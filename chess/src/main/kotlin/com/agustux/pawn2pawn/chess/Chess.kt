package com.agustux.pawn2pawn.chess

enum class Side {
    WHITE, BLACK;

    val opposite: Side get() = if (this == WHITE) BLACK else WHITE
}

enum class Kind { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class Piece(val side: Side, val kind: Kind)

data class Move(val from: Int, val to: Int, val promotion: Kind? = null) {
    fun toUci(): String = squareName(from) + squareName(to) + when (promotion) {
        Kind.QUEEN -> "q"; Kind.ROOK -> "r"; Kind.BISHOP -> "b"; Kind.KNIGHT -> "n"; else -> ""
    }

    companion object {
        // returns null if malformed, doesn't check legality
        fun fromUci(s: String): Move? {
            if (s.length != 4 && s.length != 5) return null
            val from = parseSquare(s, 0) ?: return null
            val to = parseSquare(s, 2) ?: return null
            if (s.length == 4) return Move(from, to)
            val promo = when (s[4].lowercaseChar()) {
                'q' -> Kind.QUEEN; 'r' -> Kind.ROOK; 'b' -> Kind.BISHOP; 'n' -> Kind.KNIGHT
                else -> return null
            }
            return Move(from, to, promo)
        }

        private fun parseSquare(s: String, i: Int): Int? {
            val f = s[i] - 'a'
            val r = s[i + 1] - '1'
            return if (f in 0..7 && r in 0..7) r * 8 + f else null
        }

        private fun squareName(sq: Int) = "${'a' + sq % 8}${'1' + sq / 8}"
    }
}

enum class Status { ONGOING, CHECK, CHECKMATE, STALEMATE, DRAW }

/** Immutable position. */
class Position private constructor(
    private val board: Array<Piece?>,
    val turn: Side,
    private val castling: Int, // 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O
    private val epSquare: Int, // square a pawn may capture onto en passant, or -1
    private val halfmoveClock: Int,
    val lastMove: Move?,
) {
    operator fun get(square: Int): Piece? = board.getOrNull(square)

    private val legal: List<Move> by lazy {
        pseudoMoves().filter { !apply(it).isAttacked(it.kingSquareAfter(), turn.opposite) }
    }

    val status: Status by lazy {
        val inCheck = isAttacked(kingSquare(turn), turn.opposite)
        when {
            legal.isEmpty() -> if (inCheck) Status.CHECKMATE else Status.STALEMATE
            halfmoveClock >= 100 || insufficientMaterial() -> Status.DRAW
            inCheck -> Status.CHECK
            else -> Status.ONGOING
        }
    }

    fun legalMoves(): List<Move> = legal

    fun legalMovesFrom(square: Int): List<Move> = legal.filter { it.from == square }

    fun play(move: Move): Position? = if (move in legal) apply(move) else null

    private fun Move.kingSquareAfter() = if (board[from]?.kind == Kind.KING) to else kingSquare(turn)

    private fun kingSquare(side: Side) = board.indexOfFirst { it == Piece(side, Kind.KING) }

    private fun insufficientMaterial(): Boolean {
        val others = board.filter { it != null && it.kind != Kind.KING }
        return others.isEmpty() || (others.size == 1 && others[0]!!.kind.let { it == Kind.BISHOP || it == Kind.KNIGHT })
    }

    private fun isAttacked(sq: Int, by: Side): Boolean {
        if (sq < 0) return false
        fun has(s: Int, vararg kinds: Kind) = s >= 0 && board[s]?.let { it.side == by && it.kind in kinds } == true
        val pawnDr = if (by == Side.WHITE) -1 else 1
        if (has(off(sq, -1, pawnDr), Kind.PAWN) || has(off(sq, 1, pawnDr), Kind.PAWN)) return true
        if (KNIGHT_STEPS.any { (df, dr) -> has(off(sq, df, dr), Kind.KNIGHT) }) return true
        if (KING_STEPS.any { (df, dr) -> has(off(sq, df, dr), Kind.KING) }) return true
        fun ray(dirs: List<Pair<Int, Int>>, vararg kinds: Kind) = dirs.any { (df, dr) ->
            var s = off(sq, df, dr)
            while (s >= 0 && board[s] == null) s = off(s, df, dr)
            has(s, *kinds)
        }
        return ray(ROOK_DIRS, Kind.ROOK, Kind.QUEEN) || ray(BISHOP_DIRS, Kind.BISHOP, Kind.QUEEN)
    }

    private fun pseudoMoves(): List<Move> {
        val moves = ArrayList<Move>()
        val enemy = turn.opposite
        for (sq in 0..63) {
            val p = board[sq] ?: continue
            if (p.side != turn) continue
            fun target(t: Int) = t >= 0 && board[t]?.side != turn
            when (p.kind) {
                Kind.PAWN -> {
                    val dr = if (turn == Side.WHITE) 1 else -1
                    fun add(t: Int) {
                        if (t / 8 == 0 || t / 8 == 7) PROMOTIONS.forEach { moves += Move(sq, t, it) }
                        else moves += Move(sq, t)
                    }
                    val one = off(sq, 0, dr)
                    if (one >= 0 && board[one] == null) {
                        add(one)
                        val two = off(sq, 0, 2 * dr)
                        if (sq / 8 == (if (turn == Side.WHITE) 1 else 6) && board[two] == null) add(two)
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val t = off(sq, df, dr)
                        if (t >= 0 && (board[t]?.side == enemy || t == epSquare)) add(t)
                    }
                }
                Kind.KNIGHT -> KNIGHT_STEPS.forEach { (df, dr) -> off(sq, df, dr).let { if (target(it)) moves += Move(sq, it) } }
                Kind.KING -> {
                    KING_STEPS.forEach { (df, dr) -> off(sq, df, dr).let { if (target(it)) moves += Move(sq, it) } }
                    addCastling(sq, moves)
                }
                else -> {
                    val dirs = when (p.kind) {
                        Kind.ROOK -> ROOK_DIRS; Kind.BISHOP -> BISHOP_DIRS; else -> ROOK_DIRS + BISHOP_DIRS
                    }
                    for ((df, dr) in dirs) {
                        var t = off(sq, df, dr)
                        while (t >= 0 && board[t] == null) { moves += Move(sq, t); t = off(t, df, dr) }
                        if (target(t)) moves += Move(sq, t)
                    }
                }
            }
        }
        return moves
    }

    private fun addCastling(king: Int, moves: MutableList<Move>) {
        val home = if (turn == Side.WHITE) 4 else 60
        if (king != home || isAttacked(king, turn.opposite)) return
        val (kSide, qSide) = if (turn == Side.WHITE) 1 to 2 else 4 to 8
        // King side: f and g empty and not attacked (g is re-checked by the legality filter anyway).
        if (castling and kSide != 0 && board[king + 1] == null && board[king + 2] == null &&
            !isAttacked(king + 1, turn.opposite)
        ) moves += Move(king, king + 2)
        // Queen side: b, c, d empty; d not attacked.
        if (castling and qSide != 0 && board[king - 1] == null && board[king - 2] == null &&
            board[king - 3] == null && !isAttacked(king - 1, turn.opposite)
        ) moves += Move(king, king - 2)
    }

    /** Applies a pseudo-legal move without legality checks. */
    private fun apply(m: Move): Position {
        val b = board.copyOf()
        val piece = b[m.from]!!
        var capture = b[m.to] != null
        b[m.to] = if (m.promotion != null) Piece(turn, m.promotion) else piece
        b[m.from] = null
        if (piece.kind == Kind.PAWN && m.to == epSquare) {
            b[(m.from / 8) * 8 + m.to % 8] = null
            capture = true
        }
        if (piece.kind == Kind.KING && m.to - m.from == 2) { b[m.from + 1] = b[m.from + 3]; b[m.from + 3] = null }
        if (piece.kind == Kind.KING && m.from - m.to == 2) { b[m.from - 1] = b[m.from - 4]; b[m.from - 4] = null }
        val rights = castling and (rightsLost(m.from) or rightsLost(m.to)).inv()
        val ep = if (piece.kind == Kind.PAWN && kotlin.math.abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        val clock = if (piece.kind == Kind.PAWN || capture) 0 else halfmoveClock + 1
        return Position(b, turn.opposite, rights, ep, clock, m)
    }

    companion object {
        val START: Position = fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        private val PROMOTIONS = listOf(Kind.QUEEN, Kind.ROOK, Kind.BISHOP, Kind.KNIGHT)
        private val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val KING_STEPS = ROOK_DIRS + BISHOP_DIRS
        private val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)

        /** Square offset by (df, dr), or -1 if it falls off the board. */
        private fun off(sq: Int, df: Int, dr: Int): Int {
            val f = sq % 8 + df
            val r = sq / 8 + dr
            return if (f in 0..7 && r in 0..7) r * 8 + f else -1
        }

        private fun rightsLost(sq: Int) = when (sq) {
            0 -> 2; 4 -> 3; 7 -> 1; 56 -> 8; 60 -> 12; 63 -> 4; else -> 0
        }

        // FEN reader, assumes well-formed input, used for START and tests.
        internal fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            val b = arrayOfNulls<Piece>(64)
            parts[0].split('/').forEachIndexed { i, row ->
                var f = 0
                for (c in row) {
                    if (c.isDigit()) { f += c - '0'; continue }
                    val kind = Kind.entries["kqrbnp".indexOf(c.lowercaseChar())]
                    b[(7 - i) * 8 + f++] = Piece(if (c.isUpperCase()) Side.WHITE else Side.BLACK, kind)
                }
            }
            val rights = parts.getOrElse(2) { "-" }.sumOf { "KQkq".indexOf(it).let { i -> if (i < 0) 0 else 1 shl i } }
            val ep = parts.getOrElse(3) { "-" }.let { if (it == "-") -1 else (it[1] - '1') * 8 + (it[0] - 'a') }
            val clock = parts.getOrNull(4)?.toIntOrNull() ?: 0
            return Position(b, if (parts.getOrElse(1) { "w" } == "w") Side.WHITE else Side.BLACK, rights, ep, clock, null)
        }
    }
}
