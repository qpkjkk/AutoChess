package com.rogic.autochess.autoplay

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.tensorflow.lite.Interpreter as TflInterpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 棋子检测器：TFLite(F16) 主引擎 + ONNX 兜底。
 * 模型：NAKSTStudio yolov8m-chess-piece-detection，13 类：
 * 0=board 1-6=白KQRBNP 7-12=黑KQRBNP
 * TFLite 输入 [1,640,640,3] NHWC float32；输出 [1,17,8400]（box 归一化 + 13 分数）
 * ONNX 输入 [1,3,640,640] NCHW；输出同布局
 */
class YoloChessDetector private constructor() {

    data class Detection(
        val labelIdx: Int,
        val score: Float,
        /** 原图像素坐标 */
        val cx: Float, val cy: Float,
        val w: Float, val h: Float
    )

    companion object {
        const val NUM_CHAN = 17
        const val CONF_TH = 0.30f
        const val NMS_IOU = 0.50f
        /** 输入边长与锚点数：从实际加载的模型动态读取（416/640、onnx/tflite 通用） */
        @Volatile var inputSize = 640
            private set
        @Volatile var numAnchors = 8400
            private set
        /** assets 内模型字节数（完整性校验用，320 输入 opset21 归一化版） */
        const val MODEL_BYTES = 103_527_574L
        /** assets 内模型 SHA-256（云端 E2E 验证通过：三皮肤 + 真实截图两阶段 A/B 一致 + 升变模拟） */
        const val MODEL_SHA256 = "2d5fb17a556b5d6fc501f80f10874d22f52b61ea6d2ad8d2cca0d13a909c2a38"
        private const val MODEL_ASSET = "models/chess_yolov8m.onnx"
        private const val TFLITE_ASSET = "models/chess_f16.tflite"
        /** assets 内 tflite 模型字节数（filesDir 拷贝时完整性校验） */
        const val TFLITE_BYTES = 51_884_986L

        /** 类别索引 → FEN 字符（'.'=board 类不作棋子） */
        val LABEL_TO_FEN = charArrayOf(
            '.',   // 0 board
            'K', 'Q', 'R', 'B', 'N', 'P',   // 1-6 白
            'k', 'q', 'r', 'b', 'n', 'p'    // 7-12 黑
        )

        @Volatile private var instance: YoloChessDetector? = null
        private var loading = false

        /** 单例获取；首次调用触发异步加载，加载完成前 detect 返回 null */
        fun get(): YoloChessDetector? = instance

        /** onReady(ok, errSummary)：失败时 errSummary 为异常摘要（类名:首行消息），直接上屏用 */
        fun ensureLoaded(context: Context, onReady: ((Boolean, String?) -> Unit)? = null) {
            if (instance != null) { onReady?.invoke(true, null); return }
            synchronized(this) {
                if (instance != null || loading) return
                loading = true
                Thread {
                    val d = YoloChessDetector()
                    var ok = false
                    var err: Throwable? = null
                    try {
                        ok = d.init(context.applicationContext)
                    } catch (t: Throwable) {
                        err = t
                    }
                    if (!ok && err == null) err = RuntimeException("init returned false")
                    var summary: String? = null
                    if (err != null) {
                        summary = "${err!!.javaClass.simpleName}: ${err.message?.take(90) ?: "未知"}"
                        // 加载失败原因落盘，便于远程定位
                        try {
                            val dir = File(context.applicationContext.getExternalFilesDir(null), "crash")
                            if (!dir.exists()) dir.mkdirs()
                            File(dir, "yolo_error.txt").writeText(
                                "time=${System.currentTimeMillis()}\n${err!!.stackTraceToString()}")
                        } catch (_: Throwable) {}
                    }
                    loading = false
                    if (ok) instance = d
                    onReady?.invoke(ok, summary)
                }.start()
            }
        }
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    /** TFLite F16 引擎（主）与复用缓冲 */
    private var tflite: TflInterpreter? = null
    private var tfliteInput: ByteBuffer? = null
    private var tfliteOutput: Array<Array<FloatArray>>? = null
    /** 最近一次推理耗时（性能自诊，上屏用） */
    @Volatile var lastDetectMs: Int = 0
    /** 当前引擎名（诊断） */
    @Volatile var engineName: String = "onnx"
    /** XNNPACK 是否生效（诊断：引擎名 xp 后缀） */
    @Volatile var xnnpackOn: Boolean = false

    private fun init(context: Context): Boolean {
        // ── 阶段1（主路径）：TFLite F16 引擎——assets mmap 零拷贝，加载即完成 ──
        // F16 权重内存减半 + XNNPACK 优化，ARM 手机上显著快于 onnx fp32
        var tfliteErr: Throwable? = null
        try {
            val mapped: MappedByteBuffer = try {
                val afd = context.assets.openFd(TFLITE_ASSET)
                afd.createInputStream().channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            } catch (_: Throwable) {
                // 资产被压缩/无法 mmap → 拷贝到 filesDir 再映射（一次性）
                val f = File(context.filesDir, "models/chess_f16.tflite")
                if (!f.exists() || f.length() != TFLITE_BYTES) {
                    f.parentFile?.mkdirs()
                    context.assets.open(TFLITE_ASSET).use { ins ->
                        f.outputStream().use { ins.copyTo(it, 1 shl 16) }
                    }
                }
                java.io.FileInputStream(f).channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length())
            }
            val opts = TflInterpreter.Options().apply { setNumThreads(4) }
            val itp = TflInterpreter(mapped, opts)
            itp.allocateTensors()
            // 从模型张量读实际输入尺寸/锚点数（416/640 通用）
            try {
                val inShape = itp.getInputTensor(0).shape()
                if (inShape.size == 4 && inShape[1] > 100) inputSize = inShape[1]
                val outShape = itp.getOutputTensor(0).shape()
                if (outShape.size == 3 && outShape[2] > 100) numAnchors = outShape[2]
            } catch (_: Throwable) {}
            // 预分配复用缓冲（NHWC float32）
            tfliteInput = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder())
            tfliteOutput = Array(1) { Array(NUM_CHAN) { FloatArray(numAnchors) } }
            tflite = itp
            engineName = "tflite-f16"
        } catch (t: Throwable) {
            tfliteErr = t
            tflite = null
        }

        // ── 阶段2（兜底）：ONNX fp32 引擎（仅当 assets 含 onnx 时可用）──
        if (tflite == null) {
            val out = File(context.filesDir, "models/chess_yolov8m.onnx")
            val hasOnnxAsset = try {
                context.assets.open(MODEL_ASSET).use { true }
            } catch (_: Throwable) { false }
            if (!hasOnnxAsset) {
                throw RuntimeException(
                    "TFLite加载失败: ${tfliteErr?.javaClass?.simpleName}: ${tfliteErr?.message?.take(60)}")
            }
            out.parentFile?.mkdirs()
            var fileOk = false
            var copyErr = "未知"
            for (attempt in 1..3) {
                if (verifySha256(out)) { fileOk = true; break }
                try {
                    if (out.exists()) out.delete()
                    val tmp = File(out.parentFile, "chess.tmp")
                    if (tmp.exists()) tmp.delete()
                    context.assets.open(MODEL_ASSET).use { input ->
                        tmp.outputStream().use { o -> input.copyTo(o, 1 shl 16) }
                    }
                    if (verifySha256(tmp)) {
                        if (tmp.renameTo(out) || run {
                                tmp.copyTo(out, overwrite = true)
                                tmp.delete()
                                out.exists()
                            }) {
                            fileOk = true; break
                        }
                        copyErr = "rename失败"
                    } else {
                        copyErr = "SHA-256校验不符(第${attempt}次)"
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    copyErr = "拷贝异常 ${t.javaClass.simpleName}"
                }
            }
            if (!fileOk) throw RuntimeException("模型文件就绪失败: $copyErr")
            env = OrtEnvironment.getEnvironment()
            // XNNPACK 优先（小模型 CPU 推理 1.5-2x），失败回退普通 CPU 会话
            var xp = false
            session = tryCreateSession(out.absolutePath, xnnpack = true)?.also { xp = true }
                ?: tryCreateSessionFromAssets(context, xnnpack = true)?.also { xp = true }
                ?: tryCreateSession(out.absolutePath, xnnpack = false)
                ?: tryCreateSessionFromAssets(context, xnnpack = false)
                ?: throw RuntimeException("会话创建失败")
            xnnpackOn = xp
            // 从 ONNX 图读实际输入尺寸/锚点数（416/640 通用）
            try {
                val inInfo = session!!.inputInfo.values.firstOrNull()
                val inShape = (inInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
                if (inShape != null && inShape.size == 4 && inShape[2] > 100) inputSize = inShape[2].toInt()
                val outInfo = session!!.outputInfo.values.firstOrNull()
                val outShape = (outInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
                if (outShape != null && outShape.size == 3 && outShape[2] > 100) numAnchors = outShape[2].toInt()
            } catch (_: Throwable) {}
            engineName = "onnx-$inputSize${if (xnnpackOn) "xp" else ""}"
        }

        // 预热首帧推理（触发图优化/内存分配，避免首次真实识别卡顿）
        try {
            val warm = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val t0 = android.os.SystemClock.elapsedRealtime()
            detect(warm)
            warm.recycle()
            lastDetectMs = (android.os.SystemClock.elapsedRealtime() - t0).toInt()
        } catch (_: Throwable) {}
        return true
    }

    private fun tryCreateSession(path: String, xnnpack: Boolean): OrtSession? {
        return try {
            val opts = OrtSession.SessionOptions().apply {
                if (xnnpack) {
                    // XNNPACK EP：线程数经由配置传入
                    addXnnpack(mapOf("intra_op_num_threads" to "4"))
                } else {
                    setIntraOpNumThreads(4)
                }
            }
            env!!.createSession(path, opts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryCreateSessionFromAssets(context: Context, xnnpack: Boolean): OrtSession? {
        return try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                if (xnnpack) {
                    addXnnpack(mapOf("intra_op_num_threads" to "4"))
                } else {
                    setIntraOpNumThreads(4)
                }
            }
            env!!.createSession(bytes, opts)
        } catch (_: Throwable) {
            null
        }
    }

    /** SHA-256 完整性校验（字节数 + 内容哈希双重） */
    private fun verifySha256(f: File): Boolean {
        if (!f.exists() || f.length() != MODEL_BYTES) return false
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val sb = StringBuilder()
            for (b in md.digest()) sb.append(String.format("%02x", b))
            sb.toString() == MODEL_SHA256
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 检测：返回原图坐标系的检测列表（棋盘+全部棋子）。
     * TFLite 主引擎（NHWC），ONNX 兜底（NCHW）；后处理完全一致（box 均为归一化×640）。
     */
    fun detect(bitmap: Bitmap, confTh: Float = CONF_TH): List<Detection>? {
        val itp = tflite
        if (itp != null) return detectTflite(itp, bitmap, confTh)
        val s = session ?: return null
        return detectOnnx(s, bitmap, confTh)
    }

    private fun detectTflite(itp: TflInterpreter, bitmap: Bitmap, confTh: Float): List<Detection>? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val srcW = bitmap.width; val srcH = bitmap.height
        if (srcW < 8 || srcH < 8) return null
        val buf = tfliteInput ?: return null
        val inp = inputSize
        val scale = inp.toFloat() / max(srcW, srcH)
        val lw = (srcW * scale).toInt().coerceAtLeast(1)
        val lh = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (inp - lw) / 2; val padY = (inp - lh) / 2
        val inputBmp = Bitmap.createScaledBitmap(bitmap, lw, lh, true)
        val pixels = IntArray(lw * lh)
        inputBmp.getPixels(pixels, 0, lw, 0, 0, lw, lh)
        if (inputBmp !== bitmap) inputBmp.recycle()

        // NHWC float [0,1]：letterbox 逐像素写入复用 ByteBuffer
        // （注意：行内左右补边只补 padX 个像素×3 通道，整行补零才用 inp×3——
        //   v1.4.4 曾把整行零数组用在行内补边上，竖屏 padX 很大时缓冲区溢出，每帧必炸）
        val zeroPixel = floatArrayOf(0f, 0f, 0f)
        val fb = buf.asFloatBuffer()
        fb.position(0)
        for (y in 0 until inp) {
            if (y < padY || y >= padY + lh) {
                repeat(inp) { fb.put(zeroPixel) }          // 整行空白
            } else {
                val row = y - padY
                repeat(padX) { fb.put(zeroPixel) }           // 行首补边
                for (x in 0 until lw) {
                    val p = pixels[row * lw + x]
                    fb.put((p shr 16 and 0xFF) / 255f)
                    fb.put((p shr 8 and 0xFF) / 255f)
                    fb.put((p and 0xFF) / 255f)
                }
                repeat(inp - padX - lw) { fb.put(zeroPixel) }  // 行尾补边
            }
        }
        val out = tfliteOutput ?: return null
        itp.run(buf, out)
        val res = parseAndNms(out[0], confTh, scale, padX, padY, srcW, srcH)
        lastDetectMs = (android.os.SystemClock.elapsedRealtime() - t0).toInt()
        return res
    }

    private fun detectOnnx(s: OrtSession, bitmap: Bitmap, confTh: Float): List<Detection>? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val srcW = bitmap.width; val srcH = bitmap.height
        if (srcW < 8 || srcH < 8) return null

        // letterbox 到 inputSize×inputSize
        val inp = inputSize
        val scale = inp.toFloat() / max(srcW, srcH)
        val lw = (srcW * scale).toInt().coerceAtLeast(1)
        val lh = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (inp - lw) / 2; val padY = (inp - lh) / 2
        val inputBmp = Bitmap.createScaledBitmap(bitmap, lw, lh, true)
        val pixels = IntArray(lw * lh)
        inputBmp.getPixels(pixels, 0, lw, 0, 0, lw, lh)
        if (inputBmp !== bitmap) inputBmp.recycle()

        // HWC RGBA → CHW float RGB [0,1]
        val n = inp * inp
        val chR = FloatArray(n); val chG = FloatArray(n); val chB = FloatArray(n)
        for (y in 0 until lh) for (x in 0 until lw) {
            val p = pixels[y * lw + x]
            val di = (y + padY) * inp + (x + padX)
            chR[di] = (p shr 16 and 0xFF) / 255f
            chG[di] = (p shr 8 and 0xFF) / 255f
            chB[di] = (p and 0xFF) / 255f
        }
        val flat = FloatArray(3 * n)
        System.arraycopy(chR, 0, flat, 0, n)
        System.arraycopy(chG, 0, flat, n, n)
        System.arraycopy(chB, 0, flat, 2 * n, n)

        val shape = longArrayOf(1, 3, inp.toLong(), inp.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { tensor ->
            val out = s.run(mapOf("images" to tensor))
            out.use {
                @Suppress("UNCHECKED_CAST")
                val raw = (it[0].value as Array<Array<FloatArray>>)[0] // [17][anchors]
                val res = parseAndNms(raw, confTh, scale, padX, padY, srcW, srcH)
                lastDetectMs = (android.os.SystemClock.elapsedRealtime() - t0).toInt()
                return res
            }
        }
    }

    /** YOLOv8 输出解析 + NMS */
    private fun parseAndNms(
        raw: Array<FloatArray>, confTh: Float,
        scale: Float, padX: Int, padY: Int, srcW: Int, srcH: Int
    ): List<Detection> {
        val candidates = ArrayList<Detection>()
        val inp = inputSize
        val anchors = raw[0].size
        for (a in 0 until anchors) {
            var bestCls = -1; var bestScore = 0f
            for (c in 4 until NUM_CHAN) {
                val sc = raw[c][a]
                if (sc > bestScore) { bestScore = sc; bestCls = c - 4 }
            }
            if (bestCls < 0 || bestScore < confTh) continue
            // 关键：该模型输出的 box 是 0~1 归一化坐标（云端实测验证），必须乘输入尺寸
            val cx640 = raw[0][a] * inp
            val cy640 = raw[1][a] * inp
            val w640 = raw[2][a] * inp
            val h640 = raw[3][a] * inp
            // letterbox 逆变换 → 原图坐标
            val cx = (cx640 - padX) / scale
            val cy = (cy640 - padY) / scale
            val w = w640 / scale; val h = h640 / scale
            if (w < 2 || h < 2 || w > srcW * 1.2f || h > srcH * 1.2f) continue
            candidates.add(Detection(bestCls, bestScore, cx, cy, w, h))
        }
        // NMS（按分数降序，跨类一起做——同一目标多类重复框互斥）
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (cand in candidates) {
            var overlapped = false
            for (k in kept) {
                if (iou(cand, k) > NMS_IOU) { overlapped = true; break }
            }
            if (!overlapped) kept.add(cand)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2
        val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2
        val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val iw = ix2 - ix1; val ih = iy2 - iy1
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        val union = a.w * a.h + b.w * b.h - inter
        return if (union <= 0) 0f else inter / union
    }

    fun close() {
        try { tflite?.close() } catch (_: Throwable) {}
        tflite = null
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }
}
