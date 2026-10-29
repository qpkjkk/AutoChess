package com.rogic.autochess

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.rogic.autochess.autoplay.MyBook

/**
 * 我的库管理（v1.10.2 用户需求）：
 *  · 列表：每条谱一个标题行（标题+步数），点进详情；长按删删除
 *  · 详情：棋盘显示当前浏览局面；◀▶ 翻看谱的历史；点棋盘走子试演；
 *    走完弹「保存修改/放弃」；顶部标题可改
 */
class MyBookActivity : AppCompatActivity() {

    private enum class Mode { LIST, DETAIL }
    private var mode = Mode.LIST
    private var currentIdx = -1

    // 详情态
    private lateinit var board: Board
    private var viewIdx = 0        // 0=谱末（最新），k=回退 k 步
    private var pendingEdit: MutableList<String>? = null   // 试演分支（从 viewIdx 处新走法）
    private var boardView: BoardEditView? = null
    private var tvTitle: TextView? = null
    private var tvMoveList: TextView? = null
    private var tvPos: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyBook.ensureLoaded(this)
        // v1.12.0：加载主页面同源棋子素材（SVG→Bitmap 缓存；cburnett 皮肤）
        com.rogic.autochess.chess.PieceAssets.ensureLoaded(this)
        showList()
    }

    private fun lines() = MyBook.all()

    // ─────────── 列表 ───────────
    @SuppressLint("SetTextI18n")
    private fun showList() {
        mode = Mode.LIST
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
        }

        val title = TextView(this).apply {
            text = "我的库（${lines().size} 条谱）"
            textSize = 20f
            setTextColor(Color.parseColor("#ECEFF6"))
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        if (lines().isEmpty()) {
            root.addView(TextView(this).apply {
                text = "还没有谱。连线后点悬浮窗「记谱」开始录制。"
                textSize = 14f
                setTextColor(Color.parseColor("#9AA6BC"))
            })
        }

        lines().forEachIndexed { i, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 20, 8, 20)
                setBackgroundColor(Color.parseColor("#1B2230"))
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = line.title
                textSize = 15f
                setTextColor(Color.parseColor("#ECEFF6"))
            })
            info.addView(TextView(this).apply {
                text = "${line.moves.size} 步 · ${if (line.startWhite) "白先" else "黑先"}"
                textSize = 12f
                setTextColor(Color.parseColor("#9AA6BC"))
            })
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(info, lp)

            val del = TextView(this).apply {
                text = "删除"
                textSize = 13f
                setTextColor(Color.parseColor("#FF8A80"))
                setPadding(24, 8, 24, 8)
            }
            del.setOnClickListener {
                AlertDialog.Builder(this@MyBookActivity)
                    .setTitle("删除该谱？")
                    .setMessage(line.title)
                    .setPositiveButton("删除") { _, _ -> MyBook.removeLine(line); showList() }
                    .setNegativeButton("取消", null).show()
            }
            row.addView(del)

            row.setOnClickListener { currentIdx = i; showDetail() }
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 })
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    // ─────────── 详情 ───────────
    @SuppressLint("SetTextI18n")
    private fun showDetail() {
        mode = Mode.DETAIL
        val line = lines().getOrNull(currentIdx) ?: run { showList(); return }
        board = Board()
        var loadOk = false
        try {
            board.loadFromFen("${line.startPlacement} ${if (line.startWhite) 'w' else 'b'} KQkq - 0 30")
            // 合法性探针：缺王摆位 loadFromFen 不报错但走法生成必炸（v1.11.1 实测）
            board.legalMoves()
            loadOk = true
        } catch (_: Exception) {}
        if (!loadOk) {
            Toast.makeText(this, "该谱起始局面损坏（识别缺子），请删除重录", Toast.LENGTH_LONG).show()
        }
        viewIdx = 0
        pendingEdit = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 40, 16, 24)
            setBackgroundColor(Color.parseColor("#141821"))
        }

        tvTitle = TextView(this).apply {
            text = line.title
            textSize = 17f
            setTextColor(Color.parseColor("#ECEFF6"))
        }
        root.addView(tvTitle)

        boardView = BoardEditView(this).apply {
            gameRef = board
            onUserMove = { from, to -> tryUserMove(from, to) }
        }
        // v1.12.2（AI 分析+实测确认）：height=0+weight=1 的弹性高度在非标准容器场景
        // 会坍缩为 0px → 棋盘不可见+点击打不中（"无子不能动"）。改确定性正方形：
        // 边=屏宽-边距，棋盘永远可见可点，不依赖 weight 分配。
        val dm = resources.displayMetrics
        val boardSize = (minOf(dm.widthPixels, dm.heightPixels) - 32).coerceAtLeast(240)
        root.addView(boardView, LinearLayout.LayoutParams(boardSize, boardSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })

        tvPos = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#7CE0B0"))
            gravity = Gravity.CENTER
        }
        root.addView(tvPos)

        tvMoveList = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA6BC"))
            setPadding(8, 12, 8, 12)
            minLines = 2
        }
        root.addView(tvMoveList)

        // 控制行：返回 / ◀ / ▶ / 保存修改
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(text: String, w: Float, onClick: () -> Unit): Button {
            val b = Button(this).apply {
                this.text = text
                textSize = 13f
                setOnClickListener { onClick() }
            }
            ctl.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w).apply {
                setMargins(4, 4, 4, 4)
            })
            return b
        }
        btn("◀ 返回", 1f) { showList() }
        btn("◀", 0.5f) { navigate(-1) }
        btn("▶", 0.5f) { navigate(1) }
        btn("存修改", 1f) { saveEdit() }
        root.addView(ctl)

        // v1.12.2：外层 ScrollView（棋盘已固定正方形尺寸不受滚动影响；
        // 棋谱文本/按钮超屏时可上下滚动查看）
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#141821"))
        }
        scroll.addView(root)
        setContentView(scroll)
        refreshDetail()
    }

    /** 导航：dir=-1 回退一步，+1 前进一步 */
    private fun navigate(dir: Int) {
        val line = lines().getOrNull(currentIdx) ?: return
        val total = line.moves.size
        // 有试演分支 → 只允许回退（试演后 ▶ 先询问放弃）
        if (pendingEdit != null && dir > 0) {
            pendingEdit = null
            refreshDetail()
            return
        }
        val newIdx = (viewIdx + dir).coerceIn(0, total)
        if (newIdx != viewIdx) { pendingEdit = null; viewIdx = newIdx; refreshDetail() }
    }

    /** uci("e2e4"/"e7e8q") → chesslib Move */
    private fun uciToMove(u: String): com.github.bhlangonijr.chesslib.move.Move {
        val from = Square.valueOf(u.substring(0, 2).uppercase())
        val to = Square.valueOf(u.substring(2, 4).uppercase())
        val promo = if (u.length >= 5) com.github.bhlangonijr.chesslib.Piece.fromFenSymbol(u[4].toString()) else null
        return if (promo != null) com.github.bhlangonijr.chesslib.move.Move(from, to, promo)
        else com.github.bhlangonijr.chesslib.move.Move(from, to)
    }

    /** 重建 board 到浏览位置（+试演分支） */
    private fun rebuildBoard() {
        val line = lines().getOrNull(currentIdx) ?: return
        board = Board()
        try { board.loadFromFen("${line.startPlacement} ${if (line.startWhite) 'w' else 'b'} KQkq - 0 30") } catch (_: Exception) {}
        val show = line.moves.dropLast(viewIdx) + (pendingEdit ?: emptyList())
        for (u in show) {
            try { board.doMove(uciToMove(u)) } catch (_: Exception) { break }
        }
    }

    private fun refreshDetail() {
        rebuildBoard()
        val line = lines().getOrNull(currentIdx) ?: return
        boardView?.gameRef = board
        boardView?.invalidate()
        val shown = line.moves.dropLast(viewIdx)
        val pos = shown.size - viewIdx.let { if (it > 0) 0 else 0 }
        tvPos?.text = "第 ${shown.size}/${line.moves.size} 步" + if (pendingEdit != null) " · 试演中" else ""
        // 着法列表（当前高亮）
        val sb = StringBuilder()
        val startNo = 1
        val all = line.moves
        for (i in all.indices) {
            val isCur = i < all.size - viewIdx && (i == all.size - viewIdx - 1 || viewIdx == 0)
            sb.append(if (i % 2 == 0) "${startNo + i / 2}. " else "")
            sb.append(all[i])
            if (viewIdx > 0 && i == all.size - viewIdx - 1) sb.append(" ▍")
            sb.append("  ")
        }
        pendingEdit?.forEach { sb.append("【试】$it ") }
        tvMoveList?.text = sb.toString().ifEmpty { "（无着法）" }
    }

    /** 详情页点击走子 → 试演分支 */
    private fun tryUserMove(from: Square, to: Square) {
        val line = lines().getOrNull(currentIdx) ?: return
        val uci = from.name.lowercase() + to.name.lowercase()
        val legal = try { board.legalMoves().any { it.from == from && it.to == to } } catch (_: Exception) { false }
        if (!legal) {
            Toast.makeText(this, "非法走法", Toast.LENGTH_SHORT).show()
            return
        }
        // 从当前位置（可能已回退 viewIdx 步）开分支
        val branch = line.moves.dropLast(viewIdx).toMutableList()
        branch.add(uci)
        pendingEdit = branch.drop(line.moves.size - viewIdx).toMutableList().let { ed ->
            // ed = 相对谱末新增的着法
            ArrayList(ed.subList(line.moves.size - viewIdx, ed.size))
        }
        // 简化：pendingEdit 记录从浏览点起的新着法
        pendingEdit = arrayListOf(uci)
        if (viewIdx > 0) {
            // 回退态试演：保留回退后的谱前缀 + 新着（saveEdit 里处理）
        }
        refreshDetail()
        AlertDialog.Builder(this)
            .setTitle("试演 $uci")
            .setMessage("保存这条修改？（谱将从当前浏览处截断后接此着法）")
            .setPositiveButton("保存") { _, _ -> saveEditNow(uci) }
            .setNegativeButton("继续试演") { _, _ -> }
            .setNeutralButton("撤销") { _, _ -> pendingEdit = null; refreshDetail() }
            .show()
    }

    private fun saveEdit() {
        if (pendingEdit == null) {
            Toast.makeText(this, "先在棋盘上点走子（试演），再保存", Toast.LENGTH_SHORT).show()
            return
        }
        val uci = pendingEdit?.lastOrNull() ?: return
        saveEditNow(uci)
    }

    private fun saveEditNow(uci: String) {
        val line = lines().getOrNull(currentIdx) ?: return
        val newMoves = line.moves.dropLast(viewIdx).toMutableList()
        newMoves.add(uci)
        val newLine = MyBook.BookLine(line.title, line.startPlacement, line.startWhite, newMoves)
        MyBook.updateLine(line, newLine)
        pendingEdit = null
        viewIdx = 0
        Toast.makeText(this, "已保存修改", Toast.LENGTH_SHORT).show()
        showDetail()
    }

    // ─────────── 棋盘编辑视图（自绘） ───────────
    private class BoardEditView(ctx: Context) : View(ctx) {
        var gameRef: Board? = null
        var onUserMove: ((Square, Square) -> Unit)? = null
        private var selSq: Square? = null
        /** v1.12.1 自持棋子位图（修"我的库无棋子"：不依赖 PieceAssets 全局缓存的
         *  加载状态/尺寸竞态——构造时用本 View 上下文直接渲染 SVG/PNG 自有副本） */
        private val pieceBmps = HashMap<String, android.graphics.Bitmap>()

        init {
            val am = ctx.assets
            for (key in listOf("wK","wQ","wR","wB","wN","wP","bK","bQ","bR","bB","bN","bP")) {
                val bmp = try {
                    val svg = com.caverock.androidsvg.SVG.getFromInputStream(am.open("pieces/cburnett/$key.svg"))
                    svg.setDocumentWidth(288f); svg.setDocumentHeight(288f)
                    val pic = svg.renderToPicture()
                    android.graphics.Bitmap.createBitmap(288, 288, android.graphics.Bitmap.Config.ARGB_8888).also {
                        Canvas(it).drawPicture(pic)
                    }
                } catch (_: Exception) {
                    try { android.graphics.BitmapFactory.decodeStream(am.open("pieces/cburnett/png/$key.png")) } catch (_: Exception) { null }
                }
                if (bmp != null) pieceBmps[key] = bmp
            }
        }

        private val light = Color.parseColor("#B5C3D6")
        private val dark = Color.parseColor("#3D4C63")
        private val selColor = Color.parseColor("#F06292")

        override fun onDraw(c: Canvas) {
            val b = gameRef ?: return
            val n = 8
            val cell = minOf(width, height) / n.toFloat()
            val ox = (width - cell * n) / 2f
            val oy = (height - cell * n) / 2f
            val p = Paint()
            for (r in 0 until n) for (col in 0 until n) {
                p.color = if ((r + col) % 2 == 0) light else dark
                c.drawRect(ox + col * cell, oy + r * cell, ox + (col + 1) * cell, oy + (r + 1) * cell, p)
            }
            // 选中高亮
            selSq?.let { sq ->
                val f = sq.file.ordinal; val rk = 7 - sq.rank.ordinal
                p.color = Color.argb(90, Color.red(selColor), Color.green(selColor), Color.blue(selColor))
                c.drawRect(ox + f * cell, oy + rk * cell, ox + (f + 1) * cell, oy + (rk + 1) * cell, p)
            }
            // 棋子：主页面同源 SVG 素材（自持位图副本，渲染与主页完全一致）
            val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (r in 0 until n) for (col in 0 until n) {
                // 视角：白在下（rank8 顶部）
                val sq = try {
                    // v1.12.4 根因修复（铁证）：chesslib Square.valueOf 必须大写（"A1"）——
                    // 小写每格必抛 IllegalArgumentException 被吞 → 棋盘格正常但零棋子+点击无效
                    // （视觉模型确认棋盘格渲染完整、谱数据有效、board 32 子俱全）
                    Square.valueOf("${"abcdefgh"[col]}${8 - r}".uppercase())
                } catch (_: Exception) { continue }
                val piece = try { b.getPiece(sq) } catch (_: Exception) { continue }
                val fen = piece.fenSymbol.toString().trim()
                if (fen.isEmpty()) continue
                val ch = fen.first()
                val key = (if (ch.isUpperCase()) "w" else "b") + ch.uppercaseChar()
                val bmp = pieceBmps[key] ?: continue
                val size = cell * 0.92f
                drawPaint.setShadowLayer(cell * 0.08f, 0f, cell * 0.04f, 0x59000000)
                c.drawBitmap(bmp, null,
                    android.graphics.RectF(
                        ox + (col + 0.5f) * cell - size / 2,
                        oy + r * cell + cell / 2 - size / 2,
                        ox + (col + 0.5f) * cell + size / 2,
                        oy + r * cell + cell / 2 + size / 2),
                    drawPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            val b = gameRef ?: return true
            val n = 8
            val cell = minOf(width, height) / n.toFloat()
            val ox = (width - cell * n) / 2f
            val oy = (height - cell * n) / 2f
            val col = ((e.x - ox) / cell).toInt()
            val r = ((e.y - oy) / cell).toInt()
            if (col !in 0..7 || r !in 0..7) return true
            val sq = try { Square.valueOf("${"abcdefgh"[col]}${8 - r}".uppercase()) } catch (_: Exception) { return true }
            val sel = selSq
            if (sel == null) {
                // 选子：只能选当前行棋方的子
                try {
                    val pc = b.getPiece(sq)
                    if (pc.fenSymbol.toString().trim().isNotEmpty() && pc.pieceSide == b.sideToMove) { selSq = sq; invalidate() }
                } catch (_: Exception) {}
            } else {
                if (sq == sel) { selSq = null; invalidate(); return true }
                onUserMove?.invoke(sel, sq)
                selSq = null
                invalidate()
            }
            return true
        }
    }
}
