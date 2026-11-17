package com.agustux.pawn2pawn.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PerftTest {
    private fun perft(p: Position, depth: Int): Long =
        if (depth == 1) p.legalMoves().size.toLong()
        else p.legalMoves().sumOf { perft(p.play(it)!!, depth - 1) }

    private fun playAll(vararg uci: String): Position =
        uci.fold(Position.START) { p, m -> assertNotNull(p.play(Move.fromUci(m)!!), "illegal: $m") }

    @Test fun startPosition() {
        listOf(20L, 400L, 8902L, 197281L).forEachIndexed { i, n -> assertEquals(n, perft(Position.START, i + 1)) }
    }

    @Test fun kiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        listOf(48L, 2039L, 97862L).forEachIndexed { i, n -> assertEquals(n, perft(p, i + 1)) }
    }

    @Test fun endgameEnPassantPins() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        listOf(14L, 191L, 2812L, 43238L).forEachIndexed { i, n -> assertEquals(n, perft(p, i + 1)) }
    }

    @Test fun promotionsAndCastlingRights() {
        val p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        listOf(6L, 264L, 9467L).forEachIndexed { i, n -> assertEquals(n, perft(p, i + 1)) }
        val q = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        listOf(44L, 1486L, 62379L).forEachIndexed { i, n -> assertEquals(n, perft(q, i + 1)) }
    }

    @Test fun uciRoundTrip() {
        assertEquals("e2e4", Move.fromUci("e2e4")!!.toUci())
        assertEquals(Move(52, 60, Kind.QUEEN), Move.fromUci("e7e8q"))
        assertEquals("e7e8q", Move(52, 60, Kind.QUEEN).toUci())
        listOf("", "e2", "e2e9", "i2e4", "e7e8k", "e2e4qq").forEach { assertNull(Move.fromUci(it), it) }
    }

    @Test fun castling() {
        val p = playAll("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1")
        assertEquals(Piece(Side.WHITE, Kind.KING), p[6])
        assertEquals(Piece(Side.WHITE, Kind.ROOK), p[5])
        assertNull(p[7])
        assertEquals(Move(4, 6), p.lastMove)
        // King moved and back: castling rights lost.
        val q = playAll("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1e2", "f8c5", "e2e1", "d7d6")
        assertNull(q.play(Move.fromUci("e1g1")!!))
    }

    @Test fun cannotCastleThroughCheck() {
        // Black rook covers f1.
        val p = Position.fromFen("4kr2/8/8/8/8/8/8/4K2R w K - 0 1")
        assertNull(p.play(Move(4, 6)))
        val q = Position.fromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        assertNotNull(q.play(Move(4, 6)))
    }

    @Test fun enPassant() {
        val p = playAll("e2e4", "a7a6", "e4e5", "d7d5")
        val after = assertNotNull(p.play(Move.fromUci("e5d6")!!))
        assertNull(after[35]) // d5 pawn removed
        assertEquals(Piece(Side.WHITE, Kind.PAWN), after[43])
        // Only available immediately.
        val late = playAll("e2e4", "a7a6", "e4e5", "d7d5", "h2h3", "h7h6")
        assertNull(late.play(Move.fromUci("e5d6")!!))
    }

    @Test fun promotion() {
        val p = playAll("h2h4", "g7g5", "h4g5", "h7h6", "g5h6", "f8g7", "h6g7", "g8f6")
        val promos = p.legalMovesFrom(54) // g7: push to g8 or capture h8, each with 4 pieces
        assertEquals(8, promos.size)
        assertEquals(setOf(Kind.QUEEN, Kind.ROOK, Kind.BISHOP, Kind.KNIGHT), promos.filter { it.to == 63 }.map { it.promotion }.toSet())
        assertNull(p.play(Move.fromUci("g7h8")!!)) // promotion piece required
        val q = assertNotNull(p.play(Move.fromUci("g7h8n")!!))
        assertEquals(Piece(Side.WHITE, Kind.KNIGHT), q[63])
    }

    @Test fun foolsMate() {
        val p = playAll("f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(Status.CHECKMATE, p.status)
        assertEquals(Side.WHITE, p.turn)
        assertEquals(0, p.legalMoves().size)
    }

    @Test fun checkAndIllegalMoves() {
        val p = playAll("e2e4", "f7f6", "d1h5")
        assertEquals(Status.CHECK, p.status)
        assertNull(p.play(Move.fromUci("a7a6")!!)) // does not resolve check
        assertNotNull(p.play(Move.fromUci("g7g6")!!))
        assertNull(Position.START.play(Move.fromUci("e2e5")!!))
        assertNull(Position.START.play(Move.fromUci("e7e5")!!)) // wrong side
        assertEquals(Status.ONGOING, Position.START.status)
        assertNull(Position.START.lastMove)
    }

    @Test fun stalemateAndDraws() {
        assertEquals(Status.STALEMATE, Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").status)
        assertEquals(Status.DRAW, Position.fromFen("7k/8/6K1/8/8/8/8/8 b - - 0 1").status)
        assertEquals(Status.DRAW, Position.fromFen("7k/8/6K1/8/8/8/8/2N5 b - - 0 1").status)
        assertEquals(Status.ONGOING, Position.fromFen("7k/8/6K1/8/8/8/8/2R5 b - - 0 1").status)
        assertEquals(Status.DRAW, Position.fromFen("7k/8/6K1/8/8/8/8/2R5 b - - 100 80").status)
    }
}
