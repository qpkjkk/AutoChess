package com.rogic.autochess.autoplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极速小模型（v1.10.0）：13 类逐格分类器（12 棋子 + 空），48×48 输入。
 * 用户主用软件（鳕鱼 VIII 皮肤）专用训练——真实截图抠图 + 增强 3.2 万样本。
 * 推理量 ~120K 参数 × 64 格批量 → 全帧 <50ms（YOLO 全帧 500ms+ 的 1/10）。
 * 管线：已知 boardRect（连线中缓存/大模型首次定位）→ 64 格 crop → 批量分类 → 摆位。
 */
object TinyClassifier {

    const val MODEL_ASSET = "models/tiny_piece48.onnx"
    /** 13 类输出 → FEN 字符（0=空） */
    private val CLS = charArrayOf(
        '.', 'K', 'Q', 'R', 'B', 'N', 'P', 'k', 'q', 'r', 'b', 'n', 'p'
    )
    const val INP = 48

    @Volatile private var session: OrtSession? = null
    private val loaded = AtomicBoolean(false)
    @Volatile var inputSize: Int = INP
        private set

    fun ready(): Boolean = session != null

    /** 从 assets 加载（~1MB，同步即可） */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded.get()) return session != null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val out = File(context.filesDir, "models/tiny_piece48.onnx")
            out.parentFile?.mkdirs()
            var ok = out.exists() && out.length() > 100_000
            if (!ok) {
                val tmp = File(out.parentFile, "tiny.tmp")
                if (tmp.exists()) tmp.delete()
                context.assets.open(MODEL_ASSET).use { ins ->
                    tmp.outputStream().use { o -> ins.copyTo(o, 1 shl 16) }
                }
                if (tmp.renameTo(out) || run { tmp.copyTo(out, true); tmp.delete(); true }) ok = true
            }
            session = env.createSession(out.absolutePath, OrtSession.SessionOptions())
            loaded.set(true)
            true
        } catch (t: Throwable) {
            loaded.set(true)
            session = null
            false
        }
    }

    /**
     * 全盘分类：已知棋盘矩形（BoardDetector.BoardRect，8×8 交点网格）→ 64 格批量推理。
     * @return Pair(types[8][8], conf[8][8])（row=0 屏幕顶部），types 大写白小写黑 '.'空；
     *         conf = softmax 最大值（v3.1.1 置信度透传：低置信格 <0.9 触发锚点复核仲裁）；
     *         失败 null
     */
    /** v3.2.2 主题感知高亮归一化（用户 06:44 c3.jpg 破案：v3.2.1 的归一化在米褐
     *  主题上是毒药——米褐标准浅格 ≈(218,214,188) 与浅蓝灰主题的高亮色同色域，
     *  归一化把米褐一半棋盘染成浅蓝灰=大面积分布外=识别劣化=verify 永不闭环）。
     *  主题门：先扫 64 格底色，米黄格 ≤4 个（高亮只有最近走子 from/to 两格）=
     *  浅蓝灰主题+真高亮 → 归一化；米黄格 ≥12 个 = 米褐主题标准底色 → 全禁用
     *  （v5 训练池含 board_brown 截图，米褐主题原生认识）。返回 null=不归一化。 */
    private fun normalizeHighlightThemed(bmp: Bitmap, rect: BoardDetector.BoardRect): Boolean {
        return try {
            var beigeCells = 0
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    // 格中心 4 点小窗底色采样（避开棋子）
                    var rSum = 0; var gSum = 0; var bSum = 0; var n = 0
                    for ((dx, dy) in arrayOf(0.20f to 0.20f, 0.78f to 0.20f, 0.20f to 0.78f, 0.78f to 0.78f)) {
                        val xx = (rect.left + rect.cell * (col + dx)).toInt().coerceIn(0, bmp.width - 2)
                        val yy = (rect.top + rect.cell * (row + dy)).toInt().coerceIn(0, bmp.height - 2)
                        val p = bmp.getPixel(xx, yy)
                        rSum += p shr 16 and 0xFF; gSum += p shr 8 and 0xFF; bSum += p and 0xFF; n++
                    }
                    val r = rSum / n; val g = gSum / n; val b = bSum / n
                    if (r in 204..232 && g in 200..228 && b in 174..202 && r > b) beigeCells++
                }
            }
            beigeCells in 1..4   // 少数米黄格=真高亮 → 开归一化；0=无高亮；12+=米褐主题
        } catch (_: Throwable) { false }
    }
    private fun normalizeHighlight(px: IntArray): IntArray {
        var hlCount = 0
        for (i in px.indices) {
            val r = px[i] shr 16 and 0xFF; val g = px[i] shr 8 and 0xFF; val b = px[i] and 0xFF
            // 高亮浅格色域 (218,214,188)±16 —— 与标准浅格 (183,194,219) 互不重叠
            val isHlLight = r in 202..234 && g in 198..230 && b in 172..204 && r > b
            // 高亮深格色域 (124,135,160)±16 —— 与标准深格 (155,161,173) 分离
            val isHlDark = r in 108..140 && g in 119..151 && b in 144..176 && b > r
            if (isHlLight || isHlDark) {
                hlCount++
                val nr: Int; val ng: Int; val nb: Int
                if (isHlLight) { nr = r - 35; ng = g + 20; nb = b + 31 } else { nr = r + 31; ng = g + 26; nb = b + 19 }
                px[i] = (px[i] and 0xFF000000.toInt()) or
                        (nr.coerceIn(0, 255) shl 16) or
                        (ng.coerceIn(0, 255) shl 8) or
                        nb.coerceIn(0, 255)
            }
        }
        return if (hlCount > px.size / 5) px else px   // 阈值以上才视为高亮格（统计门：误染保护）
    }

    fun classifyGrid(bmp: Bitmap, rect: BoardDetector.BoardRect): Pair<Array<Array<Char>>, Array<FloatArray>>? {
        val s = session ?: return null
        return try {
            val cell = rect.cell.toInt().coerceAtLeast(8)
            val sz = (cell * 0.78f).toInt().coerceAtLeast(12)   // 棋子中心区（0.72 训练 crop 同源）
            // v3.2.2 主题门：米褐主题（米黄格 12+）禁用归一化——v3.2.1 在米褐上
            // 把标准浅格当高亮染坏=识别劣化（c3.jpg 破案）
            val doNorm = normalizeHighlightThemed(bmp, rect)
            // 64 格 NCHW float
            val buf = FloatBuffer.allocate(64 * 3 * INP * INP)
            val tmp = Bitmap.createBitmap(INP, INP, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(tmp)
            val paint = android.graphics.Paint().apply { isFilterBitmap = true }
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val cx = rect.left + rect.cell * (col + 0.5f)
                    val cy = rect.top + rect.cell * (row + 0.5f)
                    val l = (cx - sz / 2).toInt().coerceAtLeast(0)
                    val t = (cy - sz / 2).toInt().coerceAtLeast(0)
                    val r = (cx + sz / 2).toInt().coerceAtMost(bmp.width)
                    val b = (cy + sz / 2).toInt().coerceAtMost(bmp.height)
                    if (r - l < 8 || b - t < 8) return null
                    val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
                    canvas.drawBitmap(crop, null, android.graphics.RectF(0f, 0f, INP.toFloat(), INP.toFloat()), paint)
                    crop.recycle()
                    // ARGB → RGB float NCHW（v1.10.2 根因修复：训练数据是 NCHW（通道平面），
                    // 旧代码按 HWC 逐像素交错填充却声明 NCHW shape → 通道错乱识别全废）
                    val px = IntArray(INP * INP)
                    tmp.getPixels(px, 0, INP, 0, 0, INP, INP)
                    if (doNorm) normalizeHighlight(px)   // v3.2.2：主题门通过才归一化（米褐主题禁用）
                    for (ch in 0 until 3) {
                        val shift = when (ch) { 0 -> 16; 1 -> 8; else -> 0 }   // R → G → B 平面序
                        for (p in px) buf.put(((p shr shift and 0xFF) / 255f))
                    }
                }
            }
            tmp.recycle()
            buf.rewind()
            val shape = longArrayOf(64, 3, INP.toLong(), INP.toLong())
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buf, shape).use { tensor ->
                val out = s.run(mapOf(s.inputNames.first() to tensor))
                try {
                    @Suppress("UNCHECKED_CAST")
                    val raw = out[0].value as? Array<FloatArray> ?: return null
                    val types = Array(8) { Array(8) { '.' } }
                    val confs = Array(8) { FloatArray(8) { 1f } }
                    for (i in 0 until 64) {
                        val logits = raw.getOrNull(i) ?: continue
                        var best = 0
                        var bestV = logits.getOrNull(0) ?: -1e9f
                        for (k in 1 until 13) {
                            val v = logits.getOrNull(k) ?: -1e9f
                            if (v > bestV) { bestV = v; best = k }
                        }
                        types[i / 8][i % 8] = CLS.getOrNull(best) ?: '.'
                        // softmax 置信（数值稳定：减 max）
                        val m = bestV
                        var sum = 0f
                        val e = FloatArray(13)
                        for (k in 0 until 13) {
                            val v = (logits.getOrNull(k) ?: -1e9f) - m
                            e[k] = kotlin.math.exp(v)
                            sum += e[k]
                        }
                        confs[i / 8][i % 8] = if (sum > 0f) e[best] / sum else 0f
                    }
                    return types to confs
                } finally {
                    out.close()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
