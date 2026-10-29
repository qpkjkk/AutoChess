package com.rogic.autochess.chess

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.abs

/**
 * SAN（标准代数记谱）生成器——不依赖第三方实现，规则完整：
 * 兵吃子/吃过路兵/升变、王车易位、歧义消除、将军/将杀后缀。
 */
object San {

    private fun fileIdx(sq: Square): Int = sq.name[0] - 'A'
    private fun fileChar(sq: Square): Char = sq.name[0].lowercaseChar()
    private fun algebraic(sq: Square): String = sq.name.lowercase()

    /** 走子前的局面生成 SAN 主体（不含 +/# 后缀） */
    fun base(board: Board, move: Move): String {
        val piece = board.getPiece(move.from)
        val type = piece.pieceType ?: PieceType.NONE
        val isPawn = type == PieceType.PAWN
        val isKing = type == PieceType.KING

        // 王车易位
        if (isKing && abs(fileIdx(move.to) - fileIdx(move.from)) == 2) {
            return if (fileIdx(move.to) > fileIdx(move.from)) "O-O" else "O-O-O"
        }

        val captured = board.getPiece(move.to)
        // 吃过路兵：目标格无子但为兵的斜走
        val isCapture = captured != Piece.NONE ||
                (isPawn && move.from.name[0] != move.to.name[0] && captured == Piece.NONE)

        val sb = StringBuilder()
        if (!isPawn) {
            sb.append(when (type) {
                PieceType.KING -> 'K'; PieceType.QUEEN -> 'Q'; PieceType.ROOK -> 'R'
                PieceType.BISHOP -> 'B'; PieceType.KNIGHT -> 'N'
                else -> 'P'
            })
            // 歧义消除：同类型同色棋子也能走到目标格
            val ambiguous = board.legalMoves().filter { m ->
                m != move && m.to == move.to &&
                        board.getPiece(m.from).pieceType == type &&
                        board.getPiece(m.from).pieceSide == piece.pieceSide
            }
            if (ambiguous.isNotEmpty()) {
                val sameFile = ambiguous.any { fileIdx(it.from) == fileIdx(move.from) }
                val sameRank = ambiguous.any { it.from.name[1] == move.from.name[1] }
                when {
                    !sameFile -> sb.append(fileChar(move.from))
                    !sameRank -> sb.append(move.from.name[1])
                    else -> sb.append(algebraic(move.from))
                }
            }
        } else if (isCapture) {
            sb.append(fileChar(move.from))
        }
        if (isCapture) sb.append('x')
        sb.append(algebraic(move.to))

        // 升变
        val promo = move.promotion
        if (promo != null && promo != Piece.NONE) {
            sb.append('=').append(when (promo.pieceType) {
                PieceType.QUEEN -> 'Q'; PieceType.ROOK -> 'R'
                PieceType.BISHOP -> 'B'; PieceType.KNIGHT -> 'N'
                else -> 'Q'
            })
        }
        return sb.toString()
    }

    /** 完整 SAN：board 为走子前局面，check/mate 为走子后的判定结果 */
    fun full(board: Board, move: Move, check: Boolean, mate: Boolean): String =
        base(board, move) + when {
            mate -> "#"
            check -> "+"
            else -> ""
        }

    /** 便捷方法：在 board 上执行 move 并返回 SAN（board 状态会被修改） */
    fun ofMoveAndApply(board: Board, move: Move): String {
        val san = base(board, move)
        board.doMove(move)
        val check = board.isKingAttacked
        val mate = check && board.isMated
        return san + when {
            mate -> "#"
            check -> "+"
            else -> ""
        }
    }
}
