package com.rogic.autochess.autoplay

import android.content.Context
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Polyglot 开局书（Rodent 引擎书，20万条目）。
 * 开局阶段查书直接出步（0ms），走主流理论变化，省电省时。
 * 哈希算法与 python-chess polyglot 模块逐字节对拍一致（30/30 局面）。
 */
object OpeningBook {

    // v3.4.10 内容指纹（9/25 04:40 用户指令"找出问题·检查三遍"终局修复）：
    // 旧加载只校验文件长度——真机 filesDir 可能躺着"长度恰好对、内容损坏"的
    // 陈年坏书（v1.x 各代残留/半写文件）。坏书上二分=随机撞条目=恰好合法的
    // 烂步（9/20 铁律：守卫拦合法烂步拦不住）。指纹不符强制重拷 assets 原件。
    private const val RODENT_SHA = "474cba6cb43991abeb644ad46c89fa1f2bff45e1dac7c69f9dfefb966d4a931a"
    private const val ZTABLE_SHA = "9aee840377ba74c9a7fe8e254958ad312aa15766257d18823f866b070cbcc9e8"

    @Volatile var ready = false
        private set
    private var loading = false

    private var table = LongArray(781)
    private var keys = LongArray(0)
    private var moves = ShortArray(0)
    private var weights = IntArray(0)

    /** 异步加载（assets → filesDir，带字节数校验） */
    fun ensureLoaded(context: Context, onReady: ((Boolean) -> Unit)? = null) {
        if (ready) { onReady?.invoke(true); return }
        synchronized(this) {
            if (ready || loading) return
            loading = true
            thread(name = "book-load") {
                var ok = false
                try { ok = load(context.applicationContext) } catch (_: Throwable) {}
                ready = ok
                loading = false
                onReady?.invoke(ok)
            }
        }
    }

    private fun load(ctx: Context): Boolean {
        // zobrist 表
        ctx.assets.open("book/zobrist_table.bin").use { ins ->
            val buf = ByteArray(781 * 8)
            // v3.4.4 陈年鬼步根因修复：ins.read(buf) 单次调用不保证读满（Java IO
            // 约定）——短读时表尾全 0 → zobrist 丢 turn/castling/ep 维度 → 同一
            // 局面黑白轮次同哈希 → probe 查到对方步/别局面步 → 开局"送象"级鬼步
            // （f1a6 现行案）。间歇触发=设备 IO 运气，v1.x 起就在。
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) throw java.io.IOException("zobrist table truncated")
                off += n
            }
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until 781) table[i] = bb.long
        }
        // 书（释放到 filesDir 再读，避免大 assets 反复读）
        val out = File(ctx.filesDir, "book/rodent.bin")
        val expected = 2_805_680L
        // v3.4.10：长度+sha256 双校验——内容损坏的陈年文件强制重拷（尸检铁律真机化）
        val contentOk = out.exists() && out.length() == expected &&
                try { sha256Of(out) == RODENT_SHA } catch (_: Throwable) { false }
        if (!contentOk) {
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, "rodent.tmp")
            if (tmp.exists()) tmp.delete()
            ctx.assets.open("book/rodent.bin").use { ins ->
                tmp.outputStream().use { o -> ins.copyTo(o, 1 shl 16) }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true); tmp.delete()
            }
        }
        if (out.length() != expected) return false
        if (sha256Of(out) != RODENT_SHA) return false   // v3.4.10 拷贝后内容复检
        val bytes = out.readBytes()
        val n = bytes.size / 16
        // entry 布局：key(8) move(2) weight(2) learn(4)，大端
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val ks = LongArray(n); val ms = ShortArray(n); val ws = IntArray(n)
        for (i in 0 until n) {
            ks[i] = bb.long
            ms[i] = bb.short
            ws[i] = bb.short.toInt() and 0xFFFF
            bb.int // learn
        }
        // v3.4.6 终极根因修复：rodent.bin 的 keys 实测乱序（python 验证书内
        // 开局条目内容正常，但 keys[0..5] 无序、全升序=False）——二分查找在
        // 乱序数组上=随机撞条目，撞出什么步全看运气；Ba6 在开局盘恰好合法，
        // 合法性守卫拦不住"合法的烂步"→ 开局送象（f1a6/Ba6 陈年案，三版
        // 未根治的真凶）。加载时按 key 升序排序（同 key 邻接，回溯逻辑自然
        // 成立），175355 条排序毫秒级，二分从此查的是真条目。
        val order = (0 until n).sortedWith(compareBy { ks[it] })
        keys = LongArray(n); moves = ShortArray(n); weights = IntArray(n)
        for (j in 0 until n) {
            val i = order[j]
            keys[j] = ks[i]; moves[j] = ms[i]; weights[j] = ws[i]
        }
        return true
    }

    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 查书：返回加权随机选出的 uci 走法；无则 null。
     * v3.4.4 守卫：返回步必须经 board.legalMoves() 校验——书条目任何损坏/
     * 哈希口径漂移都不可能再把鬼步送进点击层。 */
    fun probe(board: Board): String? {
        if (!ready) return null
        val h = zobrist(board)
        // 二分（书按 key 升序）
        var lo = 0; var hi = keys.size
        var idx = -1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val k = keys[mid]
            when {
                k == h -> { idx = mid; break }
                k < h -> lo = mid + 1
                else -> hi = mid
            }
        }
        if (idx < 0) return null
        // 回溯到首个相同 key
        var i = idx
        while (i > 0 && keys[i - 1] == h) i--
        val cand = ArrayList<Pair<Int, Short>>()  // weight, move
        while (i < keys.size && keys[i] == h) {
            cand.add(weights[i] to moves[i])
            i++
        }
        if (cand.isEmpty()) return null
        // 加权随机（权重开根号缓和，避免单一主干 90% 垄断，保留理论多样性）
        val sqrtW = cand.map { (w, _) -> kotlin.math.sqrt(w.toDouble()) }
        val sum = sqrtW.sum()
        var r = Random.nextDouble() * sum
        var chosen = cand[0].second
        for (j in cand.indices) {
            r -= sqrtW[j]
            if (r <= 0) { chosen = cand[j].second; break }
        }
        val uci = decode(chosen) ?: return null
        // 合法性守卫：书步必须存在于当前局面合法步集合——任何层的腐坏都在此终结
        return try {
            if (board.legalMoves().any { it.toString().equals(uci, ignoreCase = true) }) uci else null
        } catch (_: Throwable) { null }
    }

    /** Polyglot 走法编码 → UCI */
    private fun decode(mv: Short): String? {
        val v = mv.toInt() and 0xFFFF
        val tf = v and 7
        val tr = (v shr 3) and 7
        val ff = (v shr 6) and 7
        val fr = (v shr 9) and 7
        val promo = (v shr 12) and 15
        val files = "abcdefgh"
        val sb = StringBuilder()
            .append(files[ff]).append(fr + 1)
            .append(files[tf]).append(tr + 1)
        return when (promo) {
            1 -> sb.append('n').toString()
            2 -> sb.append('b').toString()
            3 -> sb.append('r').toString()
            4 -> sb.append('q').toString()
            in 5..15 -> null    // 非法 promo 值，丢弃
            else -> sb.toString()
        }
    }

    /** Polyglot Zobrist（与 python-chess 逐字节一致） */
    private fun zobrist(b: Board): Long {
        var h = 0L
        for (sq in Square.values()) {
            val p = try { b.getPiece(sq) } catch (_: Throwable) { null } ?: continue
            val ti = pieceTypeIndex(p)
            if (ti < 0) continue
            val color = if (p.pieceSide == Side.WHITE) 1 else 0   // 白=1 黑=0
            h = h xor table[64 * (2 * ti + color) + sq.ordinal]
        }
        // castling（白K 768 白Q 769 黑K 770 黑Q 771）
        try {
            val cw = b.getCastleRight(Side.WHITE)
            val cb = b.getCastleRight(Side.BLACK)
            if (cw == com.github.bhlangonijr.chesslib.CastleRight.KING_SIDE ||
                cw == com.github.bhlangonijr.chesslib.CastleRight.KING_AND_QUEEN_SIDE
            ) h = h xor table[768]
            if (cw == com.github.bhlangonijr.chesslib.CastleRight.QUEEN_SIDE ||
                cw == com.github.bhlangonijr.chesslib.CastleRight.KING_AND_QUEEN_SIDE
            ) h = h xor table[769]
            if (cb == com.github.bhlangonijr.chesslib.CastleRight.KING_SIDE ||
                cb == com.github.bhlangonijr.chesslib.CastleRight.KING_AND_QUEEN_SIDE
            ) h = h xor table[770]
            if (cb == com.github.bhlangonijr.chesslib.CastleRight.QUEEN_SIDE ||
                cb == com.github.bhlangonijr.chesslib.CastleRight.KING_AND_QUEEN_SIDE
            ) h = h xor table[771]
        } catch (_: Throwable) {}
        // en passant（v3.4.5 口径对齐）：Polyglot 标准只要 ep 目标格非空就计入
        // 文件哈希——旧实现加"旁有己方兵"条件=与书生成端（python-chess）口径
        // 不一致 → 同局面不同哈希 → 二分查到错误条目 → 合法鬼步放行（Ba6 送象
        // 现行案）。口径统一，查书才查的是同一个局面。
        try {
            val ep = b.getEnPassantTarget()
            if (ep != null && ep != Square.NONE) {
                val f = ep.file.ordinal
                h = h xor table[772 + f]
            }
        } catch (_: Throwable) {}
        // turn
        if (b.sideToMove == Side.WHITE) h = h xor table[780]
        return h
    }

    private fun pieceTypeIndex(p: Piece): Int = when (p) {
        Piece.WHITE_PAWN, Piece.BLACK_PAWN -> 0
        Piece.WHITE_KNIGHT, Piece.BLACK_KNIGHT -> 1
        Piece.WHITE_BISHOP, Piece.BLACK_BISHOP -> 2
        Piece.WHITE_ROOK, Piece.BLACK_ROOK -> 3
        Piece.WHITE_QUEEN, Piece.BLACK_QUEEN -> 4
        Piece.WHITE_KING, Piece.BLACK_KING -> 5
        else -> -1
    }
}
