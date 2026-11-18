package com.agustux.pawn2pawn

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock.elapsedRealtime
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agustux.pawn2pawn.chess.Move
import com.agustux.pawn2pawn.chess.Position
import com.agustux.pawn2pawn.chess.Side
import com.agustux.pawn2pawn.chess.Status
import com.agustux.pawn2pawn.net.BtLink
import com.agustux.pawn2pawn.net.Link
import com.agustux.pawn2pawn.net.LinkState
import com.agustux.pawn2pawn.net.WfdLink
import com.agustux.pawn2pawn.net.Peer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Game terms: [pick] is the colour the sender wants ('w', 'b' or 'r' for random), clock is base seconds + increment seconds (base 0 = untimed). */
data class Offer(val pick: Char, val base: Int, val inc: Int) {
    val clock get() = if (base == 0) "no clock" else "${base / 60}+$inc"
}

data class Record(val wins: Int = 0, val draws: Int = 0, val losses: Int = 0)

/**
 * Wire protocol, one line per message:
 *   hello <id|-> <loser|-> <reason|-> <whiteMs> <blackMs> <uci,uci,...|-> <name>
 *                                 sent by both on connect; resumes the game if both have the same id
 *   offer <w|b|r> <base> <inc>     propose terms; a newer offer replaces an older one
 *   start <w|b> <base> <inc> <id>  accept the other side's offer; the colour is the sender's own
 *   move <uci> <ms>                a move plus the mover's remaining clock in milliseconds
 *   resign | flag                  the sender resigned / ran out of time
 *   draw | drawok | drawno         offer a draw (only while the opponent is to move) / accept / decline
 * Each player runs their own clock and reports it with every move; only the owner calls their own flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("pawn2pawn", Context.MODE_PRIVATE)
    private val wifi = WfdLink(app)
    private val bt = BtLink(app)
    /** Saved choice, else whichever radio is already on (Wi-Fi first). */
    var bluetooth by mutableStateOf(
        if (prefs.contains("bluetooth")) prefs.getBoolean("bluetooth", false)
        else !wifiOn(app) && btOn(app),
    )
        private set
    private val active = MutableStateFlow<Link>(if (bluetooth) bt else wifi)
    private val link get() = active.value
    val linkState = active.flatMapLatest { it.state }.stateIn(viewModelScope, SharingStarted.Eagerly, LinkState.Idle)
    val peers = active.flatMapLatest { it.peers }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val systemName = active.flatMapLatest { it.systemName }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private var isHost = false

    var myName by mutableStateOf(
        prefs.getString("name", null)
            ?: Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL,
    )
        private set
    var opponentName by mutableStateOf(prefs.getString("opponent", null) ?: "opponent")
        private set
    private var renameJob: Job? = null

    /** Connected, but the other phone's hello hasn't arrived yet. */
    var syncing by mutableStateOf(false)
        private set

    // Setup
    var inSetup by mutableStateOf(true)
        private set
    var draft by mutableStateOf(Offer('r', 0, 0)) // default: random colours, no clock
    var sent by mutableStateOf<Offer?>(null)
        private set
    var received by mutableStateOf<Offer?>(null)
        private set

    // Game
    private var gameId: String? = null
    private val moves = mutableListOf<String>()
    var position by mutableStateOf(Position.START)
        private set
    var mySide by mutableStateOf(Side.WHITE)
        private set
    var selected by mutableStateOf<Int?>(null)
        private set
    var promotionChoices by mutableStateOf(emptyList<Move>())
        private set

    /** Set when the game ended by resignation or on time; [lossReason] says which. */
    var loser by mutableStateOf<Side?>(null)
        private set
    var lossReason = ""
        private set
    var drawn by mutableStateOf(false) // draw agreed
        private set
    var drawOffered by mutableStateOf(false) // incoming offer, answerable on my turn
        private set
    var drawSent by mutableStateOf(false)
        private set
    /** Results against the current opponent since the app started. */
    var record by mutableStateOf(Record())
        private set
    private var recordedGame: String? = null
    val plies get() = moves.size

    // Clocks. They only run while connected; a dropped link pauses both.
    var timed by mutableStateOf(false)
        private set
    private var incMs = 0L
    private var whiteMs by mutableStateOf(0L)
    private var blackMs by mutableStateOf(0L)
    private var turnStart = 0L
    private var now by mutableStateOf(0L)
    private var paused = true

    val isOver: Boolean
        get() = loser != null || drawn ||
            position.status.let { it == Status.CHECKMATE || it == Status.STALEMATE || it == Status.DRAW }

    /** Opponent of an unfinished game that can be resumed by reconnecting to them. */
    val pausedGame: String?
        get() = if (gameId != null && !inSetup && !isOver) opponentName else null

    /** 'w', 'l' or 'd' once the game is over, from my point of view. */
    val outcome: Char?
        get() = when {
            loser != null -> if (loser == mySide) 'l' else 'w'
            drawn -> 'd'
            position.status == Status.CHECKMATE -> if (position.turn == mySide) 'l' else 'w'
            position.status == Status.STALEMATE || position.status == Status.DRAW -> 'd'
            else -> null
        }

    fun fmt(ms: Long): String {
        val sec = ms / 1000
        return if (ms < 10_000) "0:%02d.%d".format(sec, ms % 1000 / 100) else "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun checkEnd() {
        val o = outcome ?: return
        if (recordedGame == gameId) return
        recordedGame = gameId
        record = when (o) {
            'w' -> record.copy(wins = record.wins + 1)
            'l' -> record.copy(losses = record.losses + 1)
            else -> record.copy(draws = record.draws + 1)
        }
    }

    fun remaining(side: Side): Long {
        val ms = if (side == Side.WHITE) whiteMs else blackMs
        return (if (!paused && !isOver && side == position.turn) ms - (now - turnStart) else ms).coerceAtLeast(0)
    }

    private fun setClock(side: Side, ms: Long) {
        if (side == Side.WHITE) whiteMs = ms else blackMs = ms
    }

    init {
        wifi.setName(myName)
        load()
        viewModelScope.launch {
            linkState.collect {
                if (it is LinkState.Connected) {
                    isHost = it.isHost
                    syncing = true
                    link.send(hello())
                } else {
                    syncing = false
                    pause()
                }
            }
        }
        viewModelScope.launch { active.flatMapLatest { it.incoming }.collect(::onLine) }
        viewModelScope.launch {
            while (true) {
                delay(100)
                if (inSetup || paused || !timed || isOver) continue
                now = elapsedRealtime()
                if (position.turn == mySide && remaining(mySide) == 0L) {
                    end(mySide, "out of time")
                    link.send("flag")
                }
            }
        }
    }

    // ---- messages ----

    private fun onLine(line: String) {
        val p = line.split(' ')
        when (p[0]) {
            "hello" -> onHello(p)
            "offer" -> if (inSetup || isOver) parseOffer(p, "wbr")?.let {
                if (!inSetup) toSetup()
                received = it
            }
            "start" -> parseOffer(p, "wb")?.let {
                // If both players accepted each other's offer at once, the host's terms win.
                val justStarted = !inSetup && !isOver && moves.isEmpty()
                val id = p.getOrNull(4) ?: return
                if ((inSetup && sent != null) || (justStarted && !isHost)) {
                    begin(if (it.pick == 'w') Side.BLACK else Side.WHITE, it.base, it.inc, id)
                }
            }
            "move" -> if (!inSetup && !isOver && position.turn != mySide) {
                val uci = p.getOrNull(1) ?: return
                val next = Move.fromUci(uci)?.let(position::play) ?: return
                if (timed) p.getOrNull(2)?.toLongOrNull()?.let { setClock(mySide.opposite, it.coerceAtLeast(0)) }
                moves += uci
                advance(next)
                checkEnd()
            }
            "draw" -> if (!inSetup && !isOver && position.turn == mySide) drawOffered = true
            "drawok" -> if (drawSent && !isOver) agreeDraw()
            "drawno" -> drawSent = false
            "resign" -> end(mySide.opposite, "resigned")
            "flag" -> end(mySide.opposite, "out of time")
        }
    }

    private fun parseOffer(p: List<String>, picks: String): Offer? {
        val pick = p.getOrNull(1)?.singleOrNull()?.takeIf { it in picks } ?: return null
        val base = p.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val inc = p.getOrNull(3)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Offer(pick, base, inc)
    }

    private fun hello(): String {
        val ended = loser?.let { "${it.code} ${if (lossReason == "resigned") 'r' else 't'}" } ?: if (drawn) "d d" else "- -"
        return "hello ${gameId ?: "-"} $ended $whiteMs $blackMs ${moves.joinToString(",").ifEmpty { "-" }} $myName"
    }

    private fun onHello(p: List<String>) {
        if (p.size < 8) return
        syncing = false
        val name = p.drop(7).joinToString(" ").ifBlank { "opponent" }
        if (name != opponentName) record = Record()
        opponentName = name
        prefs.edit { putString("opponent", opponentName) }
        if (gameId == null || p[1] != gameId || !resume(p)) toSetup()
    }

    /**
     * Both phones run this on each other's hello and reach the same result without another round trip.
     *
     * Moves strictly alternate and each player only ever adds their own, so the two lists can only
     * differ from the first ply where they disagree, and the move at that ply belongs to exactly one
     * player. That player's list is the truth: typically they moved, the line dropped before it
     * arrived, and their list is one longer. Each side's clock likewise comes from its owner.
     */
    private fun resume(p: List<String>): Boolean {
        val theirs = if (p[6] == "-") emptyList() else p[6].split(',')
        val theirWhite = p[4].toLongOrNull() ?: return false
        val theirBlack = p[5].toLongOrNull() ?: return false
        val k = (0 until maxOf(moves.size, theirs.size)).firstOrNull { moves.getOrNull(it) != theirs.getOrNull(it) }
        val list = if (k == null || (if (k % 2 == 0) Side.WHITE else Side.BLACK) == mySide) moves.toList() else theirs
        val replayed = replay(list) ?: return false

        if (p[2] == "d") drawn = true
        if (loser == null && !drawn) {
            Side.entries.firstOrNull { it.code.toString() == p[2] }?.let {
                loser = it
                lossReason = if (p[3] == "r") "resigned" else "out of time"
            }
        }
        if (mySide == Side.WHITE) blackMs = theirBlack else whiteMs = theirWhite
        moves.clear()
        moves += list
        position = replayed
        selected = null
        promotionChoices = emptyList()
        inSetup = false
        paused = false
        turnStart = elapsedRealtime()
        now = turnStart
        save()
        return true
    }

    private fun replay(list: List<String>): Position? =
        list.fold(Position.START as Position?) { pos, uci -> Move.fromUci(uci)?.let { pos?.play(it) } }

    // ---- state transitions ----

    private fun toSetup() {
        inSetup = true
        sent = null
        received = null
    }

    private fun begin(side: Side, base: Int, inc: Int, id: String) {
        gameId = id
        moves.clear()
        position = Position.START
        mySide = side
        selected = null
        promotionChoices = emptyList()
        loser = null
        drawn = false
        drawOffered = false
        drawSent = false
        timed = base > 0
        incMs = inc * 1000L
        whiteMs = base * 1000L
        blackMs = base * 1000L
        paused = false
        turnStart = elapsedRealtime()
        now = turnStart
        inSetup = false
        save()
    }

    private fun advance(next: Position) {
        position = next
        drawOffered = false
        drawSent = false
        turnStart = elapsedRealtime()
        now = turnStart
        save()
    }

    private fun end(side: Side, reason: String) {
        if (inSetup || isOver) return
        freeze()
        lossReason = reason
        loser = side
        save()
        checkEnd()
    }

    /** Fold the running clock's elapsed time into its stored value. */
    private fun freeze() {
        if (paused || inSetup || isOver) return
        now = elapsedRealtime()
        setClock(position.turn, remaining(position.turn))
        turnStart = now
    }

    private fun pause() {
        freeze()
        paused = true
        if (gameId != null) save()
    }

    // ---- persistence: the unfinished game survives the app being closed ----

    private fun save() = prefs.edit {
        putString("id", gameId)
        putString("side", mySide.code.toString())
        putBoolean("timed", timed)
        putLong("inc", incMs)
        putLong("white", whiteMs)
        putLong("black", blackMs)
        putString("moves", moves.joinToString(","))
        putString("loser", loser?.code?.toString())
        putString("reason", lossReason)
        putBoolean("drawn", drawn)
    }

    private fun load() {
        val id = prefs.getString("id", null) ?: return
        val list = prefs.getString("moves", "").orEmpty().split(',').filter { it.isNotEmpty() }
        position = replay(list) ?: return
        gameId = id
        moves += list
        mySide = if (prefs.getString("side", "w") == "b") Side.BLACK else Side.WHITE
        timed = prefs.getBoolean("timed", false)
        incMs = prefs.getLong("inc", 0)
        whiteMs = prefs.getLong("white", 0)
        blackMs = prefs.getLong("black", 0)
        loser = Side.entries.firstOrNull { it.code.toString() == prefs.getString("loser", null) }
        lossReason = prefs.getString("reason", "").orEmpty()
        drawn = prefs.getBoolean("drawn", false)
        if (isOver) recordedGame = id
        inSetup = false
    }

    // ---- UI actions ----

    fun rename(name: String) {
        myName = name.replace('\n', ' ').take(30)
        prefs.edit { putString("name", myName) }
        renameJob?.cancel()
        renameJob = viewModelScope.launch {
            delay(800) // don't re-advertise on every keystroke
            wifi.setName(myName.trim())
        }
    }

    fun search() {
        link.stop()
        link.start()
    }

    fun connect(peer: Peer) = link.connect(peer)

    /** The security code matches the other phone's. */
    fun acceptCode() = link.accept()

    /** Switches transport; the UI restarts the search. */
    fun useBluetooth(on: Boolean) {
        if (on == bluetooth) return
        link.stop()
        bluetooth = on
        prefs.edit { putBoolean("bluetooth", on) }
        active.value = if (on) bt else wifi
    }

    fun propose() {
        sent = draft
        link.send("offer ${draft.pick} ${draft.base} ${draft.inc}")
    }

    fun accept() {
        val offer = received ?: return
        val theirs = when (offer.pick) {
            'w' -> Side.WHITE
            'b' -> Side.BLACK
            else -> if (Random.nextBoolean()) Side.WHITE else Side.BLACK
        }
        val id = Random.nextLong().toULong().toString(36)
        begin(theirs.opposite, offer.base, offer.inc, id)
        link.send("start ${mySide.code} ${offer.base} ${offer.inc} $id")
    }

    fun tap(square: Int) {
        if (isOver || position.turn != mySide) return
        promotionChoices = emptyList()
        val from = selected
        val moves = if (from == null) emptyList() else position.legalMovesFrom(from).filter { it.to == square }
        selected = null
        when {
            moves.size > 1 -> promotionChoices = moves
            moves.size == 1 -> play(moves[0])
            square != from && position[square]?.side == mySide -> selected = square
        }
    }

    fun play(move: Move) {
        promotionChoices = emptyList()
        if (isOver || paused) return
        val next = position.play(move) ?: return
        now = elapsedRealtime()
        if (timed) setClock(mySide, remaining(mySide) + incMs)
        moves += move.toUci()
        link.send("move ${move.toUci()} ${if (mySide == Side.WHITE) whiteMs else blackMs}")
        advance(next)
        checkEnd()
    }

    fun resign() {
        if (isOver) return
        end(mySide, "resigned")
        link.send("resign")
    }

    fun offerDraw() {
        if (isOver || paused || position.turn == mySide) return
        drawSent = true
        link.send("draw")
    }

    fun acceptDraw() {
        if (!drawOffered) return
        link.send("drawok")
        agreeDraw()
    }

    fun declineDraw() {
        drawOffered = false
        link.send("drawno")
    }

    private fun agreeDraw() {
        drawn = true
        drawOffered = false
        drawSent = false
        save()
        checkEnd()
    }

    /** Leave a finished game for good, so reconnecting later starts fresh instead of showing it again. */
    fun leave() {
        if (isOver) {
            gameId = null
            prefs.edit { remove("id") }
        }
        search()
    }

    fun newGame() {
        if (!isOver) return
        gameId = null
        prefs.edit { remove("id") }
        toSetup()
    }

    override fun onCleared() {
        wifi.stop()
        bt.stop()
    }
}

private val Side.code get() = if (this == Side.WHITE) 'w' else 'b'

private fun wifiOn(c: Context) =
    runCatching { (c.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true }.getOrDefault(false)

private fun btOn(c: Context) =
    runCatching { (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true }.getOrDefault(false)
