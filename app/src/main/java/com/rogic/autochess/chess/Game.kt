package com.rogic.autochess.chess

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * 对局控制器：规则、历史、悔棋、历史导航。
 * viewIndex 语义：0=初始局面，history.size=最新局面。
 */
class Game {

    enum class Mode(val label: String) {
        HUMAN_ENGINE("人机对弈"),
        ENGINE_ENGINE("引擎对引擎"),
        HUMAN_HUMAN("双人对弈")
    }

    data class MoveRecord(
        val uci: String,
        val san: String,
        val fenAfter: String,
        val moveNumber: Int,
        val side: Side
    )

    var mode: Mode = Mode.HUMAN_ENGINE
    var humanSide: Side = Side.WHITE
    var engineLevel: Int = 10
    var startFen: String = Board().fen

    val board = Board()
    val history = mutableListOf<MoveRecord>()

    /** 0..history.size；小于 size 表示正在查看历史 */
    var viewIndex: Int = 0
        private set

    var gameOver = false
    var resultText: String = ""

    /** 棋盘方向：true=白方在下 */
    var whiteAtBottom = true

    interface Listener {
        fun onPositionChanged() {}
        fun onGameOver(text: String) {}
    }

    var listener: Listener? = null

    val isViewingHistory: Boolean get() = viewIndex < history.size
    val sideToMove: Side get() = board.sideToMove

    fun reset(mode: Mode, humanSide: Side, level: Int) {
        this.mode = mode
        this.humanSide = humanSide
        this.engineLevel = level
        startFen = Board().fen
        board.loadFromFenSafe(startFen)
        history.clear()
        viewIndex = 0
        gameOver = false
        resultText = ""
        whiteAtBottom = humanSide == Side.WHITE || mode != Mode.HUMAN_ENGINE
        listener?.onPositionChanged()
    }

    fun loadFen(fen: String) {
        startFen = fen
        board.loadFromFenSafe(fen)
        history.clear()
        viewIndex = 0
        gameOver = false
        listener?.onPositionChanged()
    }

    private fun Board.loadFromFenSafe(fen: String) {
        try {
            loadFromFen(fen)
        } catch (_: Exception) { }
    }

    /** 当前显示局面的 FEN（历史导航时为历史局面） */
    fun displayFen(): String =
        if (viewIndex == 0) startFen else history[viewIndex - 1].fenAfter

    /** 当前显示局面的最后一步（用于高亮） */
    fun displayLastMoveUci(): String? =
        if (viewIndex == 0) null else history[viewIndex - 1].uci

    /** 是否允许人类在此局面落子 */
    fun humanCanMoveNow(): Boolean {
        if (gameOver || isViewingHistory) return false
        return when (mode) {
            Mode.HUMAN_ENGINE -> board.sideToMove == humanSide
            Mode.HUMAN_HUMAN -> true
            Mode.ENGINE_ENGINE -> false
        }
    }

    /** 找到 from→to 的合法走法（含升变），无则返回 null */
    fun findLegalMove(from: Square, to: Square, promotion: Piece? = null): Move? {
        if (gameOver || isViewingHistory) return null
        val candidates = board.legalMoves().filter {
            it.from == from && it.to == to
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        // 多个候选 = 升变：默认后
        val white = board.sideToMove == Side.WHITE
        val queen = if (white) Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
        return if (promotion != null) candidates.firstOrNull { it.promotion == promotion }
        else candidates.firstOrNull { it.promotion == queen } ?: candidates[0]
    }

    /** 判断 from→to 是否需要弹出升变选择 */
    fun needsPromotionChoice(from: Square, to: Square): Boolean {
        val piece = board.getPiece(from)
        if (piece.pieceType != com.github.bhlangonijr.chesslib.PieceType.PAWN) return false
        val lastRank = when (piece.pieceSide) {
            Side.WHITE -> to.rank == com.github.bhlangonijr.chesslib.Rank.RANK_8
            Side.BLACK -> to.rank == com.github.bhlangonijr.chesslib.Rank.RANK_1
        }
        return lastRank && board.legalMoves().any { it.from == from && it.to == to }
    }

    /** 走一步（uci）。返回 SAN，失败返回 null */
    fun playMove(uci: String): String? {
        val move = parseMove(uci) ?: return null
        val san: String
        val fenAfter: String
        try {
            san = San.ofMoveAndApply(board, move)
            fenAfter = board.fen
        } catch (_: Exception) {
            return null
        }
        val record = MoveRecord(
            uci = uci,
            san = san,
            fenAfter = fenAfter,
            moveNumber = board.moveCounter,
            side = if (board.sideToMove == Side.BLACK) Side.WHITE else Side.BLACK
        )
        history.add(record)
        viewIndex = history.size
        checkGameOver()
        listener?.onPositionChanged()
        return san
    }

    fun parseMove(uci: String): Move? {
        val from = Square.valueOf(uci.substring(0, 2).uppercase())
        val to = Square.valueOf(uci.substring(2, 4).uppercase())
        val promo = if (uci.length >= 5) {
            val w = board.sideToMove == Side.WHITE
            when (uci[4]) {
                'q' -> if (w) Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
                'r' -> if (w) Piece.WHITE_ROOK else Piece.BLACK_ROOK
                'b' -> if (w) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
                'n' -> if (w) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
                else -> null
            }
        } else null
        return findLegalMove(from, to, promo)
    }

    /** 悔棋。人机模式退回到人类行棋时刻 */
    fun undo(): Boolean {
        if (history.isEmpty()) return false
        val steps = when (mode) {
            Mode.HUMAN_ENGINE -> {
                // 退到人类执子为止
                var n = 0
                while (history.isNotEmpty() && n < 4) {
                    val last = history.last()
                    history.removeAt(history.lastIndex)
                    try { board.undoMove() } catch (_: Exception) { board.loadFromFenSafe(lastOrStart()) }
                    n++
                    if (last.side == humanSide) break
                }
                0 // 已直接执行
            }
            else -> {
                val last = history.removeAt(history.lastIndex)
                try { board.undoMove() } catch (_: Exception) { board.loadFromFenSafe(lastOrStart()) }
                0
            }
        }
        viewIndex = history.size
        gameOver = false
        resultText = ""
        listener?.onPositionChanged()
        return true
    }

    private fun lastOrStart(): String =
        if (history.isEmpty()) startFen else history.last().fenAfter

    /** 历史导航 */
    fun stepBack(): Boolean = if (viewIndex > 0) { viewIndex--; listener?.onPositionChanged(); true } else false
    fun stepForward(): Boolean = if (viewIndex < history.size) { viewIndex++; listener?.onPositionChanged(); true } else false
    fun jumpToStart() { viewIndex = 0; listener?.onPositionChanged() }
    fun jumpToEnd() { viewIndex = history.size; listener?.onPositionChanged() }
    fun jumpToViewIndex(index: Int) {
        val i = index.coerceIn(0, history.size)
        if (i != viewIndex) { viewIndex = i; listener?.onPositionChanged() }
    }

    private fun checkGameOver() {
        val text = when {
            board.isMated -> "将杀！${if (board.sideToMove == Side.WHITE) "黑方" else "白方"}获胜"
            board.isDraw -> when {
                board.isStaleMate -> "逼和，和棋"
                board.isInsufficientMaterial -> "子力不足，和棋"
                board.isRepetition -> "三次重复局面，和棋"
                else -> "和棋"
            }
            else -> null
        }
        if (text != null) {
            gameOver = true
            resultText = text
            listener?.onGameOver(text)
        }
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
