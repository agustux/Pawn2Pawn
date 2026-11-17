package com.agustux.pawn2pawn.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureTest {
    private class Side(val isHost: Boolean) {
        val out = Channel<String>(Channel.UNLIMITED)
        val incoming = MutableSharedFlow<String>(replay = 8)
        val state = MutableStateFlow<LinkState>(LinkState.Idle)
        val accepted = CompletableDeferred<Unit>()
    }

    /** Runs [body] with a connected host/client pair; [hostOut] can tamper with what the host sends. */
    private fun session(hostOut: (OutputStream) -> OutputStream = { it }, body: suspend CoroutineScope.(Side, Side) -> Unit) {
        val hIn = PipedInputStream(1 shl 16)
        val cIn = PipedInputStream(1 shl 16)
        val toClient = PipedOutputStream(cIn)
        val toHost = PipedOutputStream(hIn)
        val streams = listOf<Closeable>(hIn, cIn, toClient, toHost)
        val h = Side(true)
        val c = Side(false)
        runBlocking {
            for ((side, io) in listOf(h to (hIn to hostOut(toClient)), c to (cIn to toHost))) {
                launch(Dispatchers.IO) {
                    try {
                        secureSession(io.first, io.second, side.isHost, side.out, side.incoming, side.state, side.accepted) {
                            streams.forEach { it.close() }
                        }
                    } catch (e: IOException) {
                        side.state.value = LinkState.Failed(e.toString())
                        streams.forEach { runCatching { it.close() } }
                    }
                }
            }
            try {
                withTimeout(10_000) { body(h, c) }
            } finally {
                h.out.close()
                c.out.close()
                streams.forEach { runCatching { it.close() } } // unblocks the readers so runBlocking can return
                coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
            }
        }
    }

    @Test
    fun codesMatchAndLinesArrive() = session { h, c ->
        val hv = h.state.first { it is LinkState.Verify } as LinkState.Verify
        val cv = c.state.first { it is LinkState.Verify } as LinkState.Verify
        assertEquals(hv.code, cv.code)
        assertTrue(Regex("\\d{3} \\d{3}").matches(hv.code))
        h.accepted.complete(Unit)
        c.accepted.complete(Unit)
        h.state.first { it == LinkState.Connected(true) }
        c.state.first { it == LinkState.Connected(false) }
        h.out.send("move e2e4 1000")
        c.out.send("hello ünïcode")
        assertEquals("move e2e4 1000", c.incoming.first())
        assertEquals("hello ünïcode", h.incoming.first())
    }

    @Test
    fun notConnectedUntilBothAccept() = session { h, c ->
        h.state.first { it is LinkState.Verify }
        h.accepted.complete(Unit)
        kotlinx.coroutines.delay(300)
        assertTrue(h.state.value is LinkState.Verify && c.state.value is LinkState.Verify)
    }

    @Test
    fun tamperedFrameIsRejected() {
        var written = 0
        // Frames: commit (4+32), pkH (4+91), then the first encrypted one ("ok"); flip a byte in its ciphertext.
        val target = 4 + 32 + 4 + 91 + 4 + 1
        session({ raw ->
            object : FilterOutputStream(raw) {
                override fun write(b: Int) = super.write(if (++written == target) b xor 1 else b)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    for (i in off until off + len) write(b[i].toInt())
                }
            }
        }) { h, c ->
            h.state.first { it is LinkState.Verify }
            h.accepted.complete(Unit)
            c.accepted.complete(Unit)
            c.state.first { it is LinkState.Failed }
        }
    }
}
