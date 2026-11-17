package com.agustux.pawn2pawn

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agustux.pawn2pawn.chess.Kind
import com.agustux.pawn2pawn.chess.Piece
import com.agustux.pawn2pawn.chess.Position
import com.agustux.pawn2pawn.chess.Side
import com.agustux.pawn2pawn.chess.Status
import com.agustux.pawn2pawn.net.LinkState
import kotlinx.coroutines.delay

// Catppuccin Mocha on a true-black base.
private val Base = Color(0xFF000000)
private val Surface = Color(0xFF313244)
private val Text = Color(0xFFCDD6F4)
private val Subtext = Color(0xFFA6ADC8)
private val Overlay = Color(0xFF6C7086)
private val Accent = Color(0xFF74C7EC) // Catppuccin Mocha sapphire
private val Red = Color(0xFFF38BA8)
private val Green = Color(0xFFA6E3A1)
private val LightSquare = Color(0xFFA6ADC8)
private val DarkSquare = Color(0xFF585B70)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize().background(Base).safeDrawingPadding().padding(16.dp)) { App() }
        }
    }
}

@Composable
private fun App(vm: GameViewModel = viewModel()) {
    val context = LocalContext.current
    val bt = vm.bluetooth
    val permissions = permissions(bt)
    var granted by remember(bt) {
        mutableStateOf(permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { r -> granted = r.values.all { it } }
    // Also turns Bluetooth on if it's off. Search either way: paired phones show up without it.
    val visible = rememberLauncherForActivityResult(StartActivityForResult()) { vm.search() }
    val begin = {
        if (bt) {
            visible.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } else {
            vm.search()
        }
    }
    val state by vm.linkState.collectAsState()

    LaunchedEffect(granted, bt) {
        if (!granted) launcher.launch(permissions) else if (vm.linkState.value == LinkState.Idle) begin()
    }

    when {
        !granted -> Lobby("nearby devices permission is needed to find an opponent", Red) {
            Button("grant") { launcher.launch(permissions) }
            Transport(vm)
        }
        state is LinkState.Connected -> when {
            vm.syncing -> Lobby("syncing…", Subtext) {}
            vm.inSetup -> Setup(vm)
            else -> Game(vm)
        }
        else -> when (val s = state) {
            is LinkState.Connecting -> {
                var slow by remember(s) { mutableStateOf(false) }
                LaunchedEffect(s) {
                    delay(12_000)
                    slow = true
                }
                val hint = if (slow && !bt) "\n\nthis is taking a while. they may have missed\nor ignored the invite: ask them to accept it" else ""
                Lobby("connecting to ${s.peerName}…$hint", Subtext) { Button("cancel", onClick = vm::search) }
            }
            is LinkState.Verify -> Lobby("check that both phones show this code", Subtext) {
                Label(s.code, Accent, 32)
                if (s.accepted) Label("waiting for opponent…", Subtext) else Button("codes match", onClick = vm::acceptCode)
                Button("cancel", onClick = vm::search)
            }
            is LinkState.Failed -> Lobby(s.reason.lowercase(), Red) {
                Button("retry", onClick = begin)
                Transport(vm)
            }
            else -> {
                val peers by vm.peers.collectAsState()
                Lobby(if (peers.isEmpty()) "searching for nearby players…" else "tap a player to connect", Subtext) {
                    NameField(vm.myName, vm::rename)
                    SystemName(vm)
                    vm.pausedGame?.let { Label("game vs $it paused\nconnect to them to resume", Subtext) }
                    peers.forEach { Button(it.name.ifBlank { it.address }) { vm.connect(it) } }
                    Row {
                        Label("refresh", Overlay, modifier = Modifier.clickable(onClick = vm::search).padding(12.dp))
                        if (bt) Label("make visible", Overlay, modifier = Modifier.clickable(onClick = begin).padding(12.dp))
                    }
                    Transport(vm)
                }
            }
        }
    }
}

private fun permissions(bluetooth: Boolean): Array<String> = when {
    !bluetooth && Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    bluetooth && Build.VERSION.SDK_INT >= 31 -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
    // Android 12 ignores a FINE request without COARSE.
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

@Composable
private fun Transport(vm: GameViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("wi-fi", !vm.bluetooth) { vm.useBluetooth(false) }
        Button("bluetooth", vm.bluetooth) { vm.useBluetooth(true) }
    }
}

@Composable
private fun Lobby(status: String, statusColor: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(12.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.wn), null, Modifier.size(72.dp))
        Label("Pawn2Pawn", Accent, 28)
        Label(status, statusColor)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

private val Clocks = listOf(0 to 0, 60 to 0, 180 to 0, 180 to 2, 300 to 0, 600 to 0, 900 to 10, 1800 to 0)

/** Usual naming by estimated game length (base + 40 moves of increment). */
private fun category(base: Int, inc: Int) = when {
    base == 0 -> "untimed"
    base + 40 * inc < 180 -> "bullet"
    base + 40 * inc < 480 -> "blitz"
    base + 40 * inc < 1500 -> "rapid"
    else -> "classical"
}

@Composable
private fun Setup(vm: GameViewModel) {
    BackHandler(onBack = vm::search)
    val draft = vm.draft
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(12.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        Label("new game vs ${vm.opponentName}", Accent, 22)
        Spacer(Modifier.height(4.dp))
        Label("i play", Subtext)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf('w' to "white", 'b' to "black", 'r' to "random").forEach { (pick, name) ->
                Button(name, draft.pick == pick) { vm.draft = draft.copy(pick = pick) }
            }
        }
        Label("clock · ${category(draft.base, draft.inc)}", Subtext)
        Clocks.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (base, inc) ->
                    Button(if (base == 0) "none" else "${base / 60}+$inc", draft.base == base && draft.inc == inc) {
                        vm.draft = draft.copy(base = base, inc = inc)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.height(48.dp), Alignment.Center) {
            if (vm.sent == draft) Label("proposed · waiting for opponent…", Subtext) else Button("propose", onClick = vm::propose)
        }
        vm.received?.let {
            val colours = when (it.pick) {
                'w' -> "you play black"
                'b' -> "you play white"
                else -> "random colours"
            }
            Label("opponent proposes\n$colours · ${it.clock}", Green)
            Button("accept", onClick = vm::accept)
        }
        Label("leave", Overlay, modifier = Modifier.clickable(onClick = vm::search).padding(12.dp))
    }
}

@Composable
private fun Game(vm: GameViewModel) {
    BackHandler(onBack = vm::search)
    val pos = vm.position
    val mine = pos.turn == vm.mySide
    val (status, color) = when {
        vm.loser == vm.mySide -> "${vm.lossReason} · you lose" to Red
        vm.loser != null -> "opponent ${vm.lossReason} · you win" to Green
        pos.status == Status.CHECKMATE -> if (mine) "checkmate · you lose" to Red else "checkmate · you win" to Green
        vm.drawn -> "draw agreed" to Subtext
        pos.status == Status.STALEMATE -> "stalemate · draw" to Subtext
        pos.status == Status.DRAW -> "draw" to Subtext
        pos.status == Status.CHECK -> (if (mine) "your move · check" else "opponent's move · check") to Red
        mine -> "your move" to Accent
        else -> "opponent's move" to Overlay
    }
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(16.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        Label(status, color, 18)
        if (vm.timed) Clock(vm, vm.opponentName, vm.remaining(vm.mySide.opposite), !mine && !vm.isOver)
        Board(
            pos = pos,
            flipped = vm.mySide == Side.BLACK,
            selected = vm.selected,
            targets = vm.selected?.let { from -> pos.legalMovesFrom(from).map { it.to } }.orEmpty(),
            onTap = vm::tap,
        )
        if (vm.timed) Clock(vm, "you", vm.remaining(vm.mySide), mine && !vm.isOver)
        if (vm.isOver) Stats(vm)
        Row(Modifier.height(56.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            when {
                vm.promotionChoices.isNotEmpty() -> vm.promotionChoices.forEach { move ->
                    Image(
                        painterResource(drawableOf(Piece(vm.mySide, move.promotion!!))),
                        null,
                        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(DarkSquare).clickable { vm.play(move) },
                    )
                }
                vm.isOver -> {
                    Button("play again", onClick = vm::newGame)
                    Button("new opponent", onClick = vm::leave)
                }
                vm.drawOffered -> {
                    Label("draw offered", Subtext)
                    Button("accept", onClick = vm::acceptDraw)
                    Button("decline", onClick = vm::declineDraw)
                }
                else -> {
                    Button("resign", onClick = vm::resign)
                    if (vm.drawSent) Label("draw offered…", Subtext) else if (!mine) Button("draw", onClick = vm::offerDraw)
                }
            }
        }
    }
}

@Composable
private fun Stats(vm: GameViewModel) {
    val r = vm.record
    val moves = "${(vm.plies + 1) / 2} moves"
    val clocks = if (vm.timed) " · left ${vm.fmt(vm.remaining(vm.mySide))} / ${vm.fmt(vm.remaining(vm.mySide.opposite))}" else ""
    Label("$moves$clocks\nvs ${vm.opponentName}: ${r.wins}w ${r.draws}d ${r.losses}l", Overlay)
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("you appear as", Overlay)
        BasicTextField(
            name,
            onChange,
            Modifier.border(1.dp, Surface, shape).padding(horizontal = 12.dp, vertical = 10.dp).widthIn(min = 80.dp, max = 200.dp),
            textStyle = TextStyle(color = Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            singleLine = true,
            cursorBrush = SolidColor(Accent),
        )
    }
}

/**
 * The invite dialog (Wi-Fi) or scan list (Bluetooth) uses a system name apps can't change. Wi-Fi Direct
 * has no public screen for it, but on most phones it follows the device name under "About phone".
 */
@Composable
private fun SystemName(vm: GameViewModel) {
    val context = LocalContext.current
    val name by vm.systemName.collectAsState()
    if (name.isBlank() || name == vm.myName.trim()) return
    Label(
        "${if (vm.bluetooth) "others see" else "invites show"} \"$name\" · change ›",
        Overlay,
        modifier = Modifier.clickable {
            val about = Intent(if (vm.bluetooth) Settings.ACTION_BLUETOOTH_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS)
            runCatching { context.startActivity(about) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }.padding(8.dp),
    )
}

@Composable
private fun Clock(vm: GameViewModel, who: String, ms: Long, running: Boolean) {
    val time = vm.fmt(ms)
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Label(who, Overlay)
        Label(time, if (ms < 10_000) Red else if (running) Text else Overlay, 24)
    }
}

@Composable
private fun Board(pos: Position, flipped: Boolean, selected: Int?, targets: List<Int>, onTap: (Int) -> Unit) {
    val images = pieceImages()
    val tap by rememberUpdatedState(onTap)
    val checked = if (pos.status == Status.CHECK || pos.status == Status.CHECKMATE) {
        (0..63).firstOrNull { pos[it] == Piece(pos.turn, Kind.KING) }
    } else null

    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).pointerInput(flipped) {
            detectTapGestures { at ->
                val col = (at.x * 8 / size.width).toInt().coerceIn(0, 7)
                val row = (at.y * 8 / size.height).toInt().coerceIn(0, 7)
                tap(if (flipped) row * 8 + (7 - col) else (7 - row) * 8 + col)
            }
        },
    ) {
        val cell = size.width / 8
        for (square in 0..63) {
            val file = square % 8
            val rank = square / 8
            val origin = Offset((if (flipped) 7 - file else file) * cell, (if (flipped) rank else 7 - rank) * cell)
            val cellSize = Size(cell, cell)
            drawRect(if ((file + rank) % 2 == 0) DarkSquare else LightSquare, origin, cellSize)
            pos.lastMove?.let { if (square == it.from || square == it.to) drawRect(Accent.copy(alpha = 0.3f), origin, cellSize) }
            if (square == selected) drawRect(Accent.copy(alpha = 0.65f), origin, cellSize)
            if (square == checked) drawRect(Red.copy(alpha = 0.7f), origin, cellSize)
            pos[square]?.let {
                drawImage(
                    images.getValue(it),
    dstOffset = IntOffset((origin.x + cell * 0.1f).toInt(), (origin.y + cell * 0.1f).toInt()),
                    dstSize = IntSize((cell * 0.8f).toInt(), (cell * 0.8f).toInt()),
                )
            }
            if (square in targets) drawCircle(Base.copy(alpha = 0.45f), cell / 6, origin + Offset(cell / 2, cell / 2))
        }
    }
}

@Composable
private fun pieceImages(): Map<Piece, ImageBitmap> {
    val pieces = Side.entries.flatMap { side -> Kind.entries.map { Piece(side, it) } }
    return pieces.associateWith { ImageBitmap.imageResource(drawableOf(it)) }
}

private fun drawableOf(piece: Piece): Int = when (piece.kind) {
    Kind.KING -> if (piece.side == Side.WHITE) R.drawable.wk else R.drawable.bk
    Kind.QUEEN -> if (piece.side == Side.WHITE) R.drawable.wq else R.drawable.bq
    Kind.ROOK -> if (piece.side == Side.WHITE) R.drawable.wr else R.drawable.br
    Kind.BISHOP -> if (piece.side == Side.WHITE) R.drawable.wb else R.drawable.bb
    Kind.KNIGHT -> if (piece.side == Side.WHITE) R.drawable.wn else R.drawable.bn
    Kind.PAWN -> if (piece.side == Side.WHITE) R.drawable.wp else R.drawable.bp
}

@Composable
private fun Label(text: String, color: Color = Text, size: Int = 14, modifier: Modifier = Modifier) = BasicText(
    text,
    modifier,
    TextStyle(color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
)

@Composable
private fun Button(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.clip(shape).border(1.dp, if (selected) Accent else Surface, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Label(text, if (selected) Accent else Text)
    }
}
