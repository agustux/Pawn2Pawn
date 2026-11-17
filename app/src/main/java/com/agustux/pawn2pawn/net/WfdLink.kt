package com.agustux.pawn2pawn.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

@SuppressLint("MissingPermission")
class WfdLink(context: Context) : Link {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 4096)
    override val incoming: SharedFlow<String> = _incoming
    private val _systemName = MutableStateFlow("")
    /** This phone's Wi-Fi Direct name as the system shows it, e.g. in the other phone's invite dialog. */
    override val systemName: StateFlow<String> = _systemName

    // All guarded by `this` (outgoing is also read lock-free in send()).
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var setupStarted = false
    private var ready = false // stale group cleared; connection broadcasts before this are ignored
    private var job: Job? = null
    private var socket: Socket? = null
    private var server: ServerSocket? = null
    @Volatile private var outgoing: Channel<String>? = null
    private var accepted = CompletableDeferred<Unit>()
    private var myAddress: String? = null
    private var localName = ""
    private var devices = emptyList<WifiP2pDevice>()
    private val names = mutableMapOf<String, String>() // device address -> advertised player name

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val m = manager ?: return
            val ch = channel ?: return
            try {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val s = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (s == WifiP2pManager.WIFI_P2P_STATE_DISABLED) fail("Wi-Fi Direct is off")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        m.requestPeers(ch) { list ->
                            devices = list.deviceList.toList()
                            publishPeers()
                        }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val me = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        // Without the location/nearby permission Android hides the real address behind this placeholder.
                        myAddress = me?.deviceAddress?.takeIf { it != "02:00:00:00:00:00" }
                        me?.deviceName?.let { _systemName.value = it }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                        m.requestConnectionInfo(ch) { info -> if (info != null) onInfo(info) }
                }
            } catch (e: SecurityException) {
                fail("Permission denied")
            }
        }
    }

    @Synchronized
    override fun start() {
        teardown(unregister = true)
        val m = manager ?: return fail("Wi-Fi Direct not supported")
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && !wifi.isWifiEnabled) return fail("Turn on Wi-Fi")
        val loc = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (Build.VERSION.SDK_INT < 33 && loc != null && !LocationManagerCompat.isLocationEnabled(loc)) return fail("Turn on location (needed to find nearby phones)")
        try {
            val ch = m.initialize(app, Looper.getMainLooper(), null)
            channel = ch
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            registered = true
            _state.value = LinkState.Searching
            // Clear any stale group first, then discover regardless of the outcome.
            m.setDnsSdResponseListeners(
                ch,
                null,
                WifiP2pManager.DnsSdTxtRecordListener { domain, txt, device ->
                    if (domain.contains(SERVICE_TYPE)) {
                        names[device.deviceAddress] = txt[TXT_NAME].orEmpty().ifBlank { device.deviceName }
                        publishPeers()
                    }
                },
            )
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = discover()
                override fun onFailure(reason: Int) = discover()
            })
        } catch (e: SecurityException) {
            fail("Permission denied")
        }
    }

    private fun describe(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy (is Wi-Fi on?)"
        else -> "Wi-Fi Direct error (is Wi-Fi on?)"
    }

    /** Right after removeGroup or a Wi-Fi toggle the radio often answers ERROR/BUSY once, so retry before giving up. */
    @Synchronized
    private fun discover(attempt: Int = 1) {
        val m = manager ?: return
        val ch = channel ?: return
        if (!registered) return
        ready = true
        advertise(m, ch)
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Best effort: names show up once the other phone answers, system names until then.
                    runCatching {
                        m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), null)
                        m.discoverServices(ch, null)
                    }
                }
                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.P2P_UNSUPPORTED || attempt >= 5) return fail(describe(reason))
                    scope.launch {
                        delay(1_500L * attempt)
                        synchronized(this@WfdLink) { if (channel === ch && registered) discover(attempt + 1) }
                    }
                }
            })
        } catch (e: SecurityException) {
            fail("Permission denied")
        }
    }

    /** The name other Pawn2Pawn players see in their list. Applied immediately if discovery is running. */
    @Synchronized
    override fun setName(name: String) {
        localName = name
        val m = manager ?: return
        val ch = channel ?: return
        if (ready) advertise(m, ch)
    }

    private fun advertise(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        runCatching {
            m.clearLocalServices(ch, null)
            val txt = mapOf(TXT_NAME to localName.take(30))
            m.addLocalService(ch, WifiP2pDnsSdServiceInfo.newInstance("pawn2pawn", SERVICE_TYPE, txt), null)
        }
    }

    private fun publishPeers() {
        _peers.value = devices
            .map { d -> names[d.deviceAddress]?.let { Peer(it, d.deviceAddress, true) } ?: Peer(d.deviceName, d.deviceAddress) }
            .sortedByDescending { it.app }
    }

    @Synchronized
    override fun connect(peer: Peer) {
        if (!registered) return
        _state.value = LinkState.Connecting(peer.name)
        tryConnect(peer, 1)
    }

    /**
     * Two players inviting each other at the same moment makes connect() fail as busy. Both phones
     * know both MAC addresses, so they agree without talking: the lower address re-invites right
     * away and the higher one holds back, so its user just gets the other's invite to accept. It
     * still retries late in case the other phone never tapped. Unknown own address: random pause.
     */
    @Synchronized
    private fun tryConnect(peer: Peer, attempt: Int) {
        val m = manager ?: return
        val ch = channel ?: return
        if (setupStarted || _state.value != LinkState.Connecting(peer.name)) return
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            wps.setup = WpsInfo.PBC
        }
        try {
            m.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {} // wait for CONNECTION_CHANGED
                override fun onFailure(reason: Int) {
                    if (attempt >= 4) return fail(describe(reason))
                    val me = myAddress
                    val pause = when {
                        me == null -> Random.nextLong(500, 2500)
                        me < peer.address -> 700L
                        else -> 6_000L
                    }
                    scope.launch {
                        delay(pause)
                        if (channel === ch) tryConnect(peer, attempt + 1)
                    }
                }
            })
        } catch (e: SecurityException) {
            fail("Permission denied")
        }
    }

    @Synchronized
    override fun accept() {
        val v = _state.value as? LinkState.Verify ?: return
        _state.value = v.copy(accepted = true)
        accepted.complete(Unit)
    }

    override fun send(line: String) {
        if (_state.value is LinkState.Connected) outgoing?.trySend(line)
    }

    @Synchronized
    override fun stop() {
        teardown(unregister = true)
        _state.value = LinkState.Idle
    }

    @Synchronized
    private fun fail(reason: String) {
        teardown(unregister = true)
        _state.value = LinkState.Failed(reason)
    }

    /** Closes sockets, removes the group, stops discovery. Caller holds the lock. */
    private fun teardown(unregister: Boolean) {
        job?.cancel(); job = null
        outgoing?.close(); outgoing = null
        runCatching { socket?.close() }; socket = null
        runCatching { server?.close() }; server = null
        setupStarted = false
        ready = false
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            runCatching { m.stopPeerDiscovery(ch, null) }
            runCatching { m.cancelConnect(ch, null) }
            runCatching { m.removeGroup(ch, null) }
            runCatching { m.clearServiceRequests(ch, null) }
            runCatching { m.clearLocalServices(ch, null) }
        }
        names.clear()
        devices = emptyList()
        if (unregister) {
            if (registered) runCatching { app.unregisterReceiver(receiver) }
            registered = false
            channel = null
        }
        _peers.value = emptyList()
    }

    @Synchronized
    private fun onInfo(info: WifiP2pInfo) {
        if (!registered || !ready) return
        if (info.groupFormed) {
            val addr = info.groupOwnerAddress
            if (setupStarted || addr == null) return
            setupStarted = true
            if (_state.value is LinkState.Searching) _state.value = LinkState.Connecting("Peer")
            val m = manager
            val ch = channel
            if (m != null && ch != null) runCatching { m.stopPeerDiscovery(ch, null) }
            job = scope.launch { runSession(info.isGroupOwner, addr) }
        } else if (setupStarted) {
            fail("Disconnected")
        }
    }

    private suspend fun CoroutineScope.runSession(isHost: Boolean, ownerAddr: InetAddress) {
        var up = false
        try {
            val sock: Socket = if (isHost) {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ownerAddr, PORT)) // group interface only, not other networks the phone is on
                    soTimeout = 60_000
                }
                synchronized(this@WfdLink) { server = ss }
                ss.use { it.accept() }
            } else {
                dial(ownerAddr)
            }
            val out = Channel<String>(Channel.UNLIMITED)
            val ok = CompletableDeferred<Unit>()
            synchronized(this@WfdLink) {
                if (!isActive) {
                    sock.close()
                    return
                }
                socket = sock
                outgoing = out
                accepted = ok
            }
            up = true
            secureSession(sock.getInputStream(), sock.getOutputStream(), isHost, out, _incoming, _state, ok) { sock.close() }
        } catch (e: IOException) {
            // fall through to failure handling
        }
        if (isActive) fail(if (up) "Disconnected" else "Connection failed")
    }

    private suspend fun dial(addr: InetAddress): Socket {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(addr, PORT), 2_000)
                return s
            } catch (e: IOException) {
                runCatching { s.close() }
                if (System.currentTimeMillis() > deadline) throw e
                delay(500)
            }
        }
    }

    private companion object {
        const val PORT = 8988
        const val SERVICE_TYPE = "_pawn2pawn._tcp"
        const val TXT_NAME = "n"
    }
}
