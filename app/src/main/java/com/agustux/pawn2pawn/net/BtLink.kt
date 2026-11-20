package com.agustux.pawn2pawn.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
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
import java.util.UUID
import kotlin.random.Random

/**
 * Classic Bluetooth RFCOMM. Every phone listens and scans at once; whoever taps connects to the
 * other's listening socket (accepting side = host). Insecure sockets need no pairing. Unpaired
 * phones only show up while the other phone is discoverable (see ACTION_REQUEST_DISCOVERABLE).
 *
 * Both tapping at once: a phone that is dialing refuses incoming connections, so both dials fail
 * (the acceptor never sends [OK]). Each retries after a random 0.5–2 s and accepts incoming while
 * waiting, so the shorter wait wins and the other phone becomes its host.
 */
@SuppressLint("MissingPermission")
class BtLink(context: Context) : Link {
    private val app = context.applicationContext
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 4096)
    override val incoming: SharedFlow<String> = _incoming
    private val _systemName = MutableStateFlow("")
    override val systemName: StateFlow<String> = _systemName

    // All guarded by `this`.
    private var run = SupervisorJob() // parent of everything started by start(); replaced on teardown
    private var registered = false
    private var server: BluetoothServerSocket? = null
    private var pending: BluetoothSocket? = null // outgoing dial in flight
    private var dialJob: Job? = null
    private var socket: BluetoothSocket? = null
    @Volatile private var outgoing: Channel<String>? = null
    private var accepted = CompletableDeferred<Unit>()
    private val found = linkedMapOf<String, Peer>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (d != null) add(d)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> synchronized(this@BtLink) {
                        // A scan lasts ~12 s; keep going while the lobby is open.
                        if (registered && _state.value == LinkState.Searching) adapter?.startDiscovery()
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        if (s == BluetoothAdapter.STATE_TURNING_OFF || s == BluetoothAdapter.STATE_OFF) fail("Bluetooth is off")
                    }
                }
            } catch (e: SecurityException) {
                fail("Permission denied")
            }
        }
    }

    @Synchronized
    override fun start() {
        teardown()
        val a = adapter ?: return fail("Bluetooth not supported")
        if (!a.isEnabled) return fail("Turn on Bluetooth")
        val loc = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (Build.VERSION.SDK_INT < 31 && loc != null && !LocationManagerCompat.isLocationEnabled(loc)) {
            return fail("Turn on location (needed to find nearby phones)")
        }
        try {
            _systemName.value = a.name.orEmpty()
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            registered = true
            a.bondedDevices.orEmpty().forEach(::add)
            val ss = a.listenUsingInsecureRfcommWithServiceRecord("Pawn2Pawn", SERVICE)
            server = ss
            _state.value = LinkState.Searching
            scope.launch(run) {
                while (true) {
                    val s = try {
                        ss.accept()
                    } catch (e: IOException) {
                        return@launch // closed by teardown or once connected
                    }
                    onIncoming(s)
                }
            }
            a.startDiscovery()
        } catch (e: SecurityException) {
            fail("Permission denied")
        } catch (e: IOException) {
            fail("Bluetooth error")
        }
    }

    /** Phones and tablets only, so headphones and cars don't clutter the list. */
    @Synchronized
    private fun add(d: BluetoothDevice) {
        if (!registered) return
        val major = d.bluetoothClass?.majorDeviceClass
        if (major != BluetoothClass.Device.Major.PHONE && major != BluetoothClass.Device.Major.COMPUTER) return
        found[d.address] = Peer(d.name ?: return, d.address)
        _peers.value = found.values.toList()
    }


    @Synchronized
    override fun connect(peer: Peer) {
        if (!registered || socket != null) return
        _state.value = LinkState.Connecting(peer.name)
        dial(peer, 1)
    }

    /** Caller holds the lock. */
    private fun dial(peer: Peer, attempt: Int) {
        val a = adapter ?: return
        if (!registered || socket != null || _state.value != LinkState.Connecting(peer.name)) return
        dialJob = scope.launch(run) {
            var s: BluetoothSocket? = null
            val ok = try {
                a.cancelDiscovery() // discovery slows down or breaks connecting
                s = a.getRemoteDevice(peer.address).createInsecureRfcommSocketToServiceRecord(SERVICE)
                synchronized(this@BtLink) { pending = s }
                s.connect()
                awaitOk(s)
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                return@launch fail("Permission denied")
            }
            synchronized(this@BtLink) {
                if (pending === s) pending = null
                when {
                    !isActive || socket != null -> runCatching { s?.close() }
                    ok -> open(s!!, isHost = false)
                    else -> {
                        runCatching { s?.close() }
                        if (attempt >= MAX_DIALS) return@launch fail("Connection failed (is Pawn2Pawn open on their phone?)")
                        dialJob = scope.launch(run) {
                            delay(Random.nextLong(500, 2_000))
                            synchronized(this@BtLink) { dial(peer, attempt + 1) }
                        }
                    }
                }
            }
        }
    }

    /** True once the acceptor confirms it kept the connection; false if it closed it (it was dialing too). */
    private fun CoroutineScope.awaitOk(s: BluetoothSocket): Boolean {
        val watchdog = launch {
            delay(5_000)
            runCatching { s.close() }
        }
        try {
            val buf = ByteArray(OK.size)
            var n = 0
            while (n < buf.size) {
                val r = s.inputStream.read(buf, n, buf.size - n)
                if (r < 0) return false
                n += r
            }
            return buf.contentEquals(OK)
        } finally {
            watchdog.cancel()
        }
    }

    @Synchronized
    private fun onIncoming(s: BluetoothSocket) {
        if (!registered || socket != null || pending != null) {
            runCatching { s.close() } // busy dialing: refuse, see class doc
            return
        }
        try {
            s.outputStream.apply { write(OK); flush() }
        } catch (e: IOException) {
            runCatching { s.close() }
            return
        }
        dialJob?.cancel(); dialJob = null // they won the retry race
        open(s, isHost = true)
    }

    /** Caller holds the lock. */
    private fun open(s: BluetoothSocket, isHost: Boolean) {
        socket = s
        runCatching { server?.close() }; server = null
        runCatching { adapter?.cancelDiscovery() }
        val out = Channel<String>(Channel.UNLIMITED)
        outgoing = out
        val ok = CompletableDeferred<Unit>()
        accepted = ok
        if (isHost) _state.value = LinkState.Connecting(runCatching { s.remoteDevice.name }.getOrNull() ?: "Peer")
        scope.launch(run) {
            try {
                secureSession(s.inputStream, s.outputStream, isHost, out, _incoming, _state, ok) { s.close() }
            } catch (e: IOException) {
                // fall through
            }
            if (isActive) fail("Disconnected")
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
        teardown()
        _state.value = LinkState.Idle
    }

    @Synchronized
    private fun fail(reason: String) {
        teardown()
        _state.value = LinkState.Failed(reason)
    }

    /** Caller holds the lock. Cancels first so closing sockets below doesn't report a failure. */
    private fun teardown() {
        run.cancel()
        run = SupervisorJob()
        dialJob = null
        outgoing?.close(); outgoing = null
        runCatching { socket?.close() }; socket = null
        runCatching { pending?.close() }; pending = null
        runCatching { server?.close() }; server = null
        if (registered) {
            runCatching { adapter?.cancelDiscovery() }
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
        found.clear()
        _peers.value = emptyList()
    }

    private companion object {
        const val MAX_DIALS = 5
        val OK = "ok\n".toByteArray()
        val SERVICE: UUID = UUID.fromString("5b2f6d1e-2a7c-4f0e-9c8a-7e3d1b6a4c21")
    }
}
