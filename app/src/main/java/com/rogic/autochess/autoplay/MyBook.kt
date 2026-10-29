package com.rogic.autochess.autoplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 我的库（v1.10.2 重构：按"谱"组织，支持列表管理/棋盘浏览/手动编辑）。
 *
 * 数据模型（用户需求）：
 *  · 一条谱 = 一次录制的完整习题解法：起始局面（中盘/残局）+ 着法序列
 *  · 标题自动生成（日期+步数），列表页可删除错谱
 *  · 详情页：棋盘显示 + 左右翻看历史 + 点击走子试演 + 保存
 *  · 连线闪击：任意"轮我方"节点命中 → 直接出下一步（0ms）
 */
object MyBook {

    /** 一条谱（一行棋） */
    data class BookLine(
        var title: String,
        var startPlacement: String,   // 起始摆位（白视角标准序）
        var startWhite: Boolean,      // 起始局面行棋方是否白
        val moves: MutableList<String>    // uci 序列（起始行棋方先走）
    )

    @Volatile private var lines: MutableList<BookLine> = ArrayList()
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private var file: File? = null

    /** 闪击索引："<摆位> <w|b>" → uci（加载/变更时重建） */
    @Volatile private var index: HashMap<String, String> = HashMap()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            file = File(context.filesDir, "mybook.json")
            val f = file!!
            lines = ArrayList()
            if (f.exists()) {
                try {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val placement = o.getString("start")
                        // v1.11.1：历史坏数据（缺王摆位=空盘元凶）加载即滤除
                        if (!validPlacement(placement)) continue
                        lines.add(BookLine(
                            o.optString("title", "谱${i + 1}"),
                            placement,
                            o.optBoolean("white", true),
                            o.optJSONArray("moves").let { m ->
                                val l = ArrayList<String>()
                                for (j in 0 until m.length()) l.add(m.getString(j))
                                l
                            }
                        ))
                    }
                } catch (_: Throwable) {}
            }
            rebuildIndex()
            loaded = true
        }
    }

    /** 闪击索引重建：每条谱的每个"轮我方"节点 → 我方着法 */
    private fun rebuildIndex() {
        val idx = HashMap<String, String>()
        for (line in lines) {
            // 用 chesslib 模拟推进（在调用方线程安全做——此处只做纯字符串推演：
            // 直接从 recordStartPlacement 逐步 apply 需要棋规，改由 Service 录入时
            // 同步维护 index（见 putLine），加载时调用 rebuildIndexWith 推演）
            idx.putAll(probeIndexFor(line))
        }
        index = idx
    }

    /** 单条谱的节点索引推演（借 Board 推进，调用方保证线程安全） */
    private fun probeIndexFor(line: BookLine): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val b = com.github.bhlangonijr.chesslib.Board()
            b.loadFromFen("${line.startPlacement} ${if (line.startWhite) 'w' else 'b'} KQkq - 0 30")
            var i = 0
            while (i < line.moves.size) {
                val mv = line.moves[i]
                val placement = b.fen.split(" ")[0]
                val stm = b.sideToMove
                // 记录所有节点（行棋方视角）；probe 时按我方行棋方过滤
                out["$placement ${if (stm == com.github.bhlangonijr.chesslib.Side.WHITE) 'w' else 'b'}"] = mv
                try {
                    val from = com.github.bhlangonijr.chesslib.Square.valueOf(mv.substring(0, 2).uppercase())
                    val to = com.github.bhlangonijr.chesslib.Square.valueOf(mv.substring(2, 4).uppercase())
                    val promo = if (mv.length >= 5) com.github.bhlangonijr.chesslib.Piece.fromFenSymbol(mv[4].toString()) else null
                    val m = if (promo != null) com.github.bhlangonijr.chesslib.move.Move(from, to, promo)
                    else com.github.bhlangonijr.chesslib.move.Move(from, to)
                    b.doMove(m)
                } catch (_: Exception) { break }
                i++
            }
        } catch (_: Throwable) {}
        return out
    }

    /** 查询闪击（whiteToMove=我是否执白） */
    fun probe(placement: String, whiteToMove: Boolean): String? =
        index["$placement ${if (whiteToMove) 'w' else 'b'}"]

    /** 摆位合法性强校验（v1.11.1 空盘根因修复）：8 行×8 列 + 双王各一。
     *  chesslib 对"缺王"摆位 loadFromFen 不报错但 legalMoves 抛 MoveGeneratorException，
     *  且 getPiece 渲染异常 → 棋盘空盘只剩走法文本（用户实测）。入库前必须拦截。 */
    fun validPlacement(p: String): Boolean {
        val rows = p.split("/")
        if (rows.size != 8) return false
        var wk = 0; var bk = 0
        for (row in rows) {
            var len = 0
            for (ch in row) {
                when {
                    ch in '1'..'8' -> len += ch - '0'
                    ch == 'p' || ch == 'n' || ch == 'b' || ch == 'r' || ch == 'q' || ch == 'k' -> { len++; if (ch == 'k') bk++ }
                    ch == 'P' || ch == 'N' || ch == 'B' || ch == 'R' || ch == 'Q' || ch == 'K' -> { len++; if (ch == 'K') wk++ }
                    else -> return false
                }
            }
            if (len != 8) return false
        }
        return wk == 1 && bk == 1
    }

    /** 新增/替换一条谱（录谱结束时调用；v1.11.1：摆位非法直接拒绝——宁缺勿坏） */
    fun putLine(line: BookLine) {
        if (!validPlacement(line.startPlacement)) return
        synchronized(this) {
            // 同起始局面 → 覆盖（重录同题）
            lines.removeAll { it.startPlacement == line.startPlacement && it.startWhite == line.startWhite }
            lines.add(line)
            index.putAll(probeIndexFor(line))
            dirty = true
            save()
        }
    }

    fun updateLine(old: BookLine, new: BookLine) {
        synchronized(this) {
            val i = lines.indexOf(old)
            if (i >= 0) lines[i] = new
            rebuildIndex()
            dirty = true
            save()
        }
    }

    fun removeLine(line: BookLine) {
        synchronized(this) {
            lines.remove(line)
            rebuildIndex()
            dirty = true
            save()
        }
    }

    fun all(): List<BookLine> = lines.toList()

    val size: Int get() = index.size

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (l in lines) {
                arr.put(JSONObject().apply {
                    put("title", l.title)
                    put("start", l.startPlacement)
                    put("white", l.startWhite)
                    put("moves", JSONArray(l.moves))
                })
            }
            val tmp = File(f.parentFile, "mybook.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (_: Throwable) {}
    }

    fun flush() {
        if (dirty) { save(); dirty = false }
    }

    fun clear(context: Context) {
        synchronized(this) {
            lines = ArrayList(); index = HashMap(); dirty = false
            file = File(context.filesDir, "mybook.json")
            try { file?.delete() } catch (_: Throwable) {}
        }
    }
}
