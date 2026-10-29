package com.rogic.autochess.autoplay

import android.graphics.Bitmap
import android.graphics.Rect
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕识别模块：纯算法 CV（无模型依赖）
 * 1. locateBoard: 从截图中定位 8x8 棋盘区域（棋盘格交替性签名）
 * 2. recognize: 识别 64 格棋子（颜色统计 + 模板匹配定类型）→ FEN
 * 3. diffMove: 对比前后两帧棋盘，提取对方走棋
 */
object BoardDetector {

    /** 棋盘定位结果（全分辨率坐标） */
    data class BoardRect(val left: Int, val top: Int, val cell: Int) {
        val size: Int get() = cell * 8
        val right: Int get() = left + size
        val bottom: Int get() = top + size
        fun centerOf(col: Int, row: Int): Pair<Float, Float> =
            (left + col * cell + cell / 2f) to (top + row * cell + cell / 2f)
    }

    /** 单格识别结果 */
    enum class CellState { EMPTY, WHITE, BLACK }

    data class Recognition(
        val rect: BoardRect,
        /** cells[row][col]，row=0 为屏幕顶部行 */
        val cells: Array<Array<CellState>>,
        /** 每格识别的棋子类型字符（PNBRQK），大写白小写黑，'.'空，'?'不确定 */
        val types: Array<Array<Char>>,
        /** 白方是否在屏幕下方 */
        val whiteAtBottom: Boolean,
        /** 识别到的棋子总数（诊断用） */
        val pieceCount: Int = 0,
        /** 其中白子数（诊断用） */
        val whiteCount: Int = 0,
        /**
         * 网格线性拟合（坐标精定位的秘密）：x = fitX[0]*col + fitX[1]，y = fitY[0]*row + fitY[1]。
         * 用全部棋子框中心做最小二乘拟合整条网格线，消除"框除以8"的逐格累积误差。
         * null = 拟合数据不足，调用方回退 rect 均分。
         */
        val fitX: FloatArray? = null,
        val fitY: FloatArray? = null
    )

    /** 识别结果的 8 行文本渲染（诊断上屏用）：大写=白 小写=黑 .=空 */
    fun asciiBoard(rec: Recognition): String = buildString {
        for (r in 0 until 8) {
            for (c in 0 until 8) append(rec.types[r][c])
            append('\n')
        }
    }

    /** 识别矩阵签名（帧间一致性投票用）：64 格类型字符拼接 */
    fun matrixSignature(rec: Recognition): String = buildString {
        for (r in 0 until 8) for (c in 0 until 8) append(rec.types[r][c])
    }

    // ─────────────── 模型识别（YOLO 主路径） ───────────────

    /**
     * YOLO 检测 → 棋盘矩阵。
     * forcedRect 非空时（手动校准）：直接用它做 64 格映射，不依赖 YOLO board 框。
     * roiOffsetX/Y 非零时（ROI 推理模式）：src 是裁剪后的棋盘区域图，
     * 所有输出坐标（rect/fitX/fitY）最后加回偏移还原全帧坐标。
     * 返回 null = 没检测到棋盘且无有效校准。
     */
    fun recognizeByYolo(src: Bitmap, detector: YoloChessDetector,
                        forcedRect: BoardRect? = null,
                        roiOffsetX: Int = 0, roiOffsetY: Int = 0): Recognition? {
        val dets = detector.detect(src) ?: return null
        // 1. 棋子目标（排除 board 类）
        val pieces = dets.filter { it.labelIdx != 0 }.sortedByDescending { it.score }
        // 去重（同位置多框——补 NMS 边角）
        val pieceList = ArrayList<YoloChessDetector.Detection>()
        for (d in pieces) {
            if (pieceList.none { kotlin.math.abs(it.cx - d.cx) < 40f && kotlin.math.abs(it.cy - d.cy) < 40f }) {
                pieceList.add(d)
            }
        }

        // 2. 棋子落格（v2.2 恢复用户认证的旧版网格拟合——v2.1.0 等差网格在真机
        //  帧上把悬浮窗/界外检出目标也当棋子拟合导致拉歪（用户实测"手动校准才能
        //  识别"=forcedRect 均分路径好、自动拟合路径坏）。旧版三段式：board 框
        //  均分落格（界外目标天然被丢弃）→ 棋子中心采样 → 每列独立最小二乘
        //  → 修正 rect。v1.0-v1.14.3 长期稳定，勿再改。）
        val rect: BoardRect = if (forcedRect != null &&
            forcedRect.left >= 0 && forcedRect.top >= 0 &&
            forcedRect.right <= src.width && forcedRect.bottom <= src.height &&
            forcedRect.cell >= 4
        ) {
            forcedRect
        } else {
            val board = dets.filter { it.labelIdx == 0 }.maxByOrNull { it.score } ?: return null
            val bw = board.w; val bh = board.h
            if (bw < 32 || bh < 32) return null
            val left = (board.cx - bw / 2).toInt().coerceIn(0, src.width - 16)
            val top = (board.cy - bh / 2).toInt().coerceIn(0, src.height - 16)
            BoardRect(left, top, (min(bw, bh) / 8f).toInt().coerceAtLeast(4))
        }
        val cell = rect.cell

        val cells = Array(8) { Array(8) { CellState.EMPTY } }
        val types = Array(8) { Array(8) { '.' } }
        var whiteCount = 0; var blackCount = 0
        // 网格拟合采样点（棋子中心 → 落格）
        val colXSamples = ArrayList<Pair<Int, Float>>()   // (col, cx)
        val rowYSamples = ArrayList<Pair<Int, Float>>()   // (row, cy)
        for (d in dets) {
            if (d.labelIdx == 0) continue
            val ch = YoloChessDetector.LABEL_TO_FEN[d.labelIdx]
            if (ch == null || ch == '.') continue
            val col = ((d.cx - rect.left) / cell).toInt()
            val row = ((d.cy - rect.top) / cell).toInt()
            if (col < 0 || col > 7 || row < 0 || row > 7) continue
            // 网格拟合采样（每个棋子都贡献样本，与是否首检无关）
            colXSamples.add(col to d.cx)
            rowYSamples.add(row to d.cy)
            // 同格多检留高分
            if (cells[row][col] != CellState.EMPTY) {
                continue
            }
            types[row][col] = ch
            if (ch.isUpperCase()) {
                cells[row][col] = CellState.WHITE; whiteCount++
            } else {
                cells[row][col] = CellState.BLACK; blackCount++
            }
        }
        // 2.5 网格线性拟合：整条网格线用全部棋子中心最小二乘，亚像素精度
        val fitX = fitGridLine(colXSamples, cell)
        val fitY = fitGridLine(rowYSamples, cell)
        // 拟合成功 → 用拟合线推导棋盘精确边界（col=-0.5 即棋盘左缘），
        // 替代 YOLO board 框（框常把棋盘外元素圈进来导致整格偏移）
        val rectRefined: BoardRect = if (fitX != null && fitY != null) {
            val leftR = (fitX[1] - fitX[0] / 2f).toInt()
            val topR = (fitY[1] - fitY[0] / 2f).toInt()
            val cellR = ((fitX[0] + fitY[0]) / 2f).toInt().coerceAtLeast(4)
            if (leftR >= 0 && topR >= 0 &&
                leftR + cellR * 8 <= src.width && topR + cellR * 8 <= src.height
            ) {
                BoardRect(leftR, topR, cellR)
            } else {
                rect
            }
        } else {
            rect
        }
        // 3. 白方位置：白子集中半区
        var wBottom = 0; var wTop = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            if (cells[r][c] == CellState.WHITE) { if (r >= 4) wBottom++ else wTop++ }
        }
        return Recognition(
            BoardRect(rectRefined.left + roiOffsetX, rectRefined.top + roiOffsetY, rectRefined.cell),
            cells, types,
            whiteAtBottom = wBottom >= wTop,
            pieceCount = whiteCount + blackCount,
            whiteCount = whiteCount,
            fitX = fitX?.let { floatArrayOf(it[0], it[1] + roiOffsetX) },
            fitY = fitY?.let { floatArrayOf(it[0], it[1] + roiOffsetY) }
        )
    }

    /** v2.1 等差网格拟合：中心值集合 → (c0, d)（8 条等距线）。失败返回 null。 */
    private fun fitUniformGrid(vals: List<Float>): FloatArray? {
        if (vals.size < 8) return null
        val v = vals.sorted()
        var c0 = v.first(); var d = (v.last() - v.first()) / 7f
        if (d < 10f) return null
        var idx = IntArray(v.size)
        for (iter in 0 until 3) {
            // 最近线分配
            for (i in v.indices) {
                var best = 0; var bd = Float.MAX_VALUE
                for (k in 0 until 8) {
                    val dd = kotlin.math.abs(v[i] - (c0 + d * k))
                    if (dd < bd) { bd = dd; best = k }
                }
                idx[i] = best
            }
            if (idx.toSet().size < 2) return null
            // 最小二乘重估（x_i ≈ c0 + d*idx_i）
            var sx = 0f; var sy = 0f; var sxx = 0f; var sxy = 0f; val n = v.size
            for (i in v.indices) { val k = idx[i].toFloat(); sx += k; sy += v[i]; sxx += k * k; sxy += k * v[i] }
            val det = n * sxx - sx * sx
            if (kotlin.math.abs(det) < 1e-6f) return null
            val nc0 = (sy * sxx - sx * sxy) / det
            val nd = (n * sxy - sx * sy) / det
            if (nd < 10f || nd > 2000f) return null
            c0 = nc0; d = nd
        }
        return floatArrayOf(c0, d)
    }

    private fun nearestGridIdx(v: Float, grid: FloatArray): Int {
        var best = 0; var bd = Float.MAX_VALUE
        for (k in 0 until 8) {
            val dd = kotlin.math.abs(v - (grid[0] + grid[1] * k))
            if (dd < bd) { bd = dd; best = k }
        }
        return best
    }

    private fun cellXOf(r: Int, c: Int, rect: BoardRect): Float = rect.left + rect.cell * (c + 0.5f)

    /** board 框兜底矩形（v2.1 降级路径） */
    private fun boardRectOf(dets: List<YoloChessDetector.Detection>, src: Bitmap): BoardRect? {
        val board = dets.filter { it.labelIdx == 0 }.maxByOrNull { it.score } ?: return null
        val bw = board.w; val bh = board.h
        if (bw < 32 || bh < 32) return null
        val left = (board.cx - bw / 2).toInt().coerceIn(0, src.width - 16)
        val top = (board.cy - bh / 2).toInt().coerceIn(0, src.height - 16)
        return BoardRect(left, top, (min(bw, bh) / 8f).toInt().coerceAtLeast(4))
    }

    /**
     * 网格线最小二乘拟合：y = a*x + b。
     * x=格索引(0-7)，y=屏幕像素中心。至少 3 个不同索引、斜率合理才返回。
     */
    private fun fitGridLine(samples: List<Pair<Int, Float>>, cell: Int): FloatArray? {
        if (samples.size < 4) return null
        if (samples.map { it.first }.distinct().size < 3) return null
        var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((x, y) in samples) {
            n++; sx += x; sy += y; sxx += x.toDouble() * x; sxy += x.toDouble() * y
        }
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return null
        val a = (n * sxy - sx * sy) / denom
        val b = (sy - a * sx) / n
        // 斜率合理性：约等于格宽（±40% 容差），离谱则放弃拟合
        if (abs(a - cell) > cell * 0.4) return null
        return floatArrayOf(a.toFloat(), b.toFloat())
    }

    // ─────────────── 棋盘定位 ───────────────

    /**
     * 在截图中定位棋盘。算法：降采样后对每个候选(起点,格宽)计算
     * 棋盘格交替签名得分（相邻格色差大、隔一格色差小），取最高分。
     */
    fun locateBoard(src: Bitmap): BoardRect? {
        val scale = 256f / max(src.width, 1)
        val w = (src.width * scale).toInt().coerceAtLeast(64)
        val h = (src.height * scale).toInt().coerceAtLeast(64)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)

        fun lum(x: Int, y: Int): Int {
            val c = px[y * w + x]
            return ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }

        var bestScore = -1e9
        var bestX = -1; var bestY = -1; var bestS = -1

        val step = 2
        for (s in 6..min(32, w / 8)) {
            val grid = s
            if (grid * 8 > w || grid * 8 > h) continue
            for (y0 in 0..h - grid * 8 step step) {
                for (x0 in 0..w - grid * 8 step step) {
                    // 64 格中心采样，计算交替签名
                    var sumAdj = 0; var sumSame = 0; var n = 0
                    val lums = Array(8) { IntArray(8) }
                    for (r in 0 until 8) {
                        for (c in 0 until 8) {
                            lums[r][c] = lum(x0 + c * grid + grid / 2, y0 + r * grid + grid / 2)
                        }
                    }
                    for (r in 0 until 8) for (c in 0 until 8) {
                        if (c + 1 < 8) { sumAdj += abs(lums[r][c] - lums[r][c + 1]); n++ }
                        if (r + 1 < 8) { sumAdj += abs(lums[r][c] - lums[r + 1][c]); n++ }
                        if (c + 2 < 8) sumSame += abs(lums[r][c] - lums[r][c + 2])
                        if (r + 2 < 8) sumSame += abs(lums[r][c] - lums[r + 2][c])
                    }
                    // 签名：相邻差异大（棋盘双色），隔格差异小
                    val score = (sumAdj - sumSame).toDouble() / n
                    // 偏好更靠近中心的棋盘（多数 App 棋盘居中）
                    val cx = x0 + grid * 4; val cy = y0 + grid * 4
                    val centerBonus = -((cx - w / 2.0) / w + (cy - h / 2.0) / h) * 4
                    val total = score + centerBonus
                    if (total > bestScore) {
                        bestScore = total; bestX = x0; bestY = y0; bestS = grid
                    }
                }
            }
        }

        // ── v3.4.15 精化阶段（lichess 网格偏移死循环根治·9/26 云手机实测铁证!!）──
        // 根因：粗扫 step=2（缩放图!!）在 lichess 网页上收敛到偏移约半格的解
        //（实测 left=39 vs 真盘~80!!）——页面背景+盘侧坐标标注形成伪交替评分峰。
        // recognize 按偏移 rect 把子归错格 → occ 门拦(32/32) → 清 boardRect 重定位
        // → 同一确定性算法收敛回同一偏移解 → 死循环「识别质量差·重新定位中」!!
        //（鳕鱼盘占满屏+中心偏好压得住，从不暴露; lichess 网页边栏必现!!）
        // 修法（精化·非补丁!!）：粗解邻域内以 step=1 重扫 + 多点采样（每格中心+四角
        // 内缩25%均值——抗棋子/坐标数字干扰!!）+ 角格色序验证（标准棋盘 a1 深色
        // a8 浅色——伪解在偏移半格时角格色序翻转/混乱!!）——把半格偏移在精化中
        // 消掉，格子归属回到真实网格。
        if (bestS > 0) {
            fun multiLum(x0: Int, y0: Int, grid: Int, r: Int, c: Int): Int {
                // 每格 5 点采样：中心 + 四角内缩 25%（均值抗干扰）
                val cxp = x0 + c * grid; val cyp = y0 + r * grid
                val g4 = (grid / 4).coerceAtLeast(1)
                val pts = arrayOf(
                    cxp + grid / 2 to cyp + grid / 2,
                    cxp + g4 to cyp + g4,
                    cxp + grid - g4 to cyp + g4,
                    cxp + g4 to cyp + grid - g4,
                    cxp + grid - g4 to cyp + grid - g4
                )
                var s = 0
                for ((xx, yy) in pts) {
                    val xi = xx.coerceIn(0, w - 1); val yi = yy.coerceIn(0, h - 1)
                    s += lum(xi, yi)
                }
                return s / 5
            }
            fun refineScore(x0: Int, y0: Int, grid: Int): Double {
                if (x0 < 0 || y0 < 0 || x0 + grid * 8 > w || y0 + grid * 8 > h) return -1e9
                var sumAdj = 0; var sumSame = 0; var n = 0
                val lums = Array(8) { IntArray(8) }
                for (r in 0 until 8) for (c in 0 until 8)
                    lums[r][c] = multiLum(x0, y0, grid, r, c)
                for (r in 0 until 8) for (c in 0 until 8) {
                    if (c + 1 < 8) { sumAdj += abs(lums[r][c] - lums[r][c + 1]); n++ }
                    if (r + 1 < 8) { sumAdj += abs(lums[r][c] - lums[r + 1][c]); n++ }
                    if (c + 2 < 8) sumSame += abs(lums[r][c] - lums[r][c + 2])
                    if (r + 2 < 8) sumSame += abs(lums[r][c] - lums[r + 2][c])
                }
                val score = (sumAdj - sumSame).toDouble() / n
                // 角格色差验证（v3.4.15·方向无关!!）：标准棋盘 a1/a8 颜色相反（a1 深
                // a8 浅——绝对约定与屏幕朝向无关!!）。正确对齐时 |a1-a8| 色差显著;
                // 偏移半格的伪解角格采到格边缘/混合区——色差弱或混乱。双朝向兼容:
                // 只验证色差幅度（方向由 whiteAtBottom 识别层处理!!）
                val a1 = lums[7][0]; val a8 = lums[0][0]
                val cornerDiff = abs(a1 - a8).toDouble() / 255.0
                val cornerOk = cornerDiff * 1.6   // 色差显著=奖励（正比!!）——不锁方向
                val cx = x0 + grid * 4; val cy = y0 + grid * 4
                val centerBonus = -((cx - w / 2.0) / w + (cy - h / 2.0) / h) * 4
                return score + centerBonus + cornerOk
            }
            // 邻域精扫：x0/y0 ±2、s ±1（缩放图!!），步进 1
            for (dy in -2..2) for (dx in -2..2) for (ds in -1..1) {
                val gx = bestX + dx; val gy = bestY + dy; val gs = bestS + ds
                if (gs < 4 || gx < 0 || gy < 0 || gx + gs * 8 > w || gy + gs * 8 > h) continue
                val t = refineScore(gx, gy, gs)
                if (t > bestScore) { bestScore = t; bestX = gx; bestY = gy; bestS = gs }
            }
        }

        small.recycle()
        if (bestS < 0 || bestScore < 6.0) return null
        // 映射回原分辨率
        val inv = 1f / scale
        val cellFull = bestS * inv
        return BoardRect(
            (bestX * inv).toInt(), (bestY * inv).toInt(),
            cellFull.toInt().coerceAtLeast(4)
        )
    }

    // ─────────────── 棋子识别 ───────────────

    /**
     * 识别棋盘上的棋子。
     * @param templates 模板位图 map：key 如 "wK","bP"，值为与格子等宽的小图（可为 null 则跳过类型匹配）
     */
    fun recognize(
        src: Bitmap,
        rect: BoardRect,
        templates: Map<String, Bitmap>? = null
    ): Recognition? {
        // 完整边界检查：任何越界/异常 cell 直接判失败（getPixel 越界会抛异常）
        if (rect.left < 0 || rect.top < 0) return null
        if (rect.right > src.width || rect.bottom > src.height) return null
        if (rect.cell < 4) return null
        val cell = rect.cell
        // 注意：必须是 Array(8){ Array(8){...} }——arrayOf() 只建长度1数组，
        // 旧写法 Array(8){arrayOf(EMPTY)} 导致 cells[r][1] 起全越界（连线必崩的原始根因）
        val cells = Array(8) { Array(8) { CellState.EMPTY } }
        val types = Array(8) { Array(8) { '.' } }

        // ── 第一遍：每格亮度统计（均值/标准差）──
        val means = Array(8) { DoubleArray(8) }
        val stds = Array(8) { DoubleArray(8) }
        for (r in 0 until 8) for (c in 0 until 8) {
            val st = cellLumaStats(src, rect.left + c * cell, rect.top + r * cell, cell)
            means[r][c] = st.first; stds[r][c] = st.second
        }

        // ── 第二遍：空/非空（自适应阈值：棋子带来高方差，纯色空格方差低）──
        val stdFlat = stds.flatMap { it.toList() }.sorted()
        val stdMid = stdFlat[stdFlat.size / 2]
        val piece = Array(8) { BooleanArray(8) }
        fun markPieces(threshold: Double) {
            for (r in 0 until 8) for (c in 0 until 8) piece[r][c] = stds[r][c] >= threshold
        }
        markPieces(max(11.0, min(26.0, stdMid * 1.7)))
        var pieceCount = piece.sumOf { row -> row.count { it } }
        // 兜底：开局应有 32 子，识别过少说明阈值过高 → 放宽重判
        if (pieceCount in 1 until 24) {
            markPieces(max(8.0, stdMid * 1.1))
            pieceCount = piece.sumOf { row -> row.count { it } }
        }

        // ── 第三遍：白/黑（非空格按亮度最大间隔分割，适配任意皮肤配色）──
        if (pieceCount > 0) {
            val pm = ArrayList<Double>(pieceCount)
            for (r in 0 until 8) for (c in 0 until 8) if (piece[r][c]) pm.add(means[r][c])
            val sorted = pm.sorted()
            var splitIdx = 0; var maxGap = -1.0
            for (i in 1 until sorted.size) {
                val gap = sorted[i] - sorted[i - 1]
                if (gap > maxGap) { maxGap = gap; splitIdx = i }
            }
            // 分割有效：间隔显著（避免单色残局硬分）；单枚棋子默认白色
            val twoGroups = pieceCount == 1 || maxGap > 18.0 || sorted.size == 1
            val splitMean = if (pieceCount == 1) sorted[0] + 1
            else if (twoGroups) (sorted[splitIdx - 1] + sorted[splitIdx]) / 2.0
            else sorted.last() + 1   // 分不开 → 全判黑（残局同色）由王数校验兜底
            for (r in 0 until 8) for (c in 0 until 8) {
                if (!piece[r][c]) continue
                if (means[r][c] >= splitMean) {
                    cells[r][c] = CellState.WHITE; types[r][c] = 'P'
                } else {
                    cells[r][c] = CellState.BLACK; types[r][c] = 'p'
                }
            }
        }

        // 模板匹配细化类型
        if (templates != null && templates.isNotEmpty()) {
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    if (cells[r][c] == CellState.EMPTY) continue
                    val x0 = rect.left + c * cell
                    val y0 = rect.top + r * cell
                    val t = matchTemplate(src, x0, y0, cell, cells[r][c] == CellState.WHITE, templates)
                    if (t != null) types[r][c] = t
                }
            }
        }

        // 判断白方位置：明亮棋子集中的两行在下方 → 白下；统计上下半区白子数
        var whiteBottomHalf = 0; var whiteTopHalf = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            if (cells[r][c] == CellState.WHITE) {
                if (r >= 4) whiteBottomHalf++ else whiteTopHalf++
            }
        }
        val whiteAtBottom = whiteBottomHalf >= whiteTopHalf

        return Recognition(rect, cells, types, whiteAtBottom, pieceCount,
            cells.sumOf { row -> row.count { it == CellState.WHITE } })
    }

    /** 越界安全的像素读取：出界返回 0（黑），绝不抛异常 */
    private fun safeGetPixel(bmp: Bitmap, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return 0
        return try {
            bmp.getPixel(x, y)
        } catch (_: Throwable) {
            0
        }
    }

    /** 单格亮度统计：(均值, 标准差) */
    private fun cellLumaStats(src: Bitmap, x0: Int, y0: Int, cell: Int): Pair<Double, Double> {
        val inner = (cell * 0.72f).toInt().coerceAtLeast(2)
        val off = (cell - inner) / 2
        var sum = 0.0; var sumSq = 0.0; var n = 0
        val step = max(1, inner / 14)
        for (y in off until off + inner step step) {
            for (x in off until off + inner step step) {
                val px = safeGetPixel(src, x0 + x, y0 + y)
                val l = ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
                sum += l; sumSq += l.toDouble() * l; n++
            }
        }
        if (n == 0) return 0.0 to 0.0
        val mean = sum / n
        val std = kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        return mean to std
    }

    /** 模板匹配：返回识别的类型字符，null=保持原判 */
    private fun matchTemplate(
        src: Bitmap, x0: Int, y0: Int, cell: Int,
        white: Boolean, templates: Map<String, Bitmap>
    ): Char? {
        val prefix = if (white) "w" else "b"
        val keys = listOf("P", "N", "B", "R", "Q", "K").map { prefix + it }
        var bestKey: String? = null
        var bestScore = -1.0
        val sample = (cell * 0.66f).toInt().coerceAtLeast(8)
        // 提取格子中心灰度
        val off = (cell - sample) / 2
        val gray = IntArray(sample * sample)
        for (y in 0 until sample) for (x in 0 until sample) {
            val p = safeGetPixel(src, x0 + off + x, y0 + off + y)
            gray[y * sample + x] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        for (k in keys) {
            val tpl = templates[k] ?: continue
            val tw = tpl.width; val th = tpl.height
            if (tw < 4 || th < 4) continue
            // 归一化互相关（对齐采样）
            var dot = 0.0; var mag1 = 0.0; var mag2 = 0.0; var n = 0
            for (y in 0 until sample) for (x in 0 until sample) {
                val tx = x * tw / sample; val ty = y * th / sample
                val tp = tpl.getPixel(tx, ty)
                val a = tp shr 24 and 0xFF
                if (a < 100) continue  // 透明像素跳过
                val tl = ((tp shr 16 and 0xFF) * 299 + (tp shr 8 and 0xFF) * 587 + (tp and 0xFF) * 114) / 1000
                val g = gray[y * sample + x]
                dot += g.toDouble() * tl; mag1 += g.toDouble() * g; mag2 += tl.toDouble() * tl; n++
            }
            if (n < sample * sample * 0.2) continue
            val denom = kotlin.math.sqrt(mag1) * kotlin.math.sqrt(mag2)
            if (denom > 1e-6) {
                val score = dot / denom
                if (score > bestScore) { bestScore = score; bestKey = k }
            }
        }
        if (bestKey == null || bestScore < 0.55) return null
        return if (white) bestKey!![1] else bestKey!![1].lowercaseChar()
    }

    // ─────────────── FEN 构建 ───────────────

    /** 识别结果 → FEN（不含走子方/易位等字段，由调用方用 chesslib 校验补全） */
    fun toPlacement(rec: Recognition): String {
        val sb = StringBuilder()
        for (r in 0 until 8) {
            if (r > 0) sb.append('/')
            var empty = 0
            for (c in 0 until 8) {
                val t = rec.types[r][c]
                if (t == '.' || t == '?') {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(t)
                }
            }
            if (empty > 0) sb.append(empty)
        }
        return sb.toString()
    }

    /**
     * 从识别结果推断完整 FEN。
     * 白在下方：屏幕 row0=rank8, col0=fileA；黑在下方：屏幕整体旋转180°。
     * 尝试两种走子方，校验合法性（王数、兵位）。
     */
    fun inferFen(rec: Recognition): String? {
        // 开局快照：首末两对行【cells 全满】+ 中间四行【cells 全空】→ 直接标准开局 FEN
        // v1.13.9（用户 9/11"开局白王2个推算失败"根治）：形态是铁的（32 格满+4 行空
        // 只可能是开局摆位），types 识别再烂（车认成王=白王2个）也该直判——
        // 旧版要求 pieceCount==32，而 pieceCount 由 types 计算，与 cells 状态脱节
        // （某格 cells 满+types 多检/漏检）→ 快照 miss → 通用 inferFen → 2 王被拒。
        // 去掉 pieceCount 门槛：cells 形态匹配即铁律直判。
        val fullRows = BooleanArray(8) { r ->
            (0 until 8).all { c -> rec.cells[r][c] != CellState.EMPTY }
        }
        val emptyMid = (2..5).all { r -> (0 until 8).none { c -> rec.cells[r][c] != CellState.EMPTY } }
        if (emptyMid && fullRows[0] && fullRows[1] && fullRows[6] && fullRows[7]) {
            return if (rec.whiteAtBottom) {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            } else {
                "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr w KQkq - 0 1"
            }
        }
        // grid[rankIdx][fileIdx]，rankIdx 0=rank1
        val grid = Array(8) { CharArray(8) { '.' } }
        for (r in 0 until 8) for (c in 0 until 8) {
            val name = squareAt(rec, r, c).name
            val fi = name[0] - 'A'
            val ri = name[1] - '1'
            grid[ri][fi] = rec.types[r][c]
        }
        val sb = StringBuilder()
        for (ri in 7 downTo 0) {
            var empty = 0
            for (f in 0 until 8) {
                val ch = grid[ri][f]
                if (ch == '.' || ch == '?') empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (ri > 0) sb.append('/')
        }
        val placement = sb.toString()
        for (stm in listOf('w', 'b')) {
            val fen = "$placement $stm KQkq - 0 1"
            if (isValidFen(fen)) return fen
        }
        return null
    }

    /** v1.13.6 推算失败诊断（上屏破案用）：王数/王位/底线兵/加载异常分类 */
    fun inferFenDiag(rec: Recognition): String {
        try {
            val grid = Array(8) { CharArray(8) { '.' } }
            for (r in 0 until 8) for (c in 0 until 8) {
                val name = squareAt(rec, r, c).name
                val fi = name[0] - 'A'
                val ri = name[1] - '1'
                grid[ri][fi] = rec.types[r][c]
            }
            val sb = StringBuilder()
            for (ri in 7 downTo 0) {
                var empty = 0
                for (f in 0 until 8) {
                    val ch = grid[ri][f]
                    if (ch == '.' || ch == '?') empty++ else {
                        if (empty > 0) { sb.append(empty); empty = 0 }
                        sb.append(ch)
                    }
                }
                if (empty > 0) sb.append(empty)
                if (ri > 0) sb.append('/')
            }
            val placement = sb.toString()
            val wK = placement.count { it == 'K' }
            val bK = placement.count { it == 'k' }
            val rows = placement.split('/')
            val backPawn = (rows.getOrNull(0)?.contains('p') == true || rows.getOrNull(0)?.contains('P') == true ||
                    rows.getOrNull(7)?.contains('p') == true || rows.getOrNull(7)?.contains('P') == true)
            return when {
                wK != 1 -> "白王${wK}个·$placement"   // v1.13.9：带摆位串（拍照直读错位格子）
                bK != 1 -> "黑王${bK}个·$placement"
                backPawn -> "底线兵·$placement"
                else -> "未知·$placement"
            }
        } catch (_: Exception) {
            return "构造异常"
        }
    }

    private fun isValidFen(fen: String): Boolean {
        return try {
            val b = Board()
            b.loadFromFen(fen)
            val wK = b.getPieceLocation(com.github.bhlangonijr.chesslib.Piece.WHITE_KING)
            val bK = b.getPieceLocation(com.github.bhlangonijr.chesslib.Piece.BLACK_KING)
            if (wK.size != 1 || bK.size != 1) return false
            // 王相邻 = 非法局面（识别错乱常客），直接拒绝
            val wk = wK[0]; val bk = bK[0]
            val wkf = wk.file.ordinal; val wkr = wk.rank.ordinal
            val bkf = bk.file.ordinal; val bkr = bk.rank.ordinal
            if (kotlin.math.abs(wkf - bkf) <= 1 && kotlin.math.abs(wkr - bkr) <= 1) return false
            val rows = fen.substringBefore(' ').split('/')
            for (row in listOf(rows[0], rows[7])) {
                if (row.contains('P') || row.contains('p')) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 底线兵信息（升变选择中的标志） */
    data class BackRankPawn(val white: Boolean, val row: Int, val col: Int)

    /**
     * 底线兵检测：识别结果是否含"兵在第 1/8 横线"（升变选择中——弹框开着/走子未完成）。
     * 真实对局兵不可能在底线，理论无误报；但升变完成后新棋子可能被误识别成兵
     * （用户实测"验证落子卡死"），调用方必须带超时自愈，不能无限期依赖本检测。
     */
    fun pawnOnBackRank(rec: Recognition): BackRankPawn? {
        for (c in 0 until 8) {
            val top = rec.types[0][c]
            if (top == 'P') return BackRankPawn(true, 0, c)
            if (top == 'p') return BackRankPawn(false, 0, c)
            val bot = rec.types[7][c]
            if (bot == 'P') return BackRankPawn(true, 7, c)
            if (bot == 'p') return BackRankPawn(false, 7, c)
        }
        return null
    }

    /**
     * 视角重排（v1.7.1）：只改 whiteAtBottom 解读标志，cells/坐标全不动。
     * 数学等价性：inferFen/squareToScreen 的行列映射完全由 whiteAtBottom 决定——
     * 真实视角与启发式不符时，仅翻转标志即得正确解读；
     * （若同时旋转 cells 会与标志翻转相互抵消，等于没修——推导已验证）
     */
    fun reorient(rec: Recognition, whiteAtBottom: Boolean): Recognition {
        if (rec.whiteAtBottom == whiteAtBottom) return rec
        return Recognition(rec.rect, rec.cells, rec.types, whiteAtBottom,
            rec.pieceCount, rec.whiteCount, rec.fitX, rec.fitY)
    }

    /**
     * 开局视角判定（100% 可靠）：标准开局形态下底部两排是白兵 → 白在底。
     * 返回 null=非标准开局形态（不能判定）。
     */
    fun openingOrientation(rec: Recognition): Boolean? {
        if (rec.pieceCount != 32) return null
        val fullRows = BooleanArray(8) { r -> (0 until 8).all { c -> rec.cells[r][c] != CellState.EMPTY } }
        val emptyMid = (2..5).all { r -> (0 until 8).none { c -> rec.cells[r][c] != CellState.EMPTY } }
        if (!emptyMid || !fullRows[0] || !fullRows[1] || !fullRows[6] || !fullRows[7]) return null
        // 底排（row7）是白兵 → 白在底
        return rec.types[7][0] == 'P' || rec.types[7][3] == 'P' || rec.types[7][4] == 'P'
    }

    // ─────────────── 走棋差异检测 ───────────────

    /**
     * 对比两个识别结果的格子占用，返回发生变化的格子列表 (row,col)。
     * 变化格数 2=普通走子/吃子，3=吃过路兵，4=王车易位。
     */
    fun diffCells(a: Recognition, b: Recognition): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        if (a.rect != b.rect) return out  // 棋盘位置变了，不能比
        for (r in 0 until 8) for (c in 0 until 8) {
            if (a.cells[r][c] != b.cells[r][c]) out.add(r to c)
        }
        return out
    }

    /** 生成 FEN 用的标准方法：从识别结果 + 屏幕坐标 → 代数坐标映射 */
    fun squareAt(rec: Recognition, row: Int, col: Int): Square {
        // 白在下：file=col, rank=7-row；黑在下（棋盘转180°）：file=7-col, rank=row
        val f = if (rec.whiteAtBottom) col else 7 - col
        val r = if (rec.whiteAtBottom) 7 - row else row
        val name = "${'A' + f}${'1' + r}"
        return Square.valueOf(name)
    }
}
