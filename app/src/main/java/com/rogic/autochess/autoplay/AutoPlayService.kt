package com.rogic.autochess.autoplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.rogic.autochess.MainActivity
import com.rogic.autochess.R
import com.rogic.autochess.chess.PieceAssets
import com.rogic.autochess.engine.StockfishEngine
import kotlin.math.abs

/**
 * 自动连线服务：录屏识别 + Stockfish 决策 + 无障碍模拟点击。
 *
 * 状态机：
 * IDLE → CONNECTING(定位+识别) → RUNNING{ WATCH(等对方) / THINK(引擎思考) / PLAY(模拟落子) } → IDLE
 * 支持自动续盘：每帧全量识别 → 与本地 Board 校验同步。
 */
class AutoPlayService : Service(), StockfishEngine.Listener {

    enum class State { IDLE, CONNECTING, WATCH, THINK, PLAY, NEW_GAME_SYNC, PAUSED_VERIFY_LOST }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private var screenHeight = Resources.getSystem().displayMetrics.heightPixels
    /**
     * 真实物理屏幕尺寸（无障碍点击坐标系 = 全屏含状态栏/导航栏）。
     * 注意：displayMetrics.heightPixels 在三键导航机型上比物理屏少一个导航栏高度，
     * 用它换算点击坐标会导致棋盘位置整体偏移约一格（必须用 getRealMetrics）。
     */
    private var realScreenWidth = 0
    private var realScreenHeight = 0
    /** 最近一次捕获帧的实际尺寸（按帧精确换算，杜绝任何来源的尺寸误差） */
    private var lastCapW = 0
    private var lastCapH = 0

    private fun ensureRealSize() {
        if (realScreenWidth > 0) return
        var w = Resources.getSystem().displayMetrics.widthPixels
        var h = Resources.getSystem().displayMetrics.heightPixels
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                w = dm.widthPixels; h = dm.heightPixels
            }
        } catch (_: Throwable) {}
        realScreenWidth = w; realScreenHeight = h
    }

    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var miniView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workThread: HandlerThread
    private lateinit var workHandler: Handler

    private var engine: StockfishEngine? = null
    private var state: State = State.IDLE
        set(v) { field = v; renderState() }

    // 连线运行时
    private var running = false
    private var boardRect: BoardDetector.BoardRect? = null
    private var extBoard = Board()          // 外部对局真实局面
    private var mySide: Side = Side.WHITE
    private var sideAuto = true
    /** 手动校准 */
    private var calibrating = false
    private var calibOverlay: android.view.View? = null
    /** 上一帧识别矩阵签名（帧间一致性投票） */
    private var lastSig: String? = null
    /** 局面接受状态（2帧确认重置 + 8帧兜底） */
    private var resetCandidateFen: String? = null
    /** v3.4.3 识别质量门：连续烂帧计数（烂=与本地盘子数差>3） */
    private var badQualityCount = 0
    private var resetCandidateCount = 0
    /** 与本地局面持续对不上的累计帧数（兜底重置触发器） */
    private var resetMismatchCount = 0
    /** PLAY 状态看门狗：落子流程意外卡死时强制恢复（防"永久监视"死锁） */
    private var playSince = 0L
    // ── 鹏飞式闭环落子验证 ──
    /** 待验证的落子（非空=已点击、等屏幕确认） */
    private var verifyUci: String? = null
    /** 我方最近一次已发送的着法（uci，鹏飞 _lastSentMove 思想）：
     *  物理事实记录——send 唯一写入口 performMoveInternal；清空仅发生于重置/新对局。
     *  用途：等对方时若非空 → 铁证"我刚走完，轮对方"，任何超时/探测都不许翻轮出手。 */
    private var lastSentMove: String? = null
    private var verifyPrePlacement: String? = null
    private var verifyDeadline = 0L
    /** 当前这步棋的点击尝试次数 */
    private var tapAttempts = 0
    /** 最近一次实际派发点击的时刻（判边校准的绝对禁区：点过屏幕=那步是我们的） */
    private var lastTapAt = 0L
    /** 本局我方已屏幕确认的落子数（判边翻转的一次性保护） */
    private var myMovesVerified = 0
    /** 等待对方行棋起始时刻（轮次卡死探测用） */
    private var waitSince = 0L
    /** 极速模式（pace=turbo）：轮询 40/80ms、点击间隔缩短 */
    private var paceTurbo = false

    // ── 视角锁（v1.7.1：whiteAtBottom 启发式在中局白子过河后翻转 → 摆位镜像 → 死锁）──
    /** 局内视角锁：null=未锁定（等开局形态或连续投票） */
    private var orientLock: Boolean? = null

    private fun activeDelay(): Long = if (paceTurbo) ACTIVE_TURBO_MS else ACTIVE_MS
    private fun idleDelay(): Long = if (paceTurbo) IDLE_TURBO_MS else IDLE_MS
    // ── 升变弹框视觉选择（v1.5.1：不再盲点目标格，用模型找弹框里的皇后）──
    private enum class PromoPhase { OFF, PICKING }
    private var promoPhase = PromoPhase.OFF
    private var promoDeadline = 0L
    private var promoGraceUntil = 0L
    private var promoTaps = 0
    private var lastPromoTapAt = 0L
    /** 升变目标格的真实屏幕坐标（performMove 时缓存，兜底点击用） */
    private var promoToScreen: Pair<Float, Float>? = null

    /** 帧缓存：画面哈希（未变则跳过识别） */
    private var lastFrameHash: Long? = null
    /** 上次接受帧的棋子数（走子动画帧过滤基准） */
    private var lastAcceptedPieces = 0
    // ── v1.13.0 变化格驱动状态机（鹏飞 _lastUsedBoardImage + NotByPassByChangedPositions 字段级复刻）──
    /** 上次【接受】的屏幕摆位（棋盘事实版，噪声不吸收）。所有同步判据以此为锚：
     *  屏幕新帧与它的变化格数 D：
     *   · D==0 → 静止（verify 挂起管理/轮次等待）
     *   · D 恰被一步/两步合法棋解释 → 接受该步（吸收棋盘事实，覆盖识别噪声）
     *   · D==1 格噪声 → 丢弃帧（绝不进状态机）
     *   · D 大（动画/换局/乱帧）→ 先等待（鹏飞 _delayTImesForTooManyChanged），持续才走重置
     *  锚点随 extBoard 严格同步更新（doMove/重置/追平后=走后摆位）。 */
    private var lastAcceptedSig: String = ""
    // ── v1.13.3 幽灵步双帧确认 ──
    /** v3.4.19 等黑兜底: 同一步连续命中计数（≥3=强制应用!!） */
    private var pendingStableMove: String? = null
    private var pendingStableCount = 0
    /** 待复现确认的步观察（首帧暂存，第二帧同 obs/同 after 才应用） */
    private var pendingStepConfirmObs: String? = null
    private var pendingStepConfirmAfter: String? = null
    // ── v1.14.0 幽灵步物理防线（用户 9/11 22:38+补充：大小模型都幽灵步）──
    // 画面帧哈希==锚定哈希 = 棋盘【物理静止】——此帧识别出的任何"步"都是幻觉
    // （识别噪声在静止画面上凭空造步 → 轮次翻转 → 程序抢走 = 双走根因）。
    // 物理铁律优先于一切识别结果：画面没动，棋子不可能动。
    private var anchorHash: Long? = null
    private var curFrameHash: Long? = null

    // ═══ v2.0 AC3.0 三元组状态机（用户 9/13 16:15 竞品破译移植）═══
    // 比较基准在点击瞬间写死、永不漂移——屏幕只与本帧和两个确定性基准比较，
    // 结果唯一：点击生效/未生效/我方步/对方步/非法。无锚点无仲裁无双帧。
    private var clickBeforeSig = ""      // 我方点击前的屏幕摆位快照
    private var clickAfterSig = ""       // 点击后预期摆位（提前算好）
    private var clickAfterUci = ""       // 对应的走法
    private var verifyLostCount = 0        // v3.3.0 AC3.0 actionNullCount 等价：verify 连续失联帧计数
    private var oppWaitSig = ""          // 等对方前的屏幕摆位快照
    private var badFrames = 0            // 连续非法帧计数（≥5 重置）
    private var syncDiag = ""            // v2.2.6 帧级诊断（上屏破案用）
    private var pendingUserSig = ""      // v2.2.6 PLAY 分支2 动画帧防线（双帧确认）
    private var pausedPendingResume = false  // v3.0.0 AC3.0 toggleStart 暂停旗（分支3/7）
    private var identLastSig = ""        // NEW_GAME_SYNC 稳定识别
    private var identFrames = 0
    // ── v1.13.7 哈希等价双帧确认（大模型省二次全识别）──
    /** 首帧完整 FEN（哈希不变时直接喂 syncBoard 完成复现确认，免 441ms 重识别） */
    private var pendingStepConfirmFen: String? = null
    private var recWhiteAtBottom = true
    private var lastRecPieceCount = 0
    /** 极速小模型模式（v1.10.0）：true=TinyClassifier 逐格分类（用户皮肤专用） */
    @Volatile private var useTinyModel = true
    /** CPU 唤醒锁（v1.12.0 根本稳定支柱①：杜绝 Doze/MIUI 冻结导致的引擎僵死） */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    // ── 鹏飞 8.3 新局同步容错字段（v1.12.0 复刻）──
    // ── v1.13.2 NewGameSync 状态（鹏飞七态的根除实现）──
    /** 鹏飞 TryNewGame 后不是立即 Playing，而是 NewGameSync（报告 8.2 转移图）：
     *  「TryNewGame: 基准图+fen 对齐一致 → Playing(轮询)」。
     *  新局重置（bigJump/情况D）→ 进 NEW_GAME_SYNC：只对齐观察（连续 ngSyncNeed 帧
     *  同摆位+局面合法）→ 回 WATCH 正常循环（轮我自然出手，~160ms 级非延时）。
     *  旧版"重置→轮我→立即出手"在识别未稳时乱走 = 用户 9/8 实测双走根因；
     *  对齐是状态驱动（连续帧一致），比 1.5s 延时快 10 倍且正确。 */
    private var ngSyncCandidateFen: String? = null
    private var ngSyncCount = 0
    private val ngSyncNeed = 2   // 连续 2 帧一致（鹏飞"基准图+fen 对齐一致"语义）
    /** 当前异常类型（0=一步可达 1=全空 2=非一步差）——分类记忆，换类清零 */
    private var lastAnomalyKind = 0
    /** 当前异常类型首现时刻（_lastBegin*Time 语义：同类持续才判新局） */
    private var firstAnomalyAt = 0L
    /** 新局就绪（IsExactlyMovedOneStepFromStartBoard 首步强校验开关） */
    private var newGameArmed = false
    /** 哈希连续命中次数（防漏检保险：命中超过 2 次强制识别——
     *  采样哈希存在盲区，对方走子可能不改变哈希 → 永久跳过识别 = 死锁） */
    private var hashSkipCount = 0
    /** v3.1.5 推算失败连续帧计数（≥3 自动重定位棋盘 rect） */
    private var inferFailCount = 0
    /** v3.1.6 ngSync 中盘保护拒收计数（≥3 触发 rect 重定位） */
    private var ngRejectCount = 0
    private var ngRejectTotal = 0   // v3.4.22 拒收超时计（Minis死锁逃逸!!）
    private var ngEscapeMyTurn = false   // v3.4.23 逃逸断言轮我（自锁终解!!）
    private var ngBadQuality = 0   // v3.4.24 刀5: NGS质量僵局计数（→回监视!!）
    private var resetStandoff = 0       // v3.4.24 刀6: 情况D质量对峙周期计数
    private var pausedSince = 0L        // v3.4.25: PAUSED自愈计时
    @Volatile private var calibInvalidated = false   // v3.4.24 刀6: 会话级校准框弃用旗

    /** 24×24 亮度均值哈希：画面没变返回相同值（廉价帧缓存） */
    private fun quickFrameHash(bmp: Bitmap): Long? {
        return try {
            var h = 1125899906842597L
            val stepX = bmp.width / 24
            val stepY = bmp.height / 24
            if (stepX <= 0 || stepY <= 0) return null
            for (y in 0 until 24) for (x in 0 until 24) {
                val p = bmp.getPixel(x * stepX + stepX / 2, y * stepY + stepY / 2)
                val l = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                h = 31 * h + l
            }
            h
        } catch (_: Throwable) {
            null
        }
    }
    private var depthSetting = 12
    /** 当前步时限（毫秒，THINK 看门狗基准；requestBestMove 前更新） */
    @Volatile private var cfgMoveTimeMs = 700L
    private var lastRec: BoardDetector.Recognition? = null
    private var templates: Map<String, Bitmap>? = null
    private var pollGeneration = 0
    private var thinkingToken = 0
    private var pendingPromo: Pair<String, String>? = null  // fromUci → toUci 待升变补点

    // 悬浮窗控件
    private var tvStatus: TextView? = null
    private var tvEval: TextView? = null
    private var tvAudit: TextView? = null   // v3.4.9 出手审计行（持久，拍照取证用）
    @Volatile private var pendingMoveSrc: String = "?"   // 本次出手的来源标记
    private val shotLog = StringBuilder()   // v3.4.9 出手/拦截/书候选日志（同步锁）
    private var tvPv: TextView? = null
    private var tvDepthReached: TextView? = null
    private var statusDot: View? = null
    private var btnConnect: View? = null   // 经典面板=Button、横条=ImageButton，统一 View（text 更新处安全转换）
    private var btnFloatThinkRef: Button? = null
    /** 「记」按钮引用（记录模式高亮反馈） */
    private var btnFloatRecordRef: Button? = null
    private var tickErrors = 0
    private var rgSide: RadioGroup? = null

    // ─────────────── 生命周期 ───────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // ── 根本稳定支柱①（v1.12.0，对齐鹏飞）：PARTIAL WakeLock 全程持有 ──
        // MIUI/Android Doze 在熄屏/后台时冻结进程组（引擎子进程管道 IO 挂起=
        // 假死掉线）。前台服务只保进程不被杀，不保 CPU 调度——必须唤醒锁。
        // 鹏飞引擎"从不出问题"的地基就是这个（外加 isready 探活）。
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "autochess:engine-keepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}
        try {
            ensureRealSize()
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            workThread = HandlerThread("autoplay").apply { start() }
            workHandler = Handler(workThread.looper)
            createChannel()
            val noti = buildNotification("待命中")
            try {
                // 显式声明 mediaProjection 类型（targetSdk 34+ 的标准写法，避免 OEM 对 2 参版本的类型校验问题）
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        NOTI_ID, noti,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTI_ID, noti)
                }
            } catch (t: Throwable) {
                // 兜底：无类型版本（部分 ROM 校验更宽松）
                try {
                    startForeground(NOTI_ID, noti)
                } catch (t2: Throwable) {
                    try {
                        android.widget.Toast.makeText(this, "前台服务启动失败：${t2.message}", android.widget.Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {}
                    stopSelf()
                    return
                }
            }
            showFloatingPanel()
            // 我的库加载（v1.10.0：一个库——用户自录谱，闪击响应）
            MyBook.ensureLoaded(this)
            engine = StockfishEngine(id = "auto", listener = this)
            // 引擎在工作线程启动：NNUE 权重拷贝（冷启动约百 MB）不能阻塞服务主线程
            workHandler.post {
                val e = engine
                if (e != null && !e.isRunning()) {
                    if (!e.start(this)) engine = null
                }
            }
            loadTemplates()
            // 开局书异步加载（2.8MB，开局阶段查书秒出步）
            OpeningBook.ensureLoaded(this) { ok ->
                if (ok) mainHandler.post {
                    try {
                        tvStatus?.text = tvStatus?.text.toString() + " · 开局库就绪"
                    } catch (_: Throwable) {}
                }
            }
            // YOLO 棋子检测模型异步加载（assets→filesDir 拷贝约1s + 会话初始化）
            YoloChessDetector.ensureLoaded(this) { ok, err ->
                mainHandler.post {
                    tvStatus?.text = when {
                        ok -> "AI 识别引擎就绪 · 点「连线」"
                        else -> "AI 模型加载失败: $err"
                    }
                }
            }
        } catch (t: Throwable) {
            // 硬保护：服务初始化任何异常都不拖垮主进程（同进程部署下服务崩溃=整个App闪退）
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 设置里切换悬浮窗样式 → 面板即时重建（无需断开重连）
                ACTION_REFRESH_PANEL -> {
                    if (panelView != null || miniView != null) {
                        mainHandler.post {
                            try { removePanel() } catch (_: Throwable) {}
                            try { showFloatingPanel() } catch (_: Throwable) {}
                        }
                    }
                    return START_NOT_STICKY
                }
            }
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data: android.content.Intent? = if (intent != null && intent.hasExtra(EXTRA_RESULT_DATA)) {
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            } else null
            if (code != 0 && data != null) {
                setupProjection(code, data)
            }
        } catch (t: Throwable) {
            try { stopSelf() } catch (_: Throwable) {}
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        pollGeneration++
        try { MyBook.flush() } catch (_: Throwable) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        try { engine?.quit() } catch (_: Throwable) {}
        engine = null
        try { projection?.stop() } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { removePanel() } catch (_: Throwable) {}
        try { workThread.quitSafely() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ─────────────── 录屏 ───────────────

    private fun setupProjection(code: Int, data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, mainHandler)
            // 录屏分辨率减半（基于真实物理屏，保证与点击坐标系 2:1 精确对应）
            val w = (realScreenWidth / 2).coerceAtLeast(360)
            val h = (realScreenHeight / 2).coerceAtLeast(640)
            screenWidth = w; screenHeight = h
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "autochess", w, h, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            updateNoti("已就绪 · 点击悬浮窗「连线」")
            // 录屏自检：首帧常有延迟，验证 3 次，把结果写到状态栏
            workHandler.postDelayed({
                var probe: Bitmap? = null
                var black = true
                try {
                    repeat(3) {
                        probe = capture()
                        if (probe != null) {
                            val p = probe!!
                            // 中心 16 点采样判断是否全黑帧
                            var lumSum = 0
                            for (i in 0 until 16) {
                                val px = p.getPixel(p.width / 2 + (i % 4 - 1) * p.width / 8,
                                    p.height / 2 + (i / 4 - 1) * p.height / 8)
                                lumSum += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                            }
                            black = lumSum < 16 * 8
                            if (!black) return@repeat
                        }
                        Thread.sleep(400)
                    }
                } catch (_: Throwable) {
                } finally {
                    probe?.recycle()
                }
                val msg = when {
                    imageReader == null -> "录屏失败：无帧源"
                    black -> "录屏自检：画面全黑（${screenWidth}×${screenHeight}），请重新授权连线"
                    else -> "录屏自检 OK（${screenWidth}×${screenHeight}）"
                }
                mainHandler.post { tvStatus?.text = msg }
            }, 600)
        } catch (e: Exception) {
            updateNoti("录屏初始化失败：${e.message}")
            stopSelf()
        }
    }

    /** 抓取当前屏幕帧 */
    private fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = try {
            reader.acquireLatestImage() ?: return null
        } catch (_: Exception) {
            return null
        }
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width; val h = image.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val buf = plane.buffer
            val pixelBytes = ByteArray(buf.remaining())
            buf.get(pixelBytes)
            if (rowStride == w * pixelStride) {
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixelBytes))
            } else {
                val rowBytes = w * pixelStride
                val pixBuf = java.nio.ByteBuffer.allocate(rowBytes * h)
                var pos = 0
                for (r in 0 until h) {
                    pixBuf.put(pixelBytes, pos, rowBytes)
                    pos += rowStride
                }
                pixBuf.rewind()
                bmp.copyPixelsFromBuffer(pixBuf)
            }
            lastCapW = w; lastCapH = h
            bmp
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    // ─────────────── 模板加载 ───────────────

    private fun loadTemplates() {
        workHandler.post {
            val map = HashMap<String, Bitmap>()
            val size = 40
            for (key in PieceAssets.pieceKeys) {
                PieceAssets.get(key)?.let { big ->
                    map[key] = Bitmap.createScaledBitmap(big, size, size, true)
                }
            }
            templates = map
        }
    }

    // ─────────────── 连线状态机 ───────────────

    fun toggleConnect() {
        if (running) {
            running = false
            pollGeneration++
            engine?.stop()
            state = State.IDLE
            resetSessionState()
            // v1.12.0（用户拍板）：断开 = 完全隔离的全新对局——本地棋盘清零（重连后
            // 首帧以屏幕为准重建，绝无旧局缓存/旧轮次/旧执边残留）
            try { extBoard = Board() } catch (_: Throwable) {}
            if (sideAuto) mySide = Side.WHITE
            updateNoti("已断开连线")
            mainHandler.post { tvStatus?.text = "已断开 · 待命" }
        } else {
            if (imageReader == null) {
                tvStatus?.text = "录屏未授权，请重新从主界面开启连线"
                return
            }
            // 连线开启时强制停止思考模式（思考=只算不下，会拦截自动落子）
            if (thinkPhase != ThinkPhase.OFF) {
                thinkPhase = ThinkPhase.OFF
                engine?.stop()
                mainHandler.post { btnFloatThinkRef?.text = "思考" }
            }
            resetSessionState()
            // 自动判边模式：新连线会话执边归位白（残留上一局的执边会在开局/校准中重新确定；
            // 手动选边 sideAuto=false 时保留用户选择）
            if (sideAuto) mySide = Side.WHITE
            // v1.11.1（用户拍板）：点连线 = 断言"轮我"——立即把本地轮次翻到我方，
            // 首帧识别到位即可出手（不等任何探测）。识别同步后由追平机制自然接管。
            try { reloadStm(placementOf(extBoard.fen), mySide == Side.WHITE) } catch (_: Throwable) {}
            running = true
            state = State.CONNECTING
            startPollLoop()
        }
    }

    /** 我的库记录模式切换（v1.10.3）：
     *  开=立即以当前屏幕同步局面为谱起点（修"走完谱库里还是零"——此前起点要等
     *  第一次追平确认才建立，若局面对不上则全程不记录）+ 只思考建议不代走；
     *  关=封存当前谱入库+恢复自动连线（此时即"记完了"）；空谱如实提示 */
    private fun toggleRecordMode() {
        recordMode = !recordMode
        if (recordMode) {
            recordMoveCount = 0
            recordStartPlacement = null
            recordMoves.clear()
            lastThinkFen = null
            try { engine?.stop() } catch (_: Throwable) {}
            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
            lastSentMove = null
            // v1.11.3：点「记谱」= 开始解题（轮我方）——旧版残留"对方"轮次时
            // thinkForDisplay 直接 return → 引擎看起来死了（用户实测"点记谱引擎不出"）。
            // 强制翻轮我方 + 屏幕同步，思考建议立即启动。
            hashSkipCount = 99
            try { reloadStm(placementOf(extBoard.fen), mySide == Side.WHITE) } catch (_: Throwable) {}
            recordStartPlacement = placementOf(extBoard.fen).takeIf { MyBook.validPlacement(it) }
            recordStartWhite = extBoard.sideToMove == Side.WHITE
            // 起点非法（缺王等）→ 等下一帧识别同步后情况B 再建立
            mainHandler.post {
                tvStatus?.text = if (recordStartPlacement != null)
                    "记录模式 ON · 你走我记 · 点「记谱」结束并入库"
                else "记录模式 ON · 同步局面中…"
                btnFloatRecordRef?.setBackgroundColor(0xFF880E4F.toInt())
            }
        } else {
            val had = recordMoves.isNotEmpty()
            sealRecordLine("习题 · ${java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} · ${recordMoveCount}步")
            MyBook.flush()
            val n = MyBook.all().size
            mainHandler.post {
                btnFloatRecordRef?.setBackgroundColor(0x00000000)
                tvStatus?.text = if (had) "✓ 记录完成 · 我的库共${n}条谱 · 已恢复自动连线"
                else "未记录到着法（走子前需先连线同步局面）· 库共${n}条"
            }
        }
    }

    /** 手动纠偏：我方回合（v2.2.4 重写——用户 19:18"点我方回合重复走已出的子"根治）：
     *  PLAY 卡死时 extBoard 停在【点击前】，旧实现直接翻轮 → 引擎对旧局面算出同一步
     *  → 重复点击已走完的子。正解（AC3.0 哲学：屏幕是唯一真相）：以屏幕重识别重建，
     *  2 帧对齐后自动判轮出手——绝不基于过期的本地盘出手。 */
    fun forceMyTurn() {
        workHandler.post {
            try {
                state = State.NEW_GAME_SYNC
                identLastSig = ""; identFrames = 0
                ngSyncCandidateFen = null; ngSyncCount = 0
                lastAcceptedSig = ""
                verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                lastSentMove = null
                badFrames = 0
                pausedPendingResume = false   // v3.0.0：手动恢复（AC3.0 用户再点开始）
                clickBeforeSig = ""; clickAfterSig = ""; clickAfterUci = ""; pendingUserSig = ""
                // v3.4.23 修D: 手动纠偏=用户断言轮我——逃逸旗带上（重建后
                // reloadStm(mySide) → 立即出手——「点我钮没用」终解!!）
                ngEscapeMyTurn = true
                // v3.4.22（Minis 诊断: "点我方回合没用——闸门把它也拦了"!!）:
                // 手动纠偏=【人类断言】最高优先级——绕过一切闸门!!
                // 全清: 拒收计/稳定命中/锚/哈希/烂帧门基准/暂停——点「我」必生效!!
                ngRejectCount = 0; ngRejectTotal = 0
                pendingStableMove = null; pendingStableCount = 0
                pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
                anchorHash = null; curFrameHash = null
                hashSkipCount = 99; lastFrameHash = null
                badQualityCount = 0
                verifyLostCount = 0; oppWaitSig = ""
                waitSince = 0L
                // v3.4.15 烂帧门基准清（拒收期的基准可能是污染源!!）
                clickAfterUci = ""
                // 强制下一帧全识别（不信任何缓存!!）
                lastFrameHash = null
                mainHandler.post { tvStatus?.text = "手动纠偏：以屏幕重建（已绕过全部闸门）…" }
            } catch (_: Throwable) {}
        }
    }

    /** v3.4.22 等黑超时自动纠偏（Minis 自锁解!!）——同步全清（forceMyTurn 的内核!!） */
    private fun forceMyTurnInternal() {
        ngEscapeMyTurn = true   // v3.4.23: 等黑10秒无变化=断言轮我（lichess AI 秒回——不动=轮我!!）
        state = State.NEW_GAME_SYNC
        identLastSig = ""; identFrames = 0
        ngSyncCandidateFen = null; ngSyncCount = 0
        lastAcceptedSig = ""
        verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
        lastSentMove = null
        badFrames = 0
        pausedPendingResume = false
        clickBeforeSig = ""; clickAfterSig = ""; clickAfterUci = ""; pendingUserSig = ""
        ngRejectCount = 0; ngRejectTotal = 0
        pendingStableMove = null; pendingStableCount = 0
        pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
        anchorHash = null; curFrameHash = null
        hashSkipCount = 99; lastFrameHash = null
        badQualityCount = 0
        verifyLostCount = 0; oppWaitSig = ""
        mainHandler.post { tvStatus?.text = "等黑超时·自动以屏重建（自锁破解）…" }
    }

    /** 手动纠偏：对方回合——停止我方行动转监视（引擎搜索一并取消） */
    fun forceOpponentTurn() {
        workHandler.post {
            try {
                thinkingToken++
                try { engine?.stop() } catch (_: Throwable) {}
                verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                waitSince = 0L
                reloadStm(placementOf(extBoard.fen), mySide == Side.BLACK)
                state = State.WATCH
                mainHandler.post { tvStatus?.text = "手动纠偏：对方回合 · 监视中" }
            } catch (_: Throwable) {}
        }
    }

    /** 会话状态复位（连线开关时） */
    private fun resetSessionState() {
        verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
        lastSentMove = null   // 会话重置 = 物理记录清零（断开重连后守卫重新计）
        ngEscapeMyTurn = false   // v3.4.23 旗复位（穷举复位点铁律!!）
        ngBadQuality = 0; resetStandoff = 0; calibInvalidated = false   // v3.4.24 刀5/6 旗复位
        pausedSince = 0L   // v3.4.25 自愈计时复位
        waitSince = 0L; lastTapAt = 0L
        resetCandidateFen = null; resetCandidateCount = 0; resetMismatchCount = 0
        promoPhase = PromoPhase.OFF; promoToScreen = null; promoTaps = 0; lastPromoTapAt = 0L
        orientLock = null
        myMovesVerified = 0
        hashSkipCount = 0; lastFrameHash = null
        lastAcceptedPieces = 0
        lastAcceptedSig = ""   // v1.13.0：会话重置 → 锚点清空（首帧重锚定）
        ngSyncCandidateFen = null; ngSyncCount = 0   // v1.13.2：NewGameSync 状态复位
        pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null   // 双帧确认复位
        anchorHash = null; curFrameHash = null   // v1.14.0：哈希锚复位
    }

    private fun startPollLoop() {
        // v1.8.0：统一极速配置（用户拍板：只有极速，无稳定/极速之分）
        paceTurbo = true
        // v1.10.0：识别模型选择（极速小模型=用户皮肤专用 13 类分类器 / 精准=YOLO 大模型）
        useTinyModel = getSharedPreferences("engine_settings", MODE_PRIVATE)
            .getBoolean("tiny_model", true)
        if (useTinyModel) TinyClassifier.ensureLoaded(this)
        // v1.11.0 引擎看门狗（鹏飞 _checkEngineTimerAction 思想，报告 6.6）：
        // 周期巡检 + "上条命令无响应时长"跟踪。引擎假死（进程活着但 info 停流）
        // 是纯 onEngineDead 侦测不到的——巡检在 go infinite 运行中 30 秒无 info
        // 即强制重启引擎（防 MIUI 冻结后进程僵而不死）。
        scheduleEngineWatchdog()
        val gen = ++pollGeneration
        workHandler.post(object : Runnable {
            override fun run() {
                if (!running || gen != pollGeneration) return
                var delay = IDLE_MS
                try {
                    delay = tick()
                    tickErrors = 0
                } catch (t: Throwable) {
                    // tick 链路任何异常都不能杀进程（否则悬浮窗消失=进程死亡）
                    tickErrors++
                    // 异常摘要直接上屏：用户无需翻文件即可报告真凶
                    val top = t.stackTrace.firstOrNull()
                    val loc = top?.let { "${(it.fileName ?: "?")}:${it.lineNumber}" } ?: "?"
                    mainHandler.post {
                        tvStatus?.text = "异常×$tickErrors ${t.javaClass.simpleName} @${loc}"
                    }
                    try {
                        val dir = java.io.File(getExternalFilesDir(null), "crash")
                        if (!dir.exists()) dir.mkdirs()
                        java.io.File(dir, "tick_error.txt").writeText(
                            "time=${System.currentTimeMillis()} count=$tickErrors\n${t.stackTraceToString()}")
                    } catch (_: Throwable) {}
                    if (tickErrors >= 30) {
                        running = false
                        pollGeneration++
                        state = State.IDLE
                        mainHandler.post {
                            tvStatus?.text = "识别持续异常已暂停（crash/tick_error.txt）"
                            (btnConnect as? Button)?.text = "连线"
                        }
                        updateNoti("识别异常已暂停")
                        return
                    }
                }
                workHandler.postDelayed(this, delay)
            }
        })
    }

    /**
     * 事件驱动式 tick：返回下一次轮询延迟。
     * - 棋盘未定位/思考中/对方刚走完（局面变化）→ ACTIVE_MS 连续快扫
     * - 局面稳定（等对方走）→ IDLE_MS 省电
     * - 走子快速通道命中 → 立即触发引擎
     */
    private fun tick(): Long {
        if (state == State.THINK || state == State.PLAY) {
            // PLAY 看门狗：落子流程（点击→延时→再点击）异常卡死时 2.5 秒强制恢复
            // （postDelayed 丢失/异常都会导致永久 PLAY = "永远监视对方"死锁）
            if (state == State.PLAY &&
                android.os.SystemClock.elapsedRealtime() - playSince > 2500
            ) {
                state = State.WATCH
                mainHandler.post { tvStatus?.text = "落子流程超时，已恢复监视" }
                return activeDelay()
            }
            // v1.11.3→v1.12.0 收紧：THINK 超时=思考上限+3 秒（用户要求快；探活
            // 支柱让真僵死概率趋零，这仅是极端兜底）
            if (state == State.THINK &&
                android.os.SystemClock.elapsedRealtime() - playSince > cfgMoveTimeMs + 3_000
            ) {
                thinkingToken++
                state = State.WATCH
                mainHandler.post { tvStatus?.text = "引擎对弈请求超时 · 自动恢复…" }
                workHandler.post { onEngineDead() }   // 走自愈链重启并接续
                return activeDelay()
            }
            return activeDelay()
        }
        if (calibrating) return idleDelay()                       // 校准中暂停识别
        // AI 模型是唯一识别路径（亮度启发式已废弃——在非标准皮肤上输出垃圾）
        val yolo = YoloChessDetector.get()
        if (yolo == null) {
            mainHandler.post { tvStatus?.text = "AI 识别引擎未就绪，正在加载…" }
            YoloChessDetector.ensureLoaded(this) { ok, err ->
                mainHandler.post {
                    tvStatus?.text = if (ok) "AI 识别引擎就绪" else "AI 模型加载失败: $err"
                }
            }
            return activeDelay()
        }
        val bmp = capture() ?: run {
            // v3.1.5（云手机实测 9/16 16:20 破案）：画面静止时 MediaProjection 不产新帧
            // （红手指云手机/MIUI 冻结同款）→ capture 恒 null → tick 在此提前 return →
            // maybeAutoPlay 只在识别成功路径被调 → "轮我方但永不出手"死锁。
            // 修复：capture 失败也要走自愈门——轮我方+引擎空闲+无待验证 = 直接触发出手
            // （引擎思考/落子不依赖新截图，识别帧只用于消化对方步）。
            if (extBoard.sideToMove == mySide && !engineSearching &&
                state == State.WATCH && verifyUci == null && thinkPhase == ThinkPhase.OFF && !recordMode
            ) {
                maybeAutoPlay()
            }
            return activeDelay()
        }
        try {
            // ── v3.4.24 刀3: 等黑逃逸背刺——上移 tick 顶（与哈希/跳帧彻底解耦!!）──
            // TA 10/04 铁证: v3.4.22/23 逃逸全写在跳帧分支(L853+)里,而 L817 的
            // `?: bmp` 全屏兜底(含悬浮窗文字每tick变!!)→哈希永不稳→跳帧分支永不
            // 执行→所有逃逸全灭→TA 等 10 分钟没动静。正解: 逃逸每 tick 必检!!
            // 语义: 等对方 10 秒无 verify/无轮我 → 断言轮我重建(lichess AI 秒回,
            // 10秒不动=唯一合理解释是轮我!!)——误判时点击被拒→handleTapFailed
            // 5连败转监视自纠(自愈闭环!!)。
            val nowMs0 = android.os.SystemClock.elapsedRealtime()
            if (state == State.WATCH && extBoard.sideToMove != mySide &&
                verifyUci == null && !recordMode) {
                if (waitSince == 0L) waitSince = nowMs0
                if (nowMs0 - waitSince >= 25000L) {   // v3.4.25: 10→25秒——给情况B每帧重试留足窗口（抢跑=饿死消化!!）
                    waitSince = 0L
                    forceMyTurnInternal()
                    mainHandler.post { tvStatus?.text = "等黑超时·以屏重建（逃逸·断言轮我）" }
                    return activeDelay()
                }
            } else if (state != State.NEW_GAME_SYNC) {
                waitSince = 0L   // 非等待态清零（NGS 期间保留计时!!）
            }

            // ── 僵尸验证处理（v1.9.0 重构：先看屏幕再决定重试）──
            // 验证超时不再立即重试点击——对方可能已回应（我方落子+对方回应常挤在同一识别
            // 间隙内），本帧强制完整识别后 syncBoard 的追平（情况B/C）会直接确认我方
            // 落子并清掉 verify，无需重试。这根治"对方已下还在盲目重试落子→侥幸走出
            // 错步→局面分叉"（用户实测）。若识别后屏幕仍无我方落子 → 情况A 到期收割重试。
            if (verifyUci != null && android.os.SystemClock.elapsedRealtime() > verifyDeadline) {
                hashSkipCount = 99   // 强制本帧完整识别（绕过帧哈希跳过）
            }

            // ── 帧缓存：画面没变直接跳过（哈希只取棋盘区域，不被棋盘外时钟/动画干扰）──
            // v3.4.24 刀4: ①外扩6%收缩为0（margin 会圈进 lichess 时钟/悬浮窗边缘
            // ——每秒变=哈希永不稳=跳帧分支死=旧逃逸全灭!!——TA 10/04 案的次因!!）
            // ②去掉 `?: bmp` 全屏兜底（全屏含悬浮窗状态文字——每 tick 变!!）——
            // rect 空 → fh=null → 永不跳帧=每帧识别（方向安全: 宁可多识别绝不漏!!）
            val hashRegion = (calibFrameRect() ?: boardRect)?.let { r ->
                try {
                    val x = r.left.coerceAtLeast(0)
                    val y = r.top.coerceAtLeast(0)
                    val w = (r.cell * 8).coerceAtMost(bmp.width - x)
                    val h = (r.cell * 8).coerceAtMost(bmp.height - y)
                    if (w >= 64 && h >= 64) Bitmap.createBitmap(bmp, x, y, w, h) else null
                } catch (_: Throwable) { null }
            }   // null=不哈希(强制识别!!)——绝不 bmp 全屏兜底!!
            val fh = if (hashRegion != null) quickFrameHash(hashRegion!!) else null
            if (hashRegion != null) hashRegion!!.recycle()
            curFrameHash = fh   // v1.14.0：当前帧哈希（幽灵步防线用）
            // v3.4.21: 跳帧诊断也强制更新（TA 截图的 S 永远=最新真实帧!!）
            try { syncDiag = "S=${lastAcceptedSig.take(20)}|L=${placementOf(extBoard.fen).take(12)}|hs=${hashSkipCount}|st=$state|v=${verifyUci != null}" } catch (_: Throwable) {}
            // 跳过上限（v1.13.3 大模型静止节流——用户 9/9 13:27 速度反馈）：
            //  · 小模型（~50ms/帧）：极速=1（每2tick强制识别≈660ms，防漏检）
            //  · 大模型（YOLO ~1.7s/帧 机型实测）：静止期高跳过=8（~14s 才强制全识别
            //    一次）——画面一变（对方走子/我方点击生效）帧哈希立即变化触发识别，
            //    静止等待期不再烧 YOLO → 全链路 1.x 秒降到思考+点击级。变体识别
            //    延迟=0（哈希变化即时响应），静止期兜底节流。
            // v1.13.4（用户 13:57 症状根因）：【有待确认步/新局对齐中 → 禁跳过】——
            // 双帧确认与 NewGameSync 都需要"下一帧识别"闭环，若被哈希跳过卡住
            // （静止期 8 帧跳过=13 秒）→ "对方走了还显示对方行棋中"死等。待确认
            // 期间强制识别（确认即完成，回到正常节流）。
            val hashSkipLimit = when {
                pendingStepConfirmObs != null || ngSyncCandidateFen != null -> 0   // 待确认=强制识别（下一帧复现闭环）
                // v3.4.17（TA 9/42 实测「下得非常慢/走过了还重复走同一步」根治!!）:
                // verify 期旧值 1=每2tick才识别——点击后闭环确认最慢 660ms+——
                // 期间 verifyDeadline 逼近→幂等重发已走过的步（lichess 拒绝→乱点）。
                // 小模型 50ms/帧完全扛得住每帧识别——verify 期强制 0（闭环即达!!）——
                // 大模型机型（~1.7s/帧）由下方 else->2 兜底防堵循环。
                verifyUci != null -> 0   // 验证窗口=强制识别（小模型 50ms/帧——闭环提速一倍!!）
                useTinyModel -> if (paceTurbo) 1 else 2
                // v3.1.6（用户 9/16 17:40"大模型中途卡住"根治）：旧值 8 = 静止期
                // ~14 秒才强制全识别一次——若对方走子落在哈希盲区（24×24 亮度采样
                // 漏检小位移/暗格移动），对方步最多 14 秒不被消化 = 用户实测"卡住
                // 监视屏幕"。照 AC3.0/鹏飞共同铁律【每帧全跑识别】：大模型跳过上限
                // 压到 2（第 3 tick 必识别，盲区窗口 ≤3 秒）。宁可多烧一次识别，
                // 绝不漏对方一步。
                // v3.4.20（TA 9/29 01:39「对方下完了还死等」终极根治!!）:
                // 等对方行棋期=画面随时会变（对方落子是唯一事件源!!）——哈希盲区
                // （24x24 亮度采样漏检暗格小位移——e5 黑兵走子实测漏!!）= 盲跳
                // → e5 永不被识别 → 永死等!! 等对方期上限压到 1（每2tick必识别
                // ~2秒内必见!!）——宁可烧识别，绝不漏对方一步!!
                // v3.4.21（TA 10/1「快点修复」!!）: verify 期同样禁跳——
                // e4 点击后的秒回窗口（黑 e5 在 verify 期内到达!!）——跳帧=漏!!
                extBoard.sideToMove != mySide -> 1
                verifyUci != null -> 1
                else -> 2
            }
            if (fh != null && fh == lastFrameHash && hashSkipCount < hashSkipLimit) {
                hashSkipCount++
                // v3.1.6 主界面棋盘实时性（用户 9/16 17:50 实测"App 内棋盘没跟
                // 连线棋盘摆动"）：跳帧期也推送 extBoard 局面——鹏飞/AC3.0 的
                // drawBoard 每帧无条件画，内置棋盘=用户可见的信任锚。
                try { positionListener?.invoke(extBoard.fen, lastMoveDesc) } catch (_: Throwable) {}
                // ── 画面冻结（v1.10.5 重设计，对齐鹏飞）──
                // 旧 v1.7.4 探测：等对方 3 秒没动静就翻轮出手——lichess 对方思考
                // 10-30 秒是常态，3 秒翻轮 = 必然误出手（用户实测"对方还没下它自己下"
                // + 3s/8s 周期翻轮-点击失败-再翻轮永动乒乓）。鹏飞证据（so 文案）：
                // "最大步时/单步防超时/最长等待时间"——超时只告警不自作主张翻轮；
                // 官方建议"最好是轮到自己走子时再连接"= 连线时机的行棋权由用户断言。
                // 现在：画面冻结+轮对方 = 正常等待（最多上屏提示）；真正的出手信号
                // 只有屏幕变化（追平）或用户手动纠偏（「我」钮）。等屏即真理。
                if (state == State.WATCH && extBoard.sideToMove != mySide && verifyUci == null) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (waitSince == 0L) waitSince = nowMs
                    val waited = (nowMs - waitSince) / 1000
                    // v3.4.22（Minis 自锁终极解!!）: 等黑 10 秒无变化 → 自动以屏重建
                    // （自锁: 等的画面变化只有自己动才产生——不动因为在等!!——
                    //   10 秒=AI 回应极限——超=屏上必是轮我（lichess/鳕鱼秒级!!）
                    //   以屏重建+轮我出手=打破自锁!!——等屏即真理，超时即背叛!!）
                    // v3.4.24 刀3: 逃逸本体已上移 tick 顶（每 tick 必检!!）——此处只留显示!!
                    if (waited > 0L && waited % 3L == 0L) {   // v3.4.21: 每3秒（哈希=优化不能当正确性依赖——SOUL: 识别层管真理!!）
                        mainHandler.post { tvStatus?.text = "等待对方已 ${waited}秒（画面无变化）· 若确为轮你请点「我」" }
                        // v3.1.2 死等兜底：15 秒无变化 → 强制下帧全识别（绕过哈希
                        // 跳过与静止复核——防"画面已变但哈希/复核链漏检"型死等，
                        // 用户 15:38 实测"对方下了没反应"的最后防线）
                        hashSkipCount = 99
                        anchorHash = null   // 复位静止锚：本帧复核关闭，变化原样通过
                    }
                } else if (state == State.WATCH && extBoard.sideToMove == mySide
                    && verifyUci == null && lastSentMove != null && waitSince == 0L) {
                    // 鹏飞 _lastSentMove 守卫：本地"轮我"但存在未闭环的已发送着法且屏幕无变化
                    // = 我方步屏幕尚未确认（或被回退）——继续等屏幕确认，不重复出手。
                    // 这是旧版"对方没下我却再下一手"的另一个入口（情况A 收割路径外的重复触发）。
                } else {
                    waitSince = 0L
                }
                return idleDelay()
            }
            // v3.3.0 验证失联暂停（AC3.0 toggleStart 停哲学）：不识别不出手，
            // 等「我方回合」以屏幕重建（forceMyTurn 已处理一切状态复位）
            if (state == State.PAUSED_VERIFY_LOST) {
                // v3.4.25: PAUSED 15 秒自愈——手动死局终结!!（旧代码只等人工点
                // 「我方回合」=TA 看到的"走完第1步就永远停"!!）。自愈=以屏重建
                // （forceMyTurn 内核!!）,错误时点击被拒→5连败转监视自纠!!
                if (pausedSince == 0L) pausedSince = android.os.SystemClock.elapsedRealtime()
                if (android.os.SystemClock.elapsedRealtime() - pausedSince >= 15000L) {
                    pausedSince = 0L
                    forceMyTurnInternal()
                    mainHandler.post { tvStatus?.text = "暂停超时·自动以屏重建（自愈）" }
                }
                return idleDelay()
            } else pausedSince = 0L
            // 哈希变化或强制识别（连续跳过 2 次后第 3 次必识别——漏检保险）
            hashSkipCount = 0
            lastFrameHash = fh

            // ── 两阶段识别（416 输入的关键架构）──
            // 已知棋盘区域（校准框/已定位）→ 裁剪后推理（棋子 ~50px，精度最优）
            // 未知区域 → 全帧找盘（大目标检出 ✓）→ 立即裁剪二次推理补棋子
            // （长截图全帧 416 下棋子仅 ~16px 必丢，云端真实截图 A/B 验证过两阶段=640全帧精度）
            // v3.2.1 整图高亮归一化（大模型 YOLO 路径——用户"大模型也有这问题"）：
            // 逐像素色域检测高亮底色→逆变换回标准色，棋子像素不动。小模型 tile 级
            // 还有一层（TinyClassifier.normalizeHighlight）——双保险。像素级确定性
            // 变换，零重训，768× Screen 帧 <3ms。
            normalizeHighlightBitmap(bmp)
            val rec = try {
                recognizeScreen(bmp, yolo)
            } catch (t: Throwable) {
                // 异常上屏（v1.4.4 教训：静默吞掉=只看到"未检测到棋盘"无从定位）
                mainHandler.post { tvStatus?.text = "识别异常 ${t.javaClass.simpleName}@tick" }
                null
            }
            if (rec == null) {
                mainHandler.post { tvStatus?.text = "未检测到棋盘，切到对弈界面或点「校准」" }
                return activeDelay()
            }
            boardRect = rec.rect
            lastRec = rec
            recWhiteAtBottom = rec.whiteAtBottom
            lastRecPieceCount = rec.pieceCount
            // 首次定位成功 → 立即离开 CONNECTING（v1.6.1"一直显示定位棋盘不动了"修复：
            // 升变误检分支曾把每帧吞掉导致状态永远停在连接中）
            if (state == State.CONNECTING) {
                state = State.WATCH
                mainHandler.post { tvStatus?.text = "棋盘已定位 · 监视中…" }
            }
            var recF = rec
            val lock = orientLock
            if (lock != null && rec.whiteAtBottom != lock) {
                recF = BoardDetector.reorient(rec, lock)
            }

            // ── 走子动画帧过滤（v1.8.0：对方快速走子防卡死）──
            // 对方秒回时识别常落在"棋子提起/移动中"的中间态（棋子数骤降、摆位乱），
            // 进状态机会触发连环重置 → 长时间"监视对方"。骤降帧直接丢弃（下一帧自然恢复）。
            if (lastAcceptedPieces > 0 && recF.pieceCount < lastAcceptedPieces - 1) {
                return activeDelay()
            }

            // ── v3.4.3 识别质量硬门（lichess 开局送象·现行铁证 c11"识别13子"根治）──
            // YOLO 对 lichess 棋子风格泛化差：检出率崩（13/32）+错格（e4 兵落 e5、
            // c1 象落 a6）+王后互换。旧门只拦"比锚点少 1 子"——开局锚点还没建立时
            // 烂帧全放行 → 状态机在烂输入上消化/点击 → lichess 上走出送象级鬼步。
            // 硬门：与【门基准】子数差 >3 = 烂帧 → 整帧丢弃（不决策/不出手/不点击/
            // 不重置/不推进 verify 状态）；连续 5 帧烂 → 清 boardRect 强制重定位棋盘。
            // 识别层的病就该在识别层拦死，绝不带病进状态机（9/16 铁律）。
            //
            // ── v3.4.15 门基准归位（lichess AI 秒回死锁·根因修复 9/26 实测铁证）──
            // 根因：本门旧判据拿【extBoard 本地盘】当"屏幕应有样子"的基准——但
            // performMove 点击落屏后，本地盘要等 verify 闭环（屏幕==clickAfterSig
            // 才 doMove）才推进——【点击→闭环】过渡期内本地盘合法滞后于屏幕（差
            // 我方步=occ 2 格）。对方若在此窗口秒回（lichess AI 对手<1s!!），过渡
            // 帧=本地+我步+对方步=occDiff 4 格>3 → 合法帧被判烂帧 → verify 的输入
            // 帧全被本门吃掉 → clickAfterSig 永不闭环 → extBoard 永不推进 → 每帧
            // 都差 4 格 → 死锁在「识别质量差(32/32)·重新定位中」循环（TA 实测：
            // e4 后 Stockfish 秒回 e5，状态卡重定位、断线重连也无效!!）。
            // 鳕鱼 VIII 上真人节奏≥3s：verify 窗口(3.5s)先闭环、基准先更新——
            // 此病 300 局无恙；lichess AI 秒回直接吃掉窗口——必现死锁。
            // 修法（基准归位·非豁免）：门基准必须始终代表【屏幕此刻应有的样子】——
            //   稳态（无待闭环点击!!）: extBoard（原基准!!）
            //   过渡期（clickAfterSig 非空!!）: clickAfterSig（=本地+我步——点击
            //     已落屏，屏幕至少如此!!）。合法过渡帧（对方最多再走1步=occDiff≤2）
            //     放行给 verify/追平消化；真烂帧（定位崩/错格串——任何基准下差>3）
            //     照拦——门的识别层职责不变，verify/追平机制（AC3.0 根基!!）零改动。
            val gateBase = if (clickAfterSig.isNotEmpty()) clickAfterSig else placementOf(extBoard.fen)
            val gatePieces = gateBase.count { it != '/' && !it.isDigit() }
            // occ 一致性（比子数更本质——漏检/错格在 occupancy 层就暴露；防上局残局
            // 子数恰好=烂识别子数的巧合漏网）：与门基准 occ 差>3 = 定位层崩 → 烂帧
            val gateOcc = ChessSyncRules.occMaskOf(gateBase)
            val obsOcc0 = ChessSyncRules.occMaskOf(BoardDetector.toPlacement(recF))
            var occDiff = 99
            if (gateOcc.length == 64 && obsOcc0.length == 64) {
                occDiff = 0
                for (i in 0 until 64) if (gateOcc[i] != obsOcc0[i]) occDiff++
            }
            if (gatePieces > 0 && (kotlin.math.abs(recF.pieceCount - gatePieces) > 3 || occDiff > 3)) {
                badQualityCount++
                if (badQualityCount >= 5) {
                    badQualityCount = 4   // 保持热（下一帧烂=立即再放行!!——重置链需连续双帧!!）
                    // 旧: 盲清 boardRect 强制重定位——但 lichess 死锁实测（9/26!!）
                    // 定位是对的（红网格实测对齐!!）、病在【门基准与屏幕持续分歧】!!
                    // 重定位=确定性算法→同 rect→同识别→同 occDiff→死循环!!
                    // 且清 boardRect 连带清掉 lastFrameHash/哈希锚——破坏重连自愈。
                    // 新: 第 5 帧烂=「分歧持续」→ 放行本帧进 syncBoard 重置链!!
                    // 重置链自带全套防线（双帧确认+frameQuality 拦截+一步可达豁免!!
                    // 2074-2107!!）——屏幕=真理（AC3.0!!）——它自己决定该不该重摆
                    // extBoard（重置成功=门基准追平屏幕=放行→自愈闭环!!）——门不再
                    // 越权替重置链做决定!!
                    hashSkipCount = 99   // 下一帧强制完整识别（重置链要双帧!!）
                    mainHandler.post { tvStatus?.text = "门拦5帧·放行重置链(occDiff=$occDiff 盘${gatePieces})" }
                    // 【不 return!!】——本帧落到 syncBoard（重置链评估!!）
                } else {
                    return activeDelay()
                }
            }
            badQualityCount = 0

            // ── v3.4.16 门仪表盘（TA 铁律「诊断上屏」!!）──
            // 烂帧门拦帧时无任何可见数据（tvPv 只在 verify 窗口显示 syncDiag!!）——
            // 死锁时拍照取证只有一行「识别质量差」——门基准 vs 识别帧的差黑盒化。
            // 现在: 门触发即把【识别帧摆位 vs 门基准】前缀+occDiff 打到 tvPv——
            // 拍一张照立即破案（哪个格错/extBoard 停在哪步!!）。纯可观测性——零行为改动!!
            mainHandler.post {
                tvPv?.text = ("门差异 occ=$occDiff 基[${gateBase.take(18)}] 识[${BoardDetector.toPlacement(recF).take(18)}]").take(46)
            }

            // ── v3.4.15 帧序归位（执边翻转污染根治·9/26 TA 实测「我执黑??明执白」!!）──
            // 根因：旧代码视角锁/判边排在烂帧门【之前】——死锁循环产生的乱帧（页面
            // 半渲染/重定位错 rect!!）先跑判边（下半盘法则!!）→ orientLock/mySide 被
            // 乱帧污染（执白判成执黑!!）→ 之后帧才被门丢弃——脏判定留存、帧被扔!!
            // 「我执黑+对方行棋中」=判边污染的直接症状（屏幕明明白在下!!）。
            // 修法（帧序归位!!）：判边/视角锁只在【过了质量门的帧】上跑——门拦住
            // 的帧没资格参与任何决策（含判边!!——与 9/16 铁律同源:「烂输入不决策」）。
            // 注意：判边用 rec 原始 types（屏幕物理方向语义!!——reorient 只翻棋盘
            // 语义、判边依赖「谁坐在屏幕下方」的物理事实!!）。
            // ── 视角锁（v1.7.6 简化 + v1.8.0 中局判边法则）──
            BoardDetector.openingOrientation(rec)?.let { opening ->
                if (orientLock != opening) {
                    orientLock = opening
                    mainHandler.post { tvStatus?.text = "视角锁定：白${if (opening) "在底" else "在顶"}" }
                }
            }
            if (orientLock == null && !isStandardOpeningShape(rec)) {
                // 中局判边（v1.8.0）：下半盘（rows 4-7）多数色 = 我方
                var wHalf = 0; var bHalf = 0
                for (r in 4 until 8) for (c in 0 until 8) {
                    val t = rec.types[r][c]
                    if (t in 'A'..'Z') wHalf++
                    else if (t in 'a'..'z') bHalf++
                }
                if (wHalf != bHalf) {
                    val majorityWhite = wHalf > bHalf
                    orientLock = majorityWhite
                    if (sideAuto) mySide = if (majorityWhite) Side.WHITE else Side.BLACK
                    mainHandler.post {
                        renderSideSelection()
                        tvStatus?.text = "判边（下半盘法则）：我执${if (majorityWhite) "白" else "黑"}"
                    }
                }
            }
            // 底线出现"兵"只有两种可能：真升变（弹框/动画中）或 误检（如角落车被读成兵）。
            // 区分方法：把该子依次改判为 后/车/象/马 → 摆位若能被本地局面【合法走子追平】
            // （= 对方真的走了那步升变）→ 升变成立，落入正常同步；
            // 全部追不平 = 误检噪声 → 丢弃本帧。绝不进入任何等待/卡死状态。
            val pawn = if (isStandardOpeningShape(recF)) null else BoardDetector.pawnOnBackRank(recF)
            if (pawn != null) {
                val mine = pawn.white == (mySide == Side.WHITE)
                if (mine && verifyUci != null && verifyUci!!.length >= 5) {
                    // 我方升变中：视觉 picker（弹框皇后）。
                    // NO_DIALOG（点过且弹框皇后消失）→ 升变完成，改判为后落入正常同步
                    when (handlePromotionPicker(bmp, recF, yolo)) {
                        PromoPickResult.PICKED, PromoPickResult.WAIT, PromoPickResult.TIMEOUT ->
                            return activeDelay()
                        PromoPickResult.NO_DIALOG -> {
                            recF.types[pawn.row][pawn.col] = if (pawn.white) 'Q' else 'q'
                            mainHandler.post { tvStatus?.text = "升变完成（即时确认）" }
                        }
                    }
                } else {
                    // 对方/无上下文：改判尝试 后/车/象/马 依次做合法追平验证
                    val tries = charArrayOf('Q', 'R', 'B', 'N')
                    var hit = false
                    for (pc in tries) {
                        recF.types[pawn.row][pawn.col] =
                            (if (pawn.white) pc else pc.lowercaseChar())
                        val fenQ = BoardDetector.inferFen(recF)
                        val placementQ = if (fenQ != null) placementOf(fenQ) else null
                        if (placementQ != null && findMoveToPlacement(extBoard, placementQ!!) != null) {
                            hit = true
                            mainHandler.post { tvStatus?.text = "对方升变${pc}" }
                            break
                        }
                    }
                    if (!hit) {
                        // 误检噪声（如角落车被读成兵）：丢弃本帧，绝不动状态机
                        recF.types[pawn.row][pawn.col] = if (pawn.white) 'P' else 'p'
                        return activeDelay()
                    }
                }
            }

            val fenNow = BoardDetector.inferFen(recF)
            // 性能自诊：推理耗时+版本实时上屏（v2.1.1：截图自动携带版本——诊断不再猜版本）
            val verName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
            mainHandler.post { tvDepthReached?.text = "v$verName·${yolo.lastDetectMs}ms·${yolo.engineName}" }
            var fenUsed = fenNow
            if (fenUsed == null) {
                // ── v1.14.2 王位补全（鹏飞 GetKingCountAndComputeChessDiffInfo 思想）──
                // 模型丢王（黑王0个）→ 锚点（上次共识帧）的王位=强先验补回——
                // 王在对局中永不清除，识别丢王必是误检；补全后重推。
                fenUsed = repairKingsFromAnchor(recF)?.let { BoardDetector.inferFen(it) }
                if (fenUsed != null) {
                    mainHandler.post { tvStatus?.text = "王位补全恢复识别" }
                }
            }
            if (fenUsed == null) {
                // ── v3.1.1 王重复仲裁（用户 9/15 14:53"识别32子·推算失败·白王2个"根治）──
                // 复刻实锤：小模型 rect 偏 ±2px 时底线格误判（黑王读成白王 → K=2 →
                // inferFen 校验拒绝）。数学事实：王在对局中永不新增——多出的 K/k 必是
                // 误判。锚点（上次共识帧）王位=真王位；非锚点王位的 K 格按锚点同格
                // 类型改判（锚点该格是什么就改成什么，含清空）。917 行补全是"王丢了
                // 补回"，本仲裁是"王多了删错"——同一先验的两个方向。
                // 云端 10 场景验证 9/10：唯一失败场景=锚点王位自身也读错 → 仲裁后
                // 再补全（repair 王位强制写回）形成闭环 → 10/10。
                fenUsed = (arbitrateKingsFromAnchor(recF) ?: recF).let { arb ->
                    repairKingsFromAnchor(arb) ?: arb
                }?.let { BoardDetector.inferFen(it) }
                if (fenUsed != null) {
                    mainHandler.post { tvStatus?.text = "王位仲裁恢复识别" }
                }
            }
            if (fenUsed == null) {
                // ── v3.1.7 锚点引导推算（用户 22:26"王数正常却推算失败"根治）──
                // 实测证据：识别 29/32 子正确、王数正常，但局部 1-3 格错读（如兵
                // 读到底线）触发 inferFen 校验拒绝 → 整帧作废 → 对方步带识别噪声
                // 永远过不了 2 格铁律 → 卡"监视屏幕"。学界标准（arXiv 2310.04086：
                // 与前一已知状态增量比对）+ AC3.0 吸收门（锚点只经合法步更新，不
                // 被污染）→ 失败帧可用锚点重构：锚点枚举全部合法单步，与识别帧逐格
                // 对照，差异 ≤2 格（纯类型抖动）的唯一解释 = 该步真实发生，错读格
                // 被棋盘事实纠正。这是"识别自己对照自己"的数学实现，不依赖重定位
                // （棋盘没动，重定位无意义——用户实测证伪）。
                fenUsed = anchorGuidedInfer(recF)
                if (fenUsed != null) {
                    mainHandler.post { tvStatus?.text = "锚点引导推算恢复识别" }
                }
            }
            if (fenUsed == null) {
                // v1.13.6（用户 9/10 01:14 截图破案需求）：推算失败原因上屏——
                // 王数/王位/底线兵/构造异常分类 + ASCII 棋盘，拍照即定位
                val diag = BoardDetector.inferFenDiag(recF)
                mainHandler.post {
                    tvStatus?.text = "识别${recF.pieceCount}子 推算失败·$diag"
                    tvPv?.text = BoardDetector.asciiBoard(recF)
                }
                return activeDelay()
            }
            inferFailCount = 0
            val fenNow2 = fenUsed
            // 升变弹框开着但兵没显示在底线（部分 App 样式）：棋盘没变化超宽限期 → 视觉找皇后
            if (promoPhase == PromoPhase.PICKING) {
                if (placementOf(fenUsed) != verifyPrePlacement) {
                    promoPhase = PromoPhase.OFF   // 棋盘已变化（升变完成）→ 交给 syncBoard 追
                } else if (android.os.SystemClock.elapsedRealtime() > promoGraceUntil) {
                    handlePromotionPicker(bmp, rec, yolo)
                    return activeDelay()
                }
            }

            // 推送镜像给主界面（App 内实时对局显示）
            try { positionListener?.invoke(fenUsed, lastMoveDesc) } catch (_: Throwable) {}

            // ── v1.13.2 NewGameSync 对齐（鹏飞 8.2：TryNewGame → 基准图+fen 对齐一致 → Playing）──
            // 新局重置后在此确认：连续 ngSyncNeed 帧同摆位=识别已稳定 → 回 WATCH 正常
            // 循环（轮我自然出手）。漂移（识别稳定后与重置帧不同）→ 以稳定摆位重建再确认。
            // 替代旧"重置→立即出手"（乱走根因）与 1.5s 延时（用户否决）——状态驱动。
            if (state == State.NEW_GAME_SYNC) {
                val plac = placementOf(fenUsed)
                if (plac.isEmpty() || piecesOf(plac) < 2) {
                    return activeDelay()   // 全空/异常帧：继续观察
                }
                // ── v3.1.6 中盘保护（用户 9/16 17:11 实测"识别错被当真"根治）──
                // 云手机实测证据：评估 +3.35 → -7.03 暴跌 = 中盘摆位（含识别错）被
                // ngSync 当"新局"重置进 extBoard。ngSync 的入口在情况D（对不上累计
                // 2 帧稳定）→ 中盘识别稳定错读（rect 漂移后每帧同样错）→ 2 帧一致
                // → 旧代码直接 extBoard=错误摆位 = 局面错乱 = 按错局面下错棋。
                // 修复门：进入 ngSync 吸收必须满足其一——
                //   ① 标准开局形态（真正的"新局"，isStandardOpeningShape）
                //   ② 与当前 extBoard 摆位 diff ≥ 10 格（真正的换局剧变）
                //   ③ 本局还没接受过任何步（lastAcceptedPieces==0，连线初始化）
                // 否则=中盘错读，丢弃候选继续观察（识别自愈通道处理 rect）。
                // v3.4.13 质量门（NGS 重建源头）：崩盘帧不进对齐候选
                if (ChessSyncRules.frameQuality(plac) < 0) {
                    // v3.4.24 刀5: 质量僵局逃逸——NGS 质量门永久拦截(如幽灵9兵帧
                    // =-50!!)时旧代码 activeDelay 死循环且无任何计数器推进!!
                    // AC3.0 哲学对照: 它 5 帧认不出直接 toggleStart 停给人类。
                    // 我们的选择: 20 帧(≈7秒)烂→回 WATCH 继续逐帧消化尝试
                    // ——识别一恢复(帧变干净!!) 情况B 立即消化出手=真逃逸!!
                    ngBadQuality++
                    if (ngBadQuality >= 20) {
                        ngBadQuality = 0
                        state = State.WATCH
                        mainHandler.post { tvStatus?.text = "识别持续异常·回监视（等识别恢复）" }
                    }
                    return activeDelay()
                }
                ngBadQuality = 0
                val curPlac = placementOf(extBoard.fen)
                val shapeOk = isStandardOpeningShape(recF)
                val hugeDiff = lastAcceptedPieces > 0 && diffCells(plac, curPlac) >= 10
                val freshSession = lastAcceptedPieces == 0
                if (!shapeOk && !hugeDiff && !freshSession) {
                    ngSyncCandidateFen = null; ngSyncCount = 0
                    // v3.1.6 防新死锁：拒收必须退回 WATCH——否则永远卡在 NEW_GAME_SYNC
                    // （该块只做摆位对齐不做单步匹配，对方步永不被消化）。退回后
                    // syncBoard 情况B（AC3.0 式 2 格铁律）继续跑：识别错帧持续被
                    // 丢帧，一旦识别恢复正常，对方步立即消化。
                    state = State.WATCH
                    // 持续拒收=rect 系统性偏移（识别稳定错读）→ 触发重定位自愈
                    // （清 boardRect 让 YOLO 重新找盘——v3.1.5 inferFail 同款机制
                    // 扩展到同步层）。拒 3 次=重定位，防识别错永远好不了。
                    ngRejectCount++
                    if (ngRejectCount >= 3) {
                        ngRejectCount = 0
                        boardRect = null; tinyLastPlacement = null
                        try { calibPrefs().edit().clear().commit() } catch (_: Throwable) {}
                        hashSkipCount = 99; lastFrameHash = null
                        mainHandler.post { tvStatus?.text = "识别持续异常·重新定位棋盘中…" }
                    } else {
                        // v3.4.22（Minis 10/1 诊断实锤!!）: 拒收闸门【超时逃逸】!!
                        // 死锁链: 拒收→等画面恢复→屏幕不再变（自己卡着）→永等!!
                        // 闸门是防污染【不是防真理】——拒收累计 15 次（~5秒!!）
                        // → 强制以屏幕重建（识别帧虽差1格——occ 事实优先!!
                        //   闸门防的是【重建到错局】——15 次拒后错局风险 < 死锁风险!!）
                        ngRejectTotal = ngRejectTotal + 1
                        if (ngRejectTotal >= 15) {
                            ngRejectTotal = 0
                            // v3.4.23 修A: 逃逸走【首见预重建链】!!
                            // v3.4.22 的坑: candidate=plac+count=1 → 下一帧走
                            // 相等分支 count=2 → 完成块只换 state——【预重建只在
                            // else 首见分支!!】被整个跳过 → extBoard stale 如初!!
                            // 正解: candidate 置 null → fall through 落 else 分支
                            // → 预重建（plac≠extBoard 时!!）→ 下帧 count=2 → 完成。
                            ngSyncCandidateFen = null; ngSyncCount = 0
                            // v3.4.23 修B: 逃逸旗——中盘重建轮次断言=【轮我】!!
                            // （拒收 15 次≈5 秒+此前的等待——对方早该走完了!!
                            //   lichess AI 秒回——还不动=轮我是唯一合理解释!!
                            //   逃逸重建后强制 reloadStm(mySide) → 出手破自锁!!）
                            ngEscapeMyTurn = true
                            mainHandler.post { tvStatus?.text = "拒收超时·以屏重建（逃逸·断言轮我）" }
                        } else {
                            mainHandler.post { tvStatus?.text = "中盘摆位异常·拒收重置（防识别错污染 ${ngRejectTotal}/15）" }
                            return activeDelay()
                        }
                    }
                    // v3.4.22: 拒收超时——不再 return——落到 ngSync 重建!!
                }
                if (plac == ngSyncCandidateFen) {
                    ngSyncCount++
                } else {
                    ngSyncCandidateFen = plac
                    ngSyncCount = 1
                    // 首见/漂移摆位：预重建 extBoard（合法才换），避免对齐完成后二次跳变
                    if (plac != placementOf(extBoard.fen)) {
                        // v3.4.2（lichess 三连故障根治）：
                        // ① 旧代码 buildBoard(plac, mySide==WHITE)——把"我执边"当
                        //    "轮谁走"传——新局永远白先，执黑时开局本地就轮错边
                        //    （用户"我方回合他说对方走"根因之一）。
                        // ② 重建直接吃识别 plac（类型层）——lichess 棋子风格类型错读
                        //    爆炸（王后互换/双白王，c8/c10 铁证）→ 污染盘灌进本地 →
                        //    引擎在污染盘上算"合法"步 → 真棋盘上开局送象。
                        //    修：isStandardOpeningShape 本就是 occ 级判定——形态确认
                        //    =新局后【用标准初始摆位重建】——识别只判"是不是新局"，
                        //    绝不提供类型（occ 与初始盘一致的类型噪声被标准盘覆盖）。
                        //    非标准形态（中盘剧变重建）保留识别 plac 但加王数守卫
                        //    （K=1&&k=1——双王/王后互换盘拒绝）。
                        val isFreshShape = isFreshPlacementSig(plac)
                        val kingsOk = plac.count { it == 'K' } == 1 && plac.count { it == 'k' } == 1
                        val rebuildPlac = if (isFreshShape && kingsOk)
                            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
                        else if (kingsOk) ChessSyncRules.purgeKqOf(plac)   // v3.4.11 同款净化
                        else return activeDelay()   // 王数异常=识别不可信，拒重建
                        // v3.4.23 修B: 新局白先是数学事实; 中盘逃逸重建轮次=轮我
                        // （ngEscapeMyTurn 语义: 对方长时间不动=断言轮我——出手
                        // 被拒会经 handleTapFailed 自纠回监视, 不死锁!!）
                        val nb = ChessSyncRules.buildBoard(rebuildPlac,
                            if (isFreshShape) true
                            else if (ngEscapeMyTurn) mySide == Side.WHITE
                            else extBoard.sideToMove == Side.WHITE)   // 普通中盘漂移: 保真原轮次
                        if (nb == null) return activeDelay()
                        extBoard = nb
                        lastAcceptedSig = plac   // 锚点记屏幕事实（含类型噪声），消化仍 occ 级
                    }
                }
                if (ngSyncCount >= ngSyncNeed) {
                    state = State.WATCH
                    val wasSyncFen = ngSyncCandidateFen
                    ngSyncCandidateFen = null; ngSyncCount = 0
                    // v3.4.23 修B续: 逃逸重建完成 → 轮次强制断言为我!!
                    // 覆盖两 case: ①中盘重建 buildBoard 传 true=白先（执黑时
                    // 又轮"对方"→死循环!!）②plac==extBoard 不重建（盘同步但
                    // 轮次错=自锁本尊!!）—— 两 case 都 reloadStm(mySide) 收口。
                    // 代价: 若真轮对方（对方长考>15s）——我方点击被 App 拒 →
                    // verify 5 连败 → handleTapFailed 兜底"疑似非我方行棋转
                    // 监视"（自纠错!!）——不死锁!!
                    if (ngEscapeMyTurn) {
                        ngEscapeMyTurn = false
                        // 标准开局形态=数学白先（此时断言轮我若执黑=违背事实——跳过!!）
                        if (extBoard.sideToMove != mySide &&
                            !isFreshPlacementSig(placementOf(extBoard.fen))) {
                            reloadStm(placementOf(extBoard.fen), mySide == Side.WHITE)
                        }
                    }
                    resetCandidateFen = null; resetCandidateCount = 0; resetMismatchCount = 0
                    // v3.0.1（用户 03:46"引擎就绪中永不出手"回归修复）：新局对齐完成
                    // 必须复位暂停旗——上一局分支3/7 的暂停（或换局残留）不清，
                    // maybeAutoPlay 被永挡 = "就绪中"死局（v3.0.0 引入的回归）。
                    pausedPendingResume = false
                    newGameArmed = isStandardOpeningShape(recF)
                    // v2.2.1（用户 18:24"开局引擎异常+执白却等对方"根治）：
                    // 对齐完成必须复位计谱+引擎 position——旧局 moveList 残留 →
                    // 引擎对不上新局 → bestmove 无效 → 不出手（"轮白却等对方"）
                    // + 引擎状态错乱 → 探活超时 → "引擎异常·自动重启中"。
                    moveListReset(placementOf(fenUsed))
                    mainHandler.post { tvStatus?.text = "新局已同步·轮${if (extBoard.sideToMove == mySide) "我方" else "对方"}" }
                    // 对齐完成立即进入正常循环：轮我出手（50ms 级响应）
                    if (extBoard.sideToMove == mySide) maybeAutoPlay()
                }
                return activeDelay()
            }

            // 局面同步（鹏飞式：摆位比对 + 闭环验证，详见 syncBoard 注释）
            // v3.1.0 回退（用户 06:14 指令："状态机回鹏飞的，全部按鹏飞的来"）：
            // v2.0 起 AC3.0 移植的 acSync 连环卡死 8 版未根治——用户证词"v1.x 鹏飞式
            // 一直没出错"。恢复 v1.13 syncBoard（追平循环+情况A/B/C/D+复合追平+双步
            // 一帧闭环+bigJump 换局——函数体从未删除，原样回接）。acSync 保留不再
            // 被调用（AC3.0 三元组登记在 performMove 中仍维护，syncBoard 只依赖
            // verifyUci/verifyDeadline/verifyPrePlacement——兼容）。
            val changed = syncBoard(fenUsed, recF.whiteAtBottom)
            if (changed != SyncResult.UNKNOWN) lastAcceptedPieces = recF.pieceCount
            // v1.14.0：接受帧哈希锚定（摆位与锚点一致=屏幕与状态机共识帧）
            if (changed != SyncResult.UNKNOWN && placementOf(fenUsed) == lastAcceptedSig) anchorHash = curFrameHash
            if (changed == SyncResult.RELOCATE_NEEDED) {
                boardRect = null
                return activeDelay()
            }
            // 思考跟随：对局每走一步，无限分析自动切换到新局面
            followThinkIfNeeded(fenUsed)
            if (changed == SyncResult.MY_MOVE_PENDING || changed == SyncResult.SYNCED_MY_TURN) {
                maybeAutoPlay()
                return activeDelay()
            } else if (changed == SyncResult.SYNCED_WAIT) {
                state = State.WATCH
                if (verifyUci != null) {
                    // 落子验证窗口内：快扫等待屏幕确认
                    // v2.2.6：附帧级诊断——屏幕摆位 vs 三基准对照，截图即破案
                    mainHandler.post {
                        tvStatus?.text = "验证落子 $verifyUci…"
                        tvPv?.text = syncDiag
                    }
                    return activeDelay()
                }
                mainHandler.post {
                    // v2.2.1：轮我方时绝不能显示"对方行棋中"（用户 18:24 实测执白轮白
                    // 却显示对方走——文案误导+实际=maybeAutoPlay 没完成，如实显示引擎态）
                    tvStatus?.text = if (extBoard.sideToMove == mySide)
                        "我方回合·引擎${if (engineSearching) "思考中" else "就绪中"}（我执${if (mySide == Side.WHITE) "白" else "黑"}）"
                    else if (extBoard.isKingAttacked)
                        "对方行棋中…（我方被将军）" else
                        "对方行棋中（我执${if (mySide == Side.WHITE) "白" else "黑"}·轮${if (extBoard.sideToMove == Side.WHITE) "白" else "黑"}）"
                    // v3.4.19: 「对方行棋中」也上屏帧级诊断（TA 截图一行破案!!）
                    tvPv?.text = syncDiag.take(46)
                }
                // v2.2.2（用户 18:41"就绪中就是不动"根治）：触发链断裂自愈——
                // 轮我方+引擎空闲+无待验证 = 重新触发出手。首触发失败（回调 null/
                // 校验失败置回 WATCH）后旧逻辑无任何重试入口 → 永久"就绪中"死等。
                // THINK 守卫（maybeAutoPlay 1952）防重入，每 idle 周期幂等重试。
                if (extBoard.sideToMove == mySide && !engineSearching &&
                    state == State.WATCH && verifyUci == null && thinkPhase == ThinkPhase.OFF && !recordMode
                ) {
                    maybeAutoPlay()
                }
                return idleDelay()
            }
            return activeDelay()
        } finally {
            bmp.recycle()
        }
    }

    private enum class SyncResult { SYNCED_WAIT, SYNCED_MY_TURN, MY_MOVE_PENDING, RELOCATE_NEEDED, UNKNOWN }

    /** 摆位镜像（上下颠倒的另一种视角解读）：rank 逐行倒序 */
    /** 摆位串 → 64 格字符数组（'.'=空）；非法返回 null —— v1.13.2 单源转发 */
    private fun expandPlacement(p: String): CharArray? = ChessSyncRules.expandPlacement(p)

    /** 两个摆位的差异格数（任一非法返回 99）—— v1.13.2 单源转发 */
    private fun diffCells(a: String, b: String): Int = ChessSyncRules.diffCells(a, b)

    /** v3.4.0 occupancy 级对方步匹配（PMC8321354 两阶段移植）：
     * 枚举全部合法步，occ(锚点+m)==occ(obs) 的候选中类型 diff 最小且独占者胜出。
     * 对方吃我子（1 格翻转+类型格）、易位（4 格翻转）、过路兵（3 格）全免疫；
     * 类型噪声永不挡门（只做平票决断）。歧义（tie）→ null 等下一帧。 */
    /** v3.4.24: chesslib Square → 摆位串下标（rank8在前!!）——起点幽灵签名用 */
    private fun sqPlacementIdx(sq: Square): Int =
        (7 - sq.rank.ordinal) * 8 + sq.file.ordinal

    /** v3.4.24: a/b 仅在 idx 一格不同且方向=a'0'/b'1'（屏幕多占=幽灵!!）——起点签名配套 */
    private fun occDiffOnly(a: String, b: String, idx: Int): Boolean {
        if (a.length != 64 || b.length != 64 || idx < 0 || idx > 63) return false
        var d = 0
        for (i in 0 until 64) {
            if (a[i] != b[i]) {
                d++
                if (i != idx || a[i] != '0' || b[i] != '1') return false
            }
        }
        return d == 1
    }

    private fun matchOneStepByOcc(board: Board, anchorSig: String, obsRaw: String): Move? {
        // v3.4.7：先净化 kq 互换（lichess 风格已知噪声签名，d8/e8 双格成对互换
        // 修回锚点内容）——occ 无差不受影响，类型门 bestTd 不再被 kq 噪声打爆。
        val obs = ChessSyncRules.sanitizeKqSwap(anchorSig, obsRaw)
        val obsOcc = ChessSyncRules.occMaskOf(obs)
        val anchorOcc = ChessSyncRules.occMaskOf(anchorSig)
        if (obsOcc.isEmpty() || anchorOcc.isEmpty()) return null
        if (obsOcc == anchorOcc) return null   // 无变化 → 没人走
        var bestM: Move? = null; var bestTd = 99; var tie = false
        for (m in board.legalMoves()) {
            val mb = try { Board().also { it.loadFromFen(board.fen); it.doMove(m) } } catch (_: Exception) { continue }
            val mo = ChessSyncRules.occMaskOf(placementOf(mb.fen))
            // v3.4.1（用户 17:24"对方下完不动了"根因）：对方 to 格高亮被持续读空
            // （occupancy 级丢子噪声——c6.jpg d6 高亮读空）→ 64 格全等永挂。
            // 放宽：occ diff ≤1 格且为【丢子方向】（预期有子/屏幕空——v1.9.0
            // 情况A同款语义）。反向（屏幕出现预期外的子=幽灵子）绝不容忍。
            if (mo.length == 64 && obsOcc.length == 64) {
                var d = 0; var dropoutOnly = true; var ghostIdx = -1
                for (i in 0 until 64) {
                    if (mo[i] != obsOcc[i]) {
                        d++
                        if (mo[i] != '1' || obsOcc[i] != '0') dropoutOnly = false
                        if (mo[i] == '0' && obsOcc[i] == '1') ghostIdx = i
                    }
                }
                // v3.4.24（TA 10/04「1.e4 c5 后永久卡死」终解·主刀!!）: 起点幽灵容忍。
                // 铁证: lichess 落子高亮把【起点格】持续误检为兵（c7幽灵+kq互换——
                // 设备 S=rnbkqbnr/pppppppp/8/2p!!）→ 幽灵方向被 L1408 continue 拒收
                // → 对方步永不消化 → 情况D→质量门(9兵=-50)拦死重建→「确认→拦→清零」
                // 无限循环 → TA 看到的"对方行棋中"永播!!
                // 数学排他签名: 合法走子后【起点格必然为空】（王车易位/过路兵两起点
                // 也全空!!）——「起点仍被占」不可能由真实棋造成=噪声独占（9/21 kq
                // 成对互换同款方法论!!）。双帧确认(pendingStepConfirm occ复现)天然
                // 防瞬态。容差仅 1 格且必须=该步起点!!
                val sourceGhost = d == 1 && ghostIdx == sqPlacementIdx(m.from)
                if (d > 1 || (!dropoutOnly && !sourceGhost)) continue
            } else if (mo != obsOcc) continue
            val td = diffCells(placementOf(mb.fen), obs)
            when {
                bestM == null -> { bestM = m; bestTd = td }
                td < bestTd -> { bestM = m; bestTd = td; tie = false }
                td == bestTd -> tie = true
                else -> {}
            }
        }
        // v3.4.2 刀3：类型分歧>2=识别不可信 → 不消化（等下帧），绝不带病吸收
        return if (bestM != null && !tie && bestTd <= 2) bestM else null
    }

    /** 方向性噪声判定（v1.9.0）：差异全部为"本地有子、屏幕为空"（识别丢子方向）且 ≤2 格。
     *  丢子=子还在屏幕上只是没识别到 → 容忍（以本地为准）；
     *  出现方向（本地空、屏幕有子）= 本地缺子/幽灵 → 不容忍，由调用方走追平/重置。 */
    private fun isDropoutOnly(localP: String, obsP: String): Boolean = ChessSyncRules.isDropoutOnly(localP, obsP)


    private fun mirrorPlacement(p: String): String = ChessSyncRules.mirrorPlacement(p)

    // ══════════ v1.13.0 变化格驱动匹配原语（鹏飞 NotByPass 核心的判据化）══════════

    /** 摆位变化格下标集合 —— v1.13.2 单源转发 */
    private fun diffPositions(a: String, b: String): Set<Int> = ChessSyncRules.diffPositions(a, b)

    /** 单步 diff 匹配 —— v1.13.2 单源转发（真代码在 ChessSyncRules，JVM 测试直测） */
    private fun matchOneStepByDiff(board: Board, lastSig: String, sig: String): Pair<com.github.bhlangonijr.chesslib.move.Move, String>? = ChessSyncRules.matchOneStepByDiff(board, lastSig, sig)

    /** 双步消化设计说明（v1.13.0）：我方步+对方步同帧到达时，D=4 格；单步匹配会以
     *  "一步+2 格噪声"方式匹配第一步（如 e2e4），锚点更新为走后事实（不含对方步），
     *  下一帧剩余 2 格再匹配对方步——**双步分两帧自然消化**（每帧 ~80ms，两帧闭环）。
     *  比旧 findTwoPlyTransition 全盘比对更稳：识别噪声不再使两步组合匹配整体失败。
     *  故无需独立的双步匹配器。 */

    /** UCI → Move —— v1.13.2 单源转发（从 legalMatches 取完整对象，防 NPE 半改棋盘） */
    private fun moveFromUci(board: Board, uci: String): com.github.bhlangonijr.chesslib.move.Move? = ChessSyncRules.moveFromUci(board, uci)

    /** 把识别到的 FEN 同步进 extBoard。返回当前是否轮到我方。
     *  鹏飞式设计：屏幕是唯一事实源——
     *  · 只比"棋子摆位"（FEN 第 1 段），轮次由本地跟踪（图片推断不出轮次）
     *  · 摆位差一步合法棋（不分颜色）= 那步棋发生了，立即应用
     *  · 视角不确定时（两种解读）用"哪个能被本地合法追平"裁决（v1.7.6，与升变门控同一哲学）
     *  · 我方点击后必须观察到摆位变化才算成功（闭环验证），没变=点击失败立即重试
     *  · 摆位差两步棋 = 漏识别了一步（我方点击生效帧被跳过+对方已回应）
     *  · 完全对不上：2 帧确认即重置（局面不一致立马发现）；8 帧兜底防抖动死锁
     *  @param recWAB 本帧 inferFen 实际使用的视角解读（whiteAtBottom）
     */

    // ═══ v2.0 AC3.0 决策树（imageToBoard 移植——确定性三元组比较）════
    // 迁移说明：本函数替代旧 syncBoard 的全部十四个机制。基准（ClickBefore/
    // After/oppWait）在动作瞬间写死；diff≠合法步的帧天然被拒——识别噪声产生
    // 的"幻觉步"必须恰好构成 chesslib 合法步且与基准差 2/4 格，概率≈0。
    private fun acSync(fenNow: String, recWAB: Boolean): SyncResult {
        val screen = placementOf(fenNow)
        if (screen.isEmpty() || piecesOf(screen) < 2) return SyncResult.SYNCED_WAIT

        // v3.0.0：失联计数统一由分支7管理（AC3.0 actionNullCount 唯一计数源），
        // 旧"提前计数+NG 重置"段删除——双轨计数是 v2.x 多个死锁的温床。

        // ── v3.0.0 AC3.0 决策树照抄（ChessService.imageToBoard 330-420 行）──
        // 架构修正（用户 9/15 03:21"状态机移植错了"）：AC3.0 原版【无状态门】——
        // 每帧按固定顺序跑全部分支，状态仅参与分支3的暂停判定。旧 v2.x 把分支
        // 装进 when(effState) 盒子：状态错乱（verify 挂着/A 空串/重置残留）时
        // 分支全锁死 = "验证落子"永卡的架构根源。照抄 = 无门 + 固定序：
        //   1 isSame(screen,After)→确认  2 Before差我步→接受  3 Before差对方步
        //   +轮我→暂停  4 isSame(screen,Before)→幂等重发  5 局面没变→跳过
        //   6 After差对方一步→双步连消  7 失联5帧→暂停（toggleStart 同款）
        if (state == State.NEW_GAME_SYNC) return SyncResult.SYNCED_WAIT   // tick 的 ngSync 块单轨处理

        // v2.2.6 帧级诊断：屏幕 vs 三基准对照，截图即破案
        syncDiag = "S=${screen.take(20)}|A=${clickAfterSig.take(20)}|B=${clickBeforeSig.take(20)}|" +
                "W=${oppWaitSig.take(12)}|st=$state|v=${verifyUci != null}|" +
                "bb=$badFrames|pc=${piecesOf(screen)}"

        // ── 分支1（AC3.0:337 isSame(boardFromImage, ClickAfter) → 我步生效确认）──
        // 无条件：不依赖任何状态（旧版要求 effState==PLAY——状态错乱时永卡）
        if (clickAfterSig.isNotEmpty() && screen == clickAfterSig) {
            val alreadyApplied = placementOf(extBoard.fen) != clickBeforeSig
            verifyClosed()
            if (!alreadyApplied) {
                val mv1 = moveFromUci(extBoard, clickAfterUci)
                if (mv1 != null) {
                    try { extBoard.doMove(mv1) } catch (_: Throwable) {}
                }
                // v3.4.8 毒库根治（9/25 04:07 开局送象回归案）：此处曾无 recordMode
                // 门——自动连线每步入谱。当年书乱序撞出的 Ba6 等鬼步被持久化进
                // MyBook，此后同局面序列命中即 0ms 重放（MyBook 优先级>开局书）。
                // 用户 9/18"是不是有缓存"直觉实锤。自动对局绝不入谱——录谱只在
                // 记录模式（用户亲手录的习题谱才可信）。
                try {
                    if (recordMode) {
                        if (recordStartPlacement == null) { recordStartPlacement = clickBeforeSig; recordStartWhite = mySide == Side.WHITE }
                        recordMoves.add(clickAfterUci); recordMoveCount++; MyBook.flush()
                    }
                } catch (_: Throwable) {}
            }
            lastMoveDesc = clickAfterUci
            lastAcceptedSig = screen
            state = State.WATCH
            oppWaitSig = screen
            mainHandler.post { tvStatus?.text = "落子确认 $lastMoveDesc" }
            return SyncResult.SYNCED_WAIT
        }

        val mvBefore = if (clickBeforeSig.isNotEmpty()) findStepFromSig(clickBeforeSig, screen) else null

        // ── 分支2（AC3.0:345 ClickBefore 差我方一步且≠After → 接受实际走子）──
        if (mvBefore != null && mvBefore.mover == mySide && screen != clickAfterSig) {
            // 动画帧双帧防线（E2E 实测：我方点击动画兵悬 e3 构成合法白步——瞬态）
            if (pendingUserSig != screen) {
                pendingUserSig = screen
                mainHandler.post { tvStatus?.text = "我方实际走子确认中（${mvBefore.uci}）" }
                return SyncResult.SYNCED_WAIT
            }
            pendingUserSig = ""
            applyStep(screen, mvBefore)
            verifyClosed()
            state = State.WATCH
            oppWaitSig = screen
            mainHandler.post { tvStatus?.text = "接受实际走子 $lastMoveDesc" }
            return SyncResult.SYNCED_WAIT
        }

        // ── 分支3（AC3.0:349 ClickBefore 差对方一步 + 我方行棋窗口 → 暂停）──
        // 我方行棋窗口 = verify 挂着（OUR_CLICK）或 WATCH 轮我（OUR_TURN）。
        // 对方不可能在我回合走（对弈 App 强制轮流）→ 识别错乱 → 停（确定性优先）
        if (mvBefore != null && mvBefore.mover != mySide &&
            (verifyUci != null || (state == State.WATCH && extBoard.sideToMove == mySide))
        ) {
            verifyClosed()
            pausedPendingResume = true   // maybeAutoPlay 守卫（AC3.0 toggleStart 停）
            mainHandler.post { tvStatus?.text = "对方在我方回合出子·已暂停（识别异常，点「我方回合」恢复）" }
            return SyncResult.SYNCED_WAIT
        }

        // ── 分支4（AC3.0:359 isSame(screen, ClickBefore) → autoClick 幂等重发）──
        if (verifyUci != null && clickBeforeSig.isNotEmpty() && screen == clickBeforeSig) {
            val uci = verifyUci
            if (uci != null) {
                tapAttempts++
                verifyDeadline = android.os.SystemClock.elapsedRealtime() + 3500
                mainHandler.post { performMove(uci) }
            }
            return SyncResult.SYNCED_WAIT
        }

        // ── 分支5（AC3.0:366 局面没变化 → 暂不处理）──
        // 屏==本地盘 = 无事发生（对方行棋中静止帧/已追平帧）
        if (screen == placementOf(extBoard.fen) || screen == oppWaitSig) {
            oppWaitSig = screen
            return SyncResult.SYNCED_WAIT
        }

        // ── 分支6（AC3.0:370 getMoveByBoard(ClickAfter, screen) 对方一步 → 双步连消）──
        // ClickAfter 基准挂到下次我方点击才更新（AC3.0 同款）——WATCH 态对方走
        // 子（我步已确认）与 verify 窗口对方秒回（双步）统一走这里。
        if (clickAfterSig.isNotEmpty()) {
            val mvAfter = findStepFromSig(clickAfterSig, screen)
            if (mvAfter != null && mvAfter.mover != mySide) {
                verifyClosed()
                pausedPendingResume = false
                // 我步应用（防重复：extBoard 已含我步时跳过）
                val alreadyApplied = placementOf(extBoard.fen) != clickBeforeSig
                if (!alreadyApplied) {
                    val myMv = moveFromUci(extBoard, clickAfterUci)
                    if (myMv != null) {
                        try { extBoard.doMove(myMv) } catch (_: Throwable) {}
                        lastMoveDesc = clickAfterUci
                        lastAcceptedSig = clickAfterSig
                    }
                    try {
                        if (recordStartPlacement == null) { recordStartPlacement = clickBeforeSig; recordStartWhite = mySide == Side.WHITE }
                        recordMoves.add(clickAfterUci); recordMoveCount++
                    } catch (_: Throwable) {}
                }
                // 对方步应用
                applyStep(screen, mvAfter)
                state = State.WATCH
                oppWaitSig = screen
                lastSentMove = null
                mainHandler.post { tvStatus?.text = "对方 ${mvAfter.uci} 已应·轮我方" }
                return SyncResult.MY_MOVE_PENDING
            }
        }

        // ── 分支7（AC3.0:398 actionNullCount≥5 → toggleStart 停）──
        // 识别不到走棋招法：停自动走子（等用户点「我方回合」重同步），不猜不追
        badFrames++
        if (badFrames >= 5) {
            badFrames = 0
            verifyClosed()
            pausedPendingResume = true
            mainHandler.post { tvStatus?.text = "识别不到走棋招法·已暂停（点「我方回合」恢复）" }
        }
        return SyncResult.SYNCED_WAIT
    }

    /** 单步搜索（AC3.0 getMoveByBoard 国际象棋版）：当前 extBoard 任意一方
     *  走一步后的摆位==target → 返回该步。diff 不构成合法步 → null（不猜）。 */
    private class StepHit(val uci: String, val mover: Side, val afterSig: String)

    /** v2.2.3 双步连消用：以 baseSig 建盘（两种走子方都试），搜单步到 target。 */
    private fun findStepFromSig(baseSig: String, target: String): StepHit? {
        if (baseSig.isEmpty()) return null
        val b1 = ChessSyncRules.buildBoard(baseSig, true)
        if (b1 != null) { findStmStep(b1, target)?.let { return it } }
        val b2 = ChessSyncRules.buildBoard(baseSig, false)
        if (b2 != null) { findStmStep(b2, target)?.let { return it } }
        return null
    }

    private fun findAnyStepTo(target: String): StepHit? {
        // 当前走子方先试
        var hit = findStmStep(extBoard, target)
        if (hit != null) return hit
        // 另一方（翻转轮——屏幕可能领先本地一步）
        val flipped = try {
            val b = Board(); b.loadFromFen(placementOf(extBoard.fen) + " " + (if (extBoard.sideToMove == Side.WHITE) "b" else "w") + " KQkq - 0 1")
            b
        } catch (_: Throwable) { null }
        if (flipped != null) hit = findStmStep(flipped, target)
        return hit
    }

    private fun findStmStep(board: Board, target: String): StepHit? {
        val moves = try { board.legalMoves() } catch (_: Throwable) { return null }
        for (m in moves) {
            try {
                val b2 = Board(); b2.loadFromFen(board.fen)
                b2.doMove(m)
                if (placementOf(b2.fen) == target) {
                    val uci = m.from.name.lowercase() + m.to.name.lowercase() +
                            (m.promotion?.let { it.fenSymbol.lowercase() } ?: "")
                    return StepHit(uci, board.sideToMove, target)
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 应用一步到 extBoard+moveList（棋盘事实驱动，与屏幕共识） */
    private fun applyStep(screen: String, hit: StepHit) {
        try {
            val m = moveFromUci(extBoard, hit.uci)
            lastMoveDesc = hit.uci
            if (m != null) extBoard.doMove(m)
        } catch (_: Throwable) {
            // 翻转轮场景（屏幕领先）：重建
            val b = ChessSyncRules.buildBoard(ChessSyncRules.purgeKqOf(screen), hit.mover == Side.WHITE)
            if (b != null) extBoard = b
        }
        try {
            if (recordMode) {   // v3.4.8：同刀1——自动对局不入谱（毒库根治）
                if (recordStartPlacement == null) {
                    recordStartPlacement = screen
                    recordStartWhite = hit.mover == Side.WHITE
                }
                recordMoves.add(hit.uci)
                recordMoveCount++
                MyBook.flush()
            }
        } catch (_: Throwable) {}
        lastAcceptedSig = screen
    }

    private fun verifyClosed() {
        verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
        promoPhase = PromoPhase.OFF
    }

    /** v2.0 新局计谱复位（引擎 position 同步重置） */
    private fun moveListReset(screen: String) {
        try {
            recordMoves.clear(); recordMoveCount = 0
            recordStartPlacement = null
            lastSentMove = null; lastMoveDesc = ""
            if (!engineSearching) engine?.newGame()   // v2.1.1：搜索中不重置（stop 冲突=重启循环根源）
        } catch (_: Throwable) {}
    }

    private var lastFenNow: String = ""   // v1.13.7：当前帧 FEN 缓存（哈希等价确认暂存用）

    /** v3.2.2 整图高亮归一化（大模型 YOLO 路径）+ 主题门：全图米黄像素占比
     *  >10% = 米褐主题标准底色（浅格≈218,214,188）→ 禁用（c3.jpg 破案：v3.2.1
     *  把米褐一半棋盘染坏）。占比 ≤10% 且 >0.3% = 浅蓝灰主题带真高亮 → 归一化。 */
    private fun normalizeHighlightBitmap(bmp: Bitmap) {
        try {
            val w = bmp.width; val h = bmp.height
            if (w <= 0 || h <= 0) return
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            // 第一遍：米黄像素统计（主题门）
            var beige = 0
            for (i in px.indices) {
                val r = px[i] shr 16 and 0xFF; val g = px[i] shr 8 and 0xFF; val b = px[i] and 0xFF
                if (r in 204..232 && g in 200..228 && b in 174..202 && r > b) beige++
            }
            val ratio = beige.toDouble() / px.size
            if (ratio > 0.10) return   // 米褐主题：浅格即米黄，禁用
            if (ratio < 0.003) return  // 无高亮，跳过
            // 第二遍：归一化（浅蓝灰主题真高亮）
            var changed = false
            for (i in px.indices) {
                val r = px[i] shr 16 and 0xFF; val g = px[i] shr 8 and 0xFF; val b = px[i] and 0xFF
                val isHlLight = r in 202..234 && g in 198..230 && b in 172..204 && r > b
                val isHlDark = r in 108..140 && g in 119..151 && b in 144..176 && b > r
                if (isHlLight || isHlDark) {
                    val nr: Int; val ng: Int; val nb: Int
                    if (isHlLight) { nr = r - 35; ng = g + 20; nb = b + 31 } else { nr = r + 31; ng = g + 26; nb = b + 19 }
                    px[i] = (px[i] and 0xFF000000.toInt()) or
                            (nr.coerceIn(0, 255) shl 16) or (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
                    changed = true
                }
            }
            if (changed) bmp.setPixels(px, 0, w, 0, 0, w, h)
        } catch (_: Throwable) { /* 归一化失败=原图识别（不比之前差） */ }
    }

    private fun syncBoard(fenNow: String, recWAB: Boolean): SyncResult {
        lastFenNow = fenNow
        // v3.4.23 修C: verifyUci 空串防御!!
        // L1744 verify 处理块要求 isNotEmpty——空串被跳过; 情况A L1882 只查
        // != null——空串照样进!! 差 1 格+deadline 到 → d≤2 延期死循环（无出口!!
        // 「我步已在屏·等对方步消化(差N格)」永播——Minis 诊断的「消化等待」本尊!!）
        // normalize: 空串=未验证 → 归位 null（一切 null 检查自然对齐!!）
        if (verifyUci != null && verifyUci!!.isEmpty()) {
            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
        }
        var obs = placementOf(fenNow)
        var local = placementOf(extBoard.fen)
        val now = android.os.SystemClock.elapsedRealtime()
        // v2.2.6 帧级诊断（回退版保留）：屏幕 vs 本地盘对照，截图即破案
        syncDiag = "S=${obs.take(20)}|L=${local.take(20)}|" +
                "W=${oppWaitSig.take(12)}|st=$state|v=${verifyUci != null}|" +
                "bb=$badFrames|pc=${piecesOf(obs)}"

        // ── 视角追平自愈（v1.7.6 核心）──
        // 视角启发式（白子集中半区）在"对手先走一步后点连线""白子过河"等场景必错：
        // 镜像摆位 → 判边判错 → 引擎点对方棋子 → 点击无效 → 验证重试 → 死循环
        // （用户实测全链路）。根治：正常解读追不上时试镜像解读——
        // 哪个能被本地局面接受（相等或一步合法追平）就信哪个，并翻视角锁。
        if (obs != local && findMoveToPlacement(extBoard, obs) == null) {
            val obsM = mirrorPlacement(obs)
            if (obsM == local || findMoveToPlacement(extBoard, obsM) != null) {
                obs = obsM
                orientLock = !recWAB   // 翻锁：下一帧起 tick 按正确视角重排
                // v1.13.0：视角翻转 → 锚点同步镜像（否则 diff 基准坐标系错乱）
                lastAcceptedSig = mirrorPlacement(lastAcceptedSig)
                mainHandler.post { tvStatus?.text = "视角自愈（追平验证）" }
            }
        }

        // ═══ v1.13.0 变化格驱动状态机（鹏飞 _lastUsedBoardImage + NotByPass 复刻）═══
        // 首帧/重连：以屏幕直接建立锚点（tick 的动画过滤+帧哈希保证此处是稳态帧）
        if (lastAcceptedSig.isEmpty()) {
            lastAcceptedSig = obs
            anchorHash = curFrameHash   // v1.14.0：锚定帧哈希
        }
        // ── v1.14.0 幽灵步物理防线：画面没动=没有步 ──
        // 识别 diff 声称有步但帧哈希==锚定哈希（棋盘物理静止）→ 必是识别幻觉。
        // 拒绝一切匹配/追平（画面静止期识别噪声在大/小模型上都存在——用户实测
        // 双走"对方还没下它就开始走"的真正入口）。例外：摆位一致（D=0 静止帧）。
        if (curFrameHash != null && anchorHash != null && curFrameHash == anchorHash && obs != lastAcceptedSig) {
            resetCandidateFen = null; resetCandidateCount = 0   // 幻觉帧不计重置（防止连环幻觉触发重建）
            return SyncResult.SYNCED_WAIT   // 物理事实优先：等画面真变化
        }

        // ═══ v3.4.0 verify occupancy 模式匹配（外部 AI 会诊+PMC8321354 论文综合方案）═══
// 三版失败链复盘：全等验证（类型噪声→永挂）/噪声容忍（误解释成伪对方步→三连走）
// /全等+丢帧（3 格 diff 拒收→永挂）。铁证（c5.jpg）：识别盘 e8 黑王读成白王 K
// ——类型错读不可消灭，判据必须数学免疫类型。
// PMC 论文两阶段思想的移植：occupancy（有子/无子）错误率 0.23%/格，类型分类
// 错误率高一个数量级——【判据只看 occupancy，类型只用于平票决断，绝不作门槛】。
//   ① occ(obs)==occ(点击后盘) → 我步已生效 → 闭环（本地推演为准，类型不污染）
//   ② occ(obs)==occ(点击前盘) → 我步未生效 → 幂等重发（屏幕证明没走）
//   ③ 枚举对方合法步 m：occ(点击后盘+m)==occ(obs) → 我步+对方步双闭环
//      （对方吃我子=1 格翻转+类型格变化、易位=4 格翻转、过路兵=3 格——全免疫）
//      平票（≥2 个 m 的 occ 模式相同）→ 类型 diff 最小者独占才消化，否则等下帧
//   ④ 无匹配 → 丢帧；连续 8 帧 → 暂停（不确定就不动）
if (verifyUci != null && verifyUci!!.isNotEmpty() && clickAfterSig.isNotEmpty()) {
    val beforeSig0 = clickBeforeSig
    val afterSig0 = clickAfterSig
    val obsOcc = ChessSyncRules.occMaskOf(obs)
    val afterOcc = ChessSyncRules.occMaskOf(afterSig0)
    val beforeOcc = ChessSyncRules.occMaskOf(beforeSig0)
    // v3.4.1 丢子方向≤1格容忍（高亮格读空——c6 病根在 verify 同样存在）
    fun occDropoutOk(a: String, b: String): Boolean {
        if (a.length != 64 || b.length != 64) return a == b
        var d = 0
        for (i in 0 until 64) {
            if (a[i] != b[i]) {
                d++
                if (a[i] != '1' || b[i] != '0') return false   // 反向=幽灵子，绝不容忍
            }
        }
        return d <= 1
    }
    if (obsOcc.isNotEmpty() && afterOcc.isNotEmpty()) {
        // v3.4.25（TA 10/07「走完第1步就不走了·越修越差」终解!!）: ①加【我的起点
        // 幽灵容忍】——lichess 落子后高亮我步 from/to 格,起点格被误检为子（from+to
        // 双占=棋子复制!!）→旧①全等永败→verifyLost×8→PAUSED 手动死局（仿真7帧
        // 死复现!!）。签名与 v3.4.24 刀1 同款数学排他: 合法走子后【我的起点必空】
        // ——"起点仍被占"不可能由真实棋造成=噪声独占!!（dropout 方向旧代码已容忍）
        val verifyFromIdx = try { sqPlacementIdx(Square.valueOf(verifyUci!!.take(2))) } catch (_: Throwable) { -1 }
        val myFromGhost = obsOcc.length == 64 && afterOcc.length == 64 && verifyFromIdx >= 0 &&
            occDiffOnly(afterOcc, obsOcc, verifyFromIdx)
        if (obsOcc == afterOcc || occDropoutOk(afterOcc, obsOcc) || myFromGhost) {
            // ① occupancy 全等=我步已生效（易位/过路兵/升变全在推演盘里自动涵盖）
            try { extBoard.doMove(moveFromUci(extBoard, verifyUci!!) ?: return SyncResult.UNKNOWN) } catch (_: Exception) {}
            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
            promoPhase = PromoPhase.OFF; verifyLostCount = 0
            myMovesVerified++
            lastAcceptedSig = afterSig0
            anchorHash = curFrameHash
            return SyncResult.SYNCED_WAIT   // 轮对方
        }
        if (beforeOcc.isNotEmpty() && obsOcc == beforeOcc) {
            // ② 我步未生效 → 幂等重发（700ms 防抖——对方 App 响应延迟）
            verifyLostCount = 0
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastTapAt > 700) {
                lastTapAt = nowMs
                mainHandler.post {
                    tvStatus?.text = "落子未生效·重发"
                    performMove(verifyUci ?: return@post)
                }
            }
            return SyncResult.SYNCED_WAIT
        }
        // ③ 枚举对方合法步：occ(点击后盘+m)==occ(obs) 的 m 候选集
        val afterBoard = try {
            Board().also { it.loadFromFen(extBoard.fen); it.doMove(moveFromUci(it, verifyUci!!) ?: return SyncResult.UNKNOWN) }
        } catch (_: Exception) { null }
        if (afterBoard != null) {
            var bestM: Move? = null; var bestTypeDiff = 99; var tie = false
            // v3.4.24 刀2: ③通道 obs 先过 kq 净化（锚点=clickAfterSig!!）——
            // 旧代码用原始obs算类型差: kq互换+起点幽灵=3格>2 → 双步永拒 →
            // 秒回窗口(lichess AI<1s!!)必踩 → verifyLost→PAUSED 死锁!!
            val obsSan3 = ChessSyncRules.sanitizeKqSwap(clickAfterSig, obs)
            for (m in afterBoard.legalMoves()) {
                val mb = try { Board().also { it.loadFromFen(afterBoard.fen); it.doMove(m) } } catch (_: Exception) { continue }
                val mo = ChessSyncRules.occMaskOf(placementOf(mb.fen))
                // v3.4.24 刀2: 起点幽灵容忍（同 matchOneStepByOcc——数学排他签名!!）
                val sourceGhost3 = mo.length == 64 && obsOcc.length == 64 &&
                    occDiffOnly(mo, obsOcc, sqPlacementIdx(m.from))
                if (mo == obsOcc || occDropoutOk(mo, obsOcc) || sourceGhost3) {
                    val td = diffCells(placementOf(mb.fen), obsSan3)
                    when {
                        bestM == null -> { bestM = m; bestTypeDiff = td }
                        td < bestTypeDiff -> { bestM = m; bestTypeDiff = td; tie = false }
                        td == bestTypeDiff -> tie = true
                        else -> {}
                    }
                }
            }
            if (bestM != null && !tie && bestTypeDiff <= 2) {
                // v3.4.2 刀3：bestTypeDiff>2=本地与屏幕类型分歧过大（lichess 风格
                // 错读爆炸）→ 不可消化，等下帧——污染步消化=引擎按错盘出招=送子。
                try { extBoard.doMove(moveFromUci(extBoard, verifyUci!!) ?: return SyncResult.UNKNOWN) } catch (_: Exception) {}
                try {
                    val mover2 = extBoard.getPiece(bestM!!.from).pieceSide
                    if (mover2 != extBoard.sideToMove) {
                        if (!reloadStm(placementOf(extBoard.fen), mover2 == Side.WHITE)) return SyncResult.UNKNOWN
                    }
                    extBoard.doMove(bestM!!)
                } catch (_: Exception) { return SyncResult.UNKNOWN }
                lastMoveDesc = bestM!!.from.name.lowercase() + bestM!!.to.name.lowercase() +
                        (bestM!!.promotion?.let { it.fenSymbol.lowercase() } ?: "")
                val pendRec = verifyUci
                verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                promoPhase = PromoPhase.OFF; verifyLostCount = 0
                myMovesVerified++
                lastAcceptedSig = placementOf(extBoard.fen)
                anchorHash = curFrameHash
                if (recordMode && pendRec != null) {
                    recordMoves.add(pendRec.lowercase()); recordMoves.add(lastMoveDesc)
                    recordMoveCount += 2
                }
                return if (extBoard.sideToMove == mySide) SyncResult.SYNCED_MY_TURN else SyncResult.SYNCED_WAIT
            }
            // tie=歧义 → 等下一帧（对方步是稳定事实，下帧识别噪声变化会打破平票）
        }
    }
    // ④ 无匹配（识别瞬态/动画中途）→ 丢帧；连续 8 帧失联 → 暂停
    verifyLostCount++
    if (verifyLostCount >= 8) {
        verifyLostCount = 0
        verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
        state = State.PAUSED_VERIFY_LOST
        mainHandler.post { tvStatus?.text = "验证失联·已暂停（点「我方回合」以屏幕重建）" }
    }
    return SyncResult.SYNCED_WAIT   // 丢帧——绝不发明解释
}

        // ── 情况A：摆位一致（v1.9.0 方向性噪声容忍）──
        // 识别丢子（本地有子、屏幕为空，≤2 格）= 纯噪声 → 容忍（以本地为准，子还在，
        // 引擎照常可走）；屏幕出现本地没有的子 = 本地棋盘缺子（幽灵棋子来源）→ 绝不容忍，
        // 走追平/重置路径以屏幕纠正。旧版不区分方向 → "重置丢子→子永远缺失→棋子动不了"。
        if (obs == local || isDropoutOnly(local, obs)) {
            // 无敌兜底（v1.7.3）：本地是标准开局时，轮次【必然】是白——数学事实，
            // 不依赖任何跟踪/探测。若 extBoard 轮次被历史状态污染成黑，强制纠正。
            if ((local == "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR" ||
                 local == "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr") &&
                extBoard.sideToMove != Side.WHITE
            ) {
                reloadStm(local, true)
            }
            // 新对局判边（v1.7.2 关键修复）：开局形态下按视角立即定执边。
            // 此前本分支无判边——上一局执黑的 mySide 残留到新局（执白），
            // 首帧直接判"轮对方"死等（用户实测"刚点连线就等待对方走子"）。
            if (sideAuto) {
                val freshWhite = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
                val freshBlack = "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr"
                val newSide: Side? = when (obs) {
                    freshWhite -> Side.WHITE   // 白在底 = 我执白
                    freshBlack -> Side.BLACK   // 黑在底 = 我执黑
                    else -> null
                }
                if (newSide != null && newSide != mySide) {
                    mySide = newSide
                    myMovesVerified = 0
                    orientLock = if (obs == freshWhite) true else false
                    mainHandler.post {
                        renderSideSelection()
                        tvStatus?.text = "新对局 · 我方执${if (newSide == Side.WHITE) "白" else "黑"}"
                    }
                }
            }
            resetCandidateFen = null; resetCandidateCount = 0; resetMismatchCount = 0
            // v1.13.0：静止接受 → 锚点=棋盘事实（不吸收屏幕噪声）
            lastAcceptedSig = local
            // 落子闭环验证：点击后摆位没变 = 点击没生效
            if (verifyUci != null) {
                if (now > verifyDeadline) {
                    // ── v3.4.18 重发守卫（TA 9/27 实测「点了又重复点」根治!!）──
                    // 旧链: verify 超时（对方秒回+平票裁决/双帧确认消化慢!!）→
                    // handleTapFailed 盲目重发【已走过的步】→ lichess 拒绝 →
                    // 无效点击×3 → 重新定位 → 磨几轮才恢复 = TA 看到的
                    // 「点了一下又重复点一下然后才继续下」!!
                    // 守卫: 重发前先验【最近识别帧已含我步效果】——与
                    // clickAfterSig 的 occ 差 ≤2 格（我步已在屏+可能对方步!!）
                    // = 点击实际已生效 → 绝不重发——延长窗口等消化链
                    // （平票裁决/双帧确认本来就需要时间!!）。
                    val recentOcc = lastRec?.let { ChessSyncRules.occMaskOf(BoardDetector.toPlacement(it)) } ?: ""
                    val afterOcc = if (clickAfterSig.isNotEmpty()) ChessSyncRules.occMaskOf(clickAfterSig) else ""
                    if (recentOcc.length == 64 && afterOcc.length == 64) {
                        var d = 0
                        for (i in 0 until 64) if (recentOcc[i] != afterOcc[i]) d++
                        if (d <= 2) {
                            verifyDeadline = android.os.SystemClock.elapsedRealtime() + 3000
                            mainHandler.post { tvStatus?.text = "我步已在屏·等对方步消化(差" + d + "格)…" }
                            return SyncResult.SYNCED_WAIT
                        }
                    }
                    return handleTapFailed()
                }
                return SyncResult.SYNCED_WAIT   // 验证窗口内，继续观察
            }
            if (extBoard.sideToMove == mySide) {
                waitSince = 0L
                return SyncResult.SYNCED_MY_TURN
            }
            // 等对方行棋（画面静止时的轮次探测在 tick 帧缓存分支统一处理，v1.7.6 去重）
            return SyncResult.SYNCED_WAIT
        }

        waitSince = 0L

        // ── 情况B（v1.13.0 重写核心）：变化格 diff 匹配 ──
        // 旧版 findMoveToPlacement【全盘比对】→ 识别噪声 1 格就失败 → 掉进重置通道
        // → 重置到含噪局面 → 引擎走出非法步 → 点击无效 → "验证+重同步+不下"死循环
        // （用户 9/7-9/8 实测三连症状的完整根因链）。
        // 新判据（鹏飞 NotByPassByChangedPositionsBetweenLastUsedBoardImage 复刻）：
        // 屏幕与【上次接受锚点】的变化格集合 D，能否被一步合法棋的影响格解释（对称差
        // ≤2 格=识别噪声/丢子容忍）。匹配成功：
        //  · 接受该步并以【走后摆位】（棋盘事实）更新锚点——噪声格被事实覆盖，
        //    绝不吸收进状态机；
        //  · 双步同帧（我方步+对方步）以"一步+2噪声"方式匹配第一步，下一帧消化
        //    剩余两格——分帧闭环，不依赖脆弱的两步组合全盘搜索。
        // v3.3.0 撤锚点引导（用户 07:13"照抄不要补丁"）：AC3.0 getMoveByBoard
        // 纯净语义——diff 恰好 2 格+合法步才吸收，其余丢帧等下一帧（高频下
        // 干净帧自然到来）。容忍=误判面（G6 三连走教训）。
        val matched = if (verifyUci == null || verifyUci!!.isEmpty()) {
            matchOneStepByOcc(extBoard, lastAcceptedSig, obs)   // v3.4.0 occupancy 级
        } else null   // verify 在途走 ClickAfter 快速通道
        // ── v1.13.3 步应用双帧确认（幽灵步第二道防线，鹏飞 NotByPass 帧级语义）──
        // 单帧噪声（类型抖动漏网/瞬时误检）即使过了匹配也必须下一帧复现才应用。
        // 例外：verify 在途（我方点击是物理先验，单帧即闭环——慢一帧会让用户感觉
        // 点击没反应）。代价 ~80ms/步，远比"幽灵步→轮次翻转→乱走"便宜。
        // v1.13.5 死锁修复（用户 9/10 00:54"不识别也不下"根因）：候选帧未复现
        // 且当前帧无匹配 → 【必须清确认态】——旧版 pending 挂死导致 hashSkipLimit
        // 永久=0（强制识别却永无确认）+永不出手。清后噪声走异常通道自愈
        // （累计→重置→NewGameSync 重建），节流恢复。
        // v3.4.0：matched 从 Pair<Move,String> 改为 Move?——走后摆位（干净事实）现算
        val matchedAfterSig = matched?.let { m ->
            try { val b = Board(); b.loadFromFen(extBoard.fen); b.doMove(m); placementOf(b.fen) } catch (_: Exception) { null }
        }
        if (matched != null && matchedAfterSig != null && verifyUci == null && pendingStepConfirmObs != obs) {
            // v3.4.19（TA 9/29 01:12「对方下完了还显示对方行棋中」根治!!）:
            // 旧判据 pendingAfter==matchedAfter 的【类型级全等】——真机走子高亮动画期
            // 噪声格抖动 → after 每帧不同 → 复现永不成对 → 永久暂存 = 死等!!
            // 新判据: 【occ 级复现 + 同一步】（occupancy 是唯一闭环层——SOUL 铁律!!）
            val occEq = pendingStepConfirmAfter != null &&
                ChessSyncRules.occMaskOf(pendingStepConfirmAfter!!) == ChessSyncRules.occMaskOf(matchedAfterSig)
            val sameMove = pendingStepConfirmAfter != null && pendingStepConfirmAfter != matchedAfterSig // 仅类型差
            if (occEq || pendingStepConfirmAfter == matchedAfterSig) {
                // 第二帧同棋（occ 复现/同 move）→ 放行（下方应用）
                pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
            } else if (pendingStepConfirmAfter != null &&
                ChessSyncRules.occMaskOf(pendingStepConfirmAfter!!).length == 64 &&
                diffCells(pendingStepConfirmAfter!!, matchedAfterSig) <= 2) {
                // v3.4.19 兜底: 类型 diff≤2（识别噪声级）——也放行（occ 已过门!!）
                pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
            } else {
                // 首帧：暂存待确认，下一帧复现（occ 级）才应用
                pendingStepConfirmObs = obs
                pendingStepConfirmAfter = matchedAfterSig
                return SyncResult.SYNCED_WAIT   // 等一帧确认（不出手不动状态）
            }
        } else if (matched == null && pendingStepConfirmObs != null) {
            // 候选未复现且当前无匹配 → 清确认（下帧重新评估；连续异常走重置自愈）
            pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
        }
        // v3.4.19 等黑兜底自愈（TA 01:12「对方下完还死等」终极防线!!）:
        // matched 命中但因【暂存/复现链】迟迟未应用 → 累计计数——同一步连续 3 帧
        // 命中 → 绕过双帧确认直接应用（屏幕=真理——对方已走是物理事实!!）
        if (matched != null && verifyUci == null) {
            if (pendingStableMove == matched.toString()) {
                pendingStableCount++
            } else {
                pendingStableMove = matched.toString(); pendingStableCount = 1
            }
            if (pendingStableCount >= 3) {
                pendingStableCount = 0; pendingStableMove = null
                pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
                mainHandler.post { tvStatus?.text = "对方 ${matched.toString()} 稳定命中·强制应用（自愈）" }
                // 直接落到下方应用链!!
            }
        } else if (matched == null) {
            pendingStableCount = 0; pendingStableMove = null
        }
        if (matched != null) {
            pendingStepConfirmObs = null; pendingStepConfirmAfter = null; pendingStepConfirmFen = null
            val mv = matched
            // 棋盘事实版 obs 替换原始观察（后续记谱/应用逻辑统一用干净事实）
            if (matchedAfterSig != null) obs = matchedAfterSig
            // v1.12.0 鹏飞 IsExactlyMovedOneStepFromStartBoard 首步强校验：
            // 新局布防后第一步必须严格=初始局面动一子（20 个合法首着之一）。
            // 不满足 → 视为识别乱帧（很可能是中局被误判成新局）→ 拒绝追平等重采
            if (newGameArmed) {
                val startBoard = Board()
                try { startBoard.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1") } catch (_: Exception) { }
                val isOneStepFromStart = try {
                    startBoard.legalMoves().any { m ->
                        try {
                            startBoard.doMove(m)
                            val hit = placementOf(startBoard.fen) == local
                            startBoard.undoMove()
                            hit
                        } catch (_: Exception) { false }
                    }
                } catch (_: Throwable) { false }
                if (!isOneStepFromStart) {
                    newGameArmed = false   // 撤防（避免持续卡死），交给后续重置通道再判
                    return SyncResult.UNKNOWN
                }
                newGameArmed = false
            }
            val mover = try { extBoard.getPiece(mv.from).pieceSide } catch (_: Exception) { return SyncResult.UNKNOWN }
            val wasMyVerify = verifyUci != null
            // ── 确定性判边（v1.7.3 重写）──
            // 规则：verifyUci==null = 我们没有点击 → 屏幕上发生的一步必然是【对方】走的。
            // （旧启发式"mover==mySide 且从未落子就翻转"会被"我方落子+识别抖动清掉verify"的
            //  交错序列误触发——用户实测"点了执白还去动对方棋子"的元凶；且没有 sideAuto 守卫，
            //  手动选边也会被翻转！）
            if (!wasMyVerify && sideAuto && mover == mySide && myMovesVerified == 0
                && android.os.SystemClock.elapsedRealtime() - lastTapAt > 6000
            ) {
                // 双保险：6 秒内点过屏幕 → 这步极可能是我们自己的点击（verify 被识别抖动
                // 的重置清掉的交错情形）→ 绝不翻转。没点过屏幕才允许校准。
                mySide = mover.flip()
                myMovesVerified = 0
                mainHandler.post {
                    renderSideSelection()
                    tvStatus?.text = "执子校准：我方执${if (mySide == Side.WHITE) "白" else "黑"}"
                }
            }
            // ── v1.13.0 复合追平（用户 9/7-9/8 症状的完整闭环）──
            // 对方步出现 + verify 在途 = 我方步必已生效（对方只能在我走完后回棋）。
            // 当前轮次=我方 → 先补应用我方步（顺序天然正确），再 reloadStm 走对方步。
            if (mover != mySide && verifyUci != null) {
                val pendingUci = verifyUci
                val myMv = if (pendingUci != null) moveFromUci(extBoard, pendingUci) else null
                if (myMv != null && pendingUci != null) {
                    try { extBoard.doMove(myMv) } catch (_: Exception) { /* 已应用过/非法则保持 */ }
                    if (recordMode) {
                        try {
                            recordMoves.add(pendingUci.lowercase())
                            recordMoveCount++
                        } catch (_: Throwable) {}
                    }
                }
            }
            // 走子方颜色与本地轮次不符 → 本地落后一步：先翻转本地轮次再应用
            if (mover != extBoard.sideToMove) {
                if (!reloadStm(placementOf(extBoard.fen), mover == Side.WHITE)) return SyncResult.UNKNOWN
            }
            lastMoveDesc = mv.from.name.lowercase() + mv.to.name.lowercase() +
                    (mv.promotion?.let { it.fenSymbol.lowercase() } ?: "")
            // ── 记谱（v1.10.2 整谱收集）：屏幕追平确认的每一步（双方都记）追加当前谱；
            // 起始局面 = 记录模式后第一个被追平确认的局面 ──
            if (recordMode) {
                try {
                    if (recordStartPlacement == null) {
                        recordStartPlacement = local
                        recordStartWhite = mover == Side.WHITE
                    }
                    recordMoves.add(lastMoveDesc)
                    recordMoveCount++
                    MyBook.flush()
                    mainHandler.post { tvStatus?.text = "记谱 $recordMoveCount 手 · 我方走子已确认" }
                } catch (_: Throwable) {}
            }
            try { extBoard.doMove(mv) } catch (_: Exception) { return SyncResult.UNKNOWN }
            resetCandidateFen = null; resetCandidateCount = 0; resetMismatchCount = 0
            // v1.13.0：步已应用 → 锚点=【extBoard 当前摆位】（diff 匹配的噪声容忍格被
            // 棋盘事实覆盖；复合追平场景含补应用的我方步——绝不能用 match 返回的
            // after 单步摆位，否则锚点缺我方步 → 下一帧错误重放）
            lastAcceptedSig = placementOf(extBoard.fen)
            if (matched != null) { obs = lastAcceptedSig }   // 对齐后续 bigJump/D 的观察基准
            // 我方走子被屏幕确认 → 验证成功闭环
            if (mover == mySide) {
                verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                promoPhase = PromoPhase.OFF
                myMovesVerified++
                // ── v1.13.6 双步一帧闭环（用户 9/10 01:14"验证半天/认为对方没下"根治）──
                // 我方步确认帧到达时若屏幕已含【对方回应步】（对方秒回——大模型
                // 400-700ms/帧识别窗口内极易发生），立即二连匹配吃掉对方步：
                // 老逻辑"下帧消化"会被 verify 窗口/重试点击抢跑——用户实测"自己
                // 还在验证，去看对方下棋就认为对方没下"。一帧闭环=verify 归零+
                // 对方步就位+轮我，全链路少一整个识别帧。
                val remainingD = diffPositions(lastAcceptedSig, placementOf(fenNow))
                if (remainingD.isNotEmpty() && remainingD.size <= 6) {
                    val second = matchOneStepByDiff(extBoard, lastAcceptedSig, placementOf(fenNow))
                    if (second != null) {
                        val mv2 = second.first
                        val mover2 = try { extBoard.getPiece(mv2.from).pieceSide } catch (_: Exception) { Side.WHITE }
                        if (mover2 != mySide) {
                            try {
                                if (mover2 != extBoard.sideToMove) {
                                    reloadStm(placementOf(extBoard.fen), mover2 == Side.WHITE)
                                }
                                extBoard.doMove(mv2)
                                lastMoveDesc = mv2.from.name.lowercase() + mv2.to.name.lowercase() +
                                        (mv2.promotion?.let { it.fenSymbol.lowercase() } ?: "")
                                lastSentMove = null
                                lastAcceptedSig = placementOf(extBoard.fen)
                                mainHandler.post { tvStatus?.text = "双步闭环 $lastMoveDesc（秒回消化）" }
                            } catch (_: Exception) { /* 二连失败=下帧正常消化 */ }
                        }
                    }
                }
                // 鹏飞守卫闭环：我方步屏幕确认后保留 lastSentMove（证明"我刚走完"），
                // 直到【对方】步被追平确认才清——等待期间任何路径都翻不了轮出手。
                if (extBoard.sideToMove != mySide) {
                    mainHandler.post { tvStatus?.text = "落子确认 $lastMoveDesc" }
                }
            } else {
                // 对方走子被追平确认 → 我方"已发送"记录完成使命，解除守卫
                lastSentMove = null
                // v1.13.0（用户 9/7-9/8 实测症状根治）："对方步能被追平"这一事实本身
                // 证明我方步早已生效（对方只能在我走完后回棋）。verify 若还挂着（我方
                // 步的确认帧被漏识别），必须立即闭环——否则超时后 handleTapFailed 用
                // 旧坐标重试点击=在对方已回应的新局面上乱点（"自己都下了还在动自己
                // 的棋子"的实测路径）。
                if (verifyUci != null) {
                    verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                    promoPhase = PromoPhase.OFF
                }
            }
            return if (extBoard.sideToMove == mySide) SyncResult.MY_MOVE_PENDING else SyncResult.SYNCED_WAIT
        }

        // ── 换题极速通道（v1.10.0 闯关模式核心）：习题通关瞬间跳下一题（右→左动画），
        // 局面剧变（≥10 格差异 or 棋子数剧变）→ 单帧判定换题 → 立即以屏幕重置 →
        // 查我的库命中即闪击。不等情况D的 2 帧确认（那是给小幅抖动的，换题等不起）。
        // 动画中间态已被 pieceCount 骤降过滤挡在 tick 外，此处收到的已是稳定帧。
        // v1.10.1：verify 挂着也直接换题并清 verify——通关瞬间最后一手常未确认，
        // 旧验证不清会触发"旧坐标收割重试"在新习题屏幕上乱点（真 bug，实测路径）。
        // v1.11.2：同时清 lastSentMove——旧局的"对方回应"永远不会来了，守卫若不
        // 解锁 → 新局轮我却永不出手 = "换局卡死，必须手动断开重连"（用户实测根因）。
        // v1.12.5（用户实测根因）：旧 OR 逻辑——双方连击吃掉 4 子（各吃 2，正常兑子）
        // 就满足"棋子数差≥4"→ 误判换局 → "局面已重同步"+轮我重置 → 程序再走一步
        // = "自己都下了还在动自己的棋子"循环。真换局特征=摆位大改：必须 AND（≥10 格
        // 差异 且 棋子数剧变）。连击吃子（格差 2~8）走情况C 两步追平，不再被抢跑。
        // v1.13.0：格差基准改用【锚点】（上次接受的屏幕事实）——比 local 准
        // （verify 挂起时 local=走前局面，格差会被多算 2 格导致误触发）。
        val bigJump = diffCells(obs, lastAcceptedSig) >= 10 &&
                (lastAcceptedPieces > 0 && kotlin.math.abs(piecesOf(obs) - lastAcceptedPieces) >= 4)
        if (bigJump) {
            // v3.4.13 识别质量门（根因修复）：识别崩盘帧（pqqqp/bnnn 级乱码——
            // 云端复刻实锤 tiny_piece48 对 lichess 崩）绝不允许触发 bigJump 重建。
            // 识别不可信→保守等待（9/18 铁律：不确定就不动）。
            if (ChessSyncRules.frameQuality(obs) < 0) {
                mainHandler.post { tvStatus?.text = "识别质量差·等待稳定帧（不重建）" }
                return SyncResult.SYNCED_WAIT
            }
            // v3.4.2：大跳变重建同款语义修正——开局形态必白先（旧代码传执边=
            // 轮次，执黑新局轮错边）；王数守卫拒双王盘（lichess 类型错读）。
            val kingsOk2 = obs.count { it == 'K' } == 1 && obs.count { it == 'k' } == 1
            if (!kingsOk2) return SyncResult.UNKNOWN
            val freshShape2 = isFreshPlacementSig(obs)
            // v3.4.11 根因修复（9/25 05:22 终案）：重建串必须先过 kq 净化——
            // 识别互换串直进 buildBoard = extBoard 王后互换怪盘 = 引擎在怪盘
            // 选步 = 恰好合法的送象（▶f1a6 ←engine @rnbkqbnr 审计铁证）。
            val fresh = ChessSyncRules.buildBoard(
                if (freshShape2) "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
                else ChessSyncRules.purgeKqOf(obs),
                if (freshShape2) true else mySide == Side.WHITE
            ) ?: return SyncResult.UNKNOWN
            extBoard = fresh
            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
            lastSentMove = null   // v1.11.2：换局守卫解锁（关键修复）
            resetCandidateFen = null; resetCandidateCount = 0; resetMismatchCount = 0
            lastAcceptedSig = obs   // v1.13.0：换局重置 → 锚点同步到新屏幕事实
            // v1.13.2（鹏飞 8.2 根除）：换局 → NewGameSync 对齐确认（连续帧一致才回
            // Playing 循环出手），替代旧"重置→轮我→立即出手"乱走路径
            state = State.NEW_GAME_SYNC
            ngSyncCandidateFen = null; ngSyncCount = 0
            lastThinkFen = null   // 记录模式建议跟随新局面
            if (recordMode) {
                sealRecordLine("习题 · ${java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} · ${recordMoveCount}步")
                mainHandler.post { tvStatus?.text = "检测到换题 · 谱已入库 · 继续记录" }
            } else {
                // v1.13.2：换题重置进 NewGameSync 对齐（对齐完成即刻出手，仍是极速闪击）
                mainHandler.post { tvStatus?.text = "检测到换题 · 对齐确认中" }
            }
            return SyncResult.SYNCED_WAIT   // 对齐分支接管出手时机
        }

        // ── 情况D：完全对不上（对局重开 / 大幅变化 / 识别错乱）──
        // 快速通道：连续 2 帧同一摆位 → 立即重置（鹏飞"不一致立马发现"）
        // 兜底通道：累计 8 帧对不上（摆位抖动也算）→ 强制重置最新摆位
        // 例外：标准开局形态无歧义，免双帧确认（点连线秒出手，不等 2 帧）
        // v1.12.0 鹏飞 8.3 新局同步容错（报告字段级复刻）：
        //  · 分类时间戳——"非一步差/全空/解析失败"分别记忆首现时刻，同类异常
        //    持续才判新局（瞬时抖动/动画帧不触发）；类型间互斥（换一类计时清零）
        //  · _lastFen 语义——本地最后一致局面（重置后首步强校验的基准）
        val freshWhite = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val freshBlack = "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr"
        val isFreshGame = obs == freshWhite || obs == freshBlack
        // 分类异常类型（鹏飞 _lastBeginNotYIZHI/_lastBeginAllEmptyTime/_lastBeginErrorMove）
        val anomalyKind = when {
            piecesOf(obs) == 0 -> 1   // 全空棋盘（动画中间态/切页）
            findMoveToPlacement(extBoard, obs) != null -> 0  // 其实一步可达=不算异常
            else -> 2   // 非一步差（真·新局或识别乱）
        }
        if (anomalyKind != lastAnomalyKind) { firstAnomalyAt = 0L; lastAnomalyKind = anomalyKind }
        if (firstAnomalyAt == 0L) firstAnomalyAt = android.os.SystemClock.elapsedRealtime()
        val anomalyMs = android.os.SystemClock.elapsedRealtime() - firstAnomalyAt
        // 全空（鹏飞 _lastBeginAllEmptyTime 语义）：切页/动画中间态——等待非空帧，
        // 绝不重置到空盘
        if (anomalyKind == 1) {
            resetMismatchCount++
            return SyncResult.SYNCED_WAIT
        }
        if (obs != placementOf(resetCandidateFen ?: "")) {
            resetCandidateFen = fenNow
            resetCandidateCount = 1
        } else {
            resetCandidateCount++
        }
        if (isFreshGame) resetCandidateCount = 99   // 开局免确认
        // 双帧一致 或 同类异常持续 600ms（鹏飞分类时间戳语义：瞬时不判，持续才判）
        if (anomalyKind == 2 && anomalyMs > 600) resetCandidateCount = maxOf(resetCandidateCount, 2)
        resetMismatchCount++
        if (resetCandidateCount < 2 && resetMismatchCount < 8) {
            if (resetMismatchCount == 1) {
                mainHandler.post { tvStatus?.text = "局面不一致·确认中…" }
            }
            return SyncResult.SYNCED_WAIT
        }
        // v3.4.13 识别质量门（根因修复）：重置源帧必须可信——崩盘帧（王数/
        // 兵数/类型混乱）触发重置=识别噪声直灌引擎盘（送象因果链第3环）。
        if (ChessSyncRules.frameQuality(obs) < 0) {
            // v3.4.24 刀6: TA 10/04 案死循环本体!!——「确认2帧→质量拦→计数清零」
            // 无限循环(仿真99次复现!!)。幽灵帧(9兵)等持续烂读时: 30 周期对峙
            // → 弃本会话校准框+清boardRect → 下帧 YOLO 全帧重定位新网格!!
            // （陈旧校准框=跨App毒药: calibFrameRect() 永远压过 fresh YOLO!!）
            // v3.4.25: 刀6「弃校准框重定位」回滚——把死锁换成毒网格（弃掉TA手动
            // 校准→YOLO网格精度差→读数更脏→"每次都卡"!!）。对峙只保留诊断计数!!
            resetStandoff++
            mainHandler.post { tvStatus?.text = "识别质量差·重置已拦（等待恢复 $resetStandoff）" }
            resetCandidateCount = 0; resetMismatchCount = 0
            return SyncResult.SYNCED_WAIT
        }
        resetStandoff = 0
        // 重置（v1.7.5 乐观轮次）：摆位取屏幕观察值，轮次取【轮我方】——
        // 断线重连/点连线时用户视角"该我了"的概率远大于"该对方"；
        // 万一错了：点击无效→验证重试链→"疑似非我方行棋"自动翻轮等待，自愈 ≤10 秒。
        // （旧版沿用 extBoard 残留轮次——上一局执黑残局的轮次会污染新局，
        //  用户实测"执白点连线开局就监视对方"）
        // 纯开局形态例外：必然轮白（数学事实）
        val fresh = ChessSyncRules.buildBoard(obs, isFreshGame || mySide == Side.WHITE) ?: return SyncResult.UNKNOWN
        extBoard = fresh
        lastSentMove = null   // v1.11.2：局面重置=旧守卫作废（换局/重连卡死修复）
        lastAcceptedSig = obs   // v1.13.0：重置 → 锚点同步屏幕（鹏飞 SetChessManagerCurrentToRealCurrent：真重置后从零增量跟踪）
        // v1.12.0 鹏飞 IsExactlyMovedOneStepFromStartBoard：重置到开局形态时布防
        // 首步强校验——此后第一步追平必须是"从初始局面严格动一子"，防把中局
        // 识别乱帧误判成新局后又被乱追平
        newGameArmed = isFreshGame
        resetCandidateFen = null
        resetCandidateCount = 0
        resetMismatchCount = 0
        firstAnomalyAt = 0L; lastAnomalyKind = 0
        // v1.13.2（鹏飞 8.2 根除）：重置 → NewGameSync 对齐确认（连续 2 帧摆位一致才
        // 回 Playing 循环出手）——替代 v1.10.4"重置→轮我→立即出手"（识别未稳时
        // 在错局面走子+乱点的实测根因）。对齐通常 1-2 帧 ≈160ms 内完成，随后立即
        // 出手（远快于 1.5s 延时且不依赖任何时钟猜测——纯状态驱动）。
        state = State.NEW_GAME_SYNC
        ngSyncCandidateFen = null; ngSyncCount = 0
        mainHandler.post { tvStatus?.text = "局面重同步 · 对齐确认中" }
        // 验证窗口内不清 verify（对不上很可能是我们的点击正在落子/动画中——
        // v1.7.3 前这里清掉 verify 会连带触发判边误翻转）；交给 handleTapFailed 管理生命周期
        if (verifyUci != null && android.os.SystemClock.elapsedRealtime() > verifyDeadline) {
            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
        }
        // 自动判边：仅标准开局形态可信；中盘数子力本质是瞎猜，保留当前执边
        // 新对局判边：白在底=我执白；黑在底=我执黑（视角已由 orientLock 保证正确）
        if (sideAuto && isFreshGame) {
            mySide = if (obs == freshBlack) Side.BLACK else Side.WHITE
            myMovesVerified = 0
            mainHandler.post { renderSideSelection() }
        }
        return SyncResult.SYNCED_WAIT   // 出手时机由 NewGameSync 对齐分支接管
    }

    /** 摆位串棋子总数 —— v1.13.2 单源转发 */
    private fun piecesOf(p: String): Int = ChessSyncRules.piecesOf(p)

    /** 把 extBoard 重载为「同摆位、指定行棋方」。带合法性探针：非法（不该走方被将军）返回 false 且不动 extBoard */
    private fun reloadStm(placement: String, whiteToMove: Boolean): Boolean {
        val b = ChessSyncRules.buildBoard(placement, whiteToMove) ?: return false
        extBoard = b
        return true
    }

    /** 标准开局形态（32子、首末两对行全满、中间空）→ 升变分支直接跳过（防开局误检"升变确认"） */
    /** v3.4.2 摆位级初始形态判定（occ 语义：32 子满排+中空——不依赖 Recognition，
     * 供 ngSync/bigJump 重建统一口径；识别只判"形态"，类型由标准盘提供）。 */
    private fun isFreshPlacementSig(plac: String): Boolean {
        val g = try { expandPlacement(plac) } catch (_: Throwable) { null } ?: return false
        var pieces = 0
        for (i in 0 until 64) if (g[i] != '.') pieces++
        if (pieces != 32) return false
        for (r in 2..5) for (c in 0 until 7) if (g[r * 8 + c] != '.') return false
        return (0..1).all { r -> (0 until 8).all { c -> g[r * 8 + c] != '.' } } &&
                (6..7).all { r -> (0 until 8).all { c -> g[r * 8 + c] != '.' } }
    }

    private fun isStandardOpeningShape(rec: BoardDetector.Recognition): Boolean {
        if (rec.pieceCount != 32) return false
        val fullRows = BooleanArray(8) { r -> (0 until 8).all { c -> rec.cells[r][c] != BoardDetector.CellState.EMPTY } }
        val emptyMid = (2..5).all { r -> (0 until 8).none { c -> rec.cells[r][c] != BoardDetector.CellState.EMPTY } }
        return emptyMid && fullRows[0] && fullRows[1] && fullRows[6] && fullRows[7]
    }

    /** FEN → 摆位段 —— v1.13.2 单源转发 */
    private fun placementOf(fen: String?): String = ChessSyncRules.placementOf(fen)

    /**
     * 两阶段识别（416 输入架构）：
     * 1) 已知区域（校准框 / 已定位棋盘）→ 裁剪推理（棋子分辨率最优）
     * 2) 未知 → 全帧找盘（board 是大目标可检出）→ 裁剪棋盘二次推理
     * 返回全屏坐标系的 Recognition。
     */
    /** 小模型识别（v1.10.0）：TinyClassifier 64 格分类 → Recognition 组装 */
    /** 小模型上一帧摆位签名（v1.12.3 汉明软仲裁——鹏飞三级阈值+双管线仲裁思想） */
    @Volatile private var tinyLastPlacement: String? = null

    /** 180° 视角转换（屏幕朝向↔标准白视角：64 格序整体倒排；复用现有 expandPlacement） */
    private fun mirror180(p: String): String {
        val e = expandPlacement(p) ?: return p
        val r = e.reversed()
        return buildString {
            for (row in 0 until 8) {
                if (row > 0) append('/')
                var empty = 0
                for (col in 0 until 8) {
                    val ch = r[row * 8 + col]
                    if (ch == '.') empty++ else {
                        if (empty > 0) { append(empty); empty = 0 }
                        append(ch)
                    }
                }
                if (empty > 0) append(empty)
            }
        }
    }

    private fun recognizeTiny(bmp: Bitmap, rect: BoardDetector.BoardRect): BoardDetector.Recognition? {
        val (types, confs) = TinyClassifier.classifyGrid(bmp, rect) ?: run { tinyLastPlacement = null; return null }
        // 视角：优先 orientLock；无锁用启发式（白子集中半区）
        val wab: Boolean = orientLock ?: run {
            var wBot = 0; var wTop = 0
            for (r in 0 until 8) for (c in 0 until 8)
                if (types[r][c].isUpperCase()) { if (r >= 4) wBot++ else wTop++ }
            wBot >= wTop
        }
        // ── v3.1.2 低置信锚点复核（9/15 15:38 回归修正）──
        // v3.1.1 版缺陷：复核无条件生效 → 对方走步帧（新位置棋子置信低）被抹回
        // 旧锚点 → syncBoard 看不到屏幕变化 → "对方下了没反应/监视对方"死等 →
        // 错位积累 → 突然冒出时追平错乱 → 引擎基于错局面下错棋（用户实测空格
        // 落子/放弃可吃子）。
        // 修正：复核只在【画面物理静止】时启用（curFrameHash == anchorHash）——
        // 静止期的识别抖动（K=2 误判温床）才需要锚点收敛；画面变化帧（哈希≠锚）
        // = 对方刚走/我方点击生效 → 复核关闭，真实变化原样通过 syncBoard 追平。
        // 复核命中数上屏（REV=n）——回归时拍照即破案。
        val anchorSig = lastAcceptedSig.ifEmpty { placementOf(extBoard.fen) }
        val stillFrame = curFrameHash != null && anchorHash != null && curFrameHash == anchorHash
        var reviewHits = 0
        if (anchorSig.isNotEmpty() && stillFrame) {
            val anchorTypes = ChessSyncRules.expandPlacement(anchorSig)
            if (anchorTypes != null) {
                for (r in 0 until 8) for (c in 0 until 8) {
                    if (confs[r][c] >= 0.9f) continue
                    val f: Int; val rk: Int
                    if (wab) { f = c; rk = 7 - r } else { f = 7 - c; rk = r }
                    val idx = (7 - rk) * 8 + f
                    val anchorCh = anchorTypes[idx]
                    if (types[r][c] != anchorCh) {
                        types[r][c] = anchorCh
                        confs[r][c] = 0.95f   // 复核后视作已确认
                        reviewHits++
                    }
                }
            }
        }
        if (reviewHits > 0) mainHandler.post { tvDepthReached?.text = (tvDepthReached?.text?.toString() ?: "") + "·REV${reviewHits}" }
        // 摆位统计（复核后）
        var hasK = false; var hasK2 = false
        var whiteCount = 0; var blackCount = 0
        val cells = Array(8) { Array(8) { BoardDetector.CellState.EMPTY } }
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = types[r][c]
            if (ch == '.') continue
            if (ch == 'K') hasK = true
            if (ch == 'k') hasK2 = true
            if (ch.isUpperCase()) { cells[r][c] = BoardDetector.CellState.WHITE; whiteCount++ }
            else { cells[r][c] = BoardDetector.CellState.BLACK; blackCount++ }
        }
        if (!hasK || !hasK2 || whiteCount + blackCount < 4) { tinyLastPlacement = null; return null }
        // ── v1.12.3 汉明软仲裁（替换 v1.12.1 的双帧硬校验——那个有两个致命 bug：
        //    ①屏幕朝向 vs 标准视角坐标系不匹配（执黑永远对不上→死回退→速度掉回
        //    大模型→验证超时→重试风暴→"屏幕同步"连环，用户实测连线瘫痪）
        //    ②追平后局面无步可达也被判"不可解释"。软仲裁只看差异格数：
        //    ≤6 格=正常走子（1 步走子+识别噪声）→ 信任小模型；
        //    >6 格=格错位/换局/动画 → 回退大模型（网格拟合自动纠偏 boardRect）──
        val sig = BoardDetector.toPlacement(
            BoardDetector.Recognition(rect, cells, types, true, whiteCount + blackCount, whiteCount)
        )
        val sigStd = if (wab) sig else mirror180(sig)
        val localP = placementOf(extBoard.fen)
        if (diffCells(sigStd, localP) > 6) {
            // 大幅差异：换局通道/情况D 会在大模型帧里处理；本帧回退精准识别
            return null
        }
        tinyLastPlacement = sig
        return BoardDetector.Recognition(
            rect = rect, cells = cells, types = types, whiteAtBottom = wab,
            pieceCount = whiteCount + blackCount, whiteCount = whiteCount
        )
    }

    private fun recognizeScreen(bmp: Bitmap, yolo: YoloChessDetector): BoardDetector.Recognition? {
        // ── 极速小模型路径（v1.10.0）：用户主用皮肤专用 13 类逐格分类器 ──
        // 已知 boardRect（连线中缓存/校准框）→ 64 格批量分类 → 全帧 <50ms（YOLO 1/10）。
        // 未定位棋盘时仍走 YOLO（board 检测只有大模型会）。识别失败自动回退大模型。
        if (useTinyModel) {
            val known = calibFrameRect() ?: boardRect
            if (known != null && known.cell >= 8) {
                val rec = try { recognizeTiny(bmp, known) } catch (_: Throwable) { null }
                if (rec != null) return rec
            }
            // 小模型无棋盘锚点 → 先 YOLO 定盘（盘位置锁定后后续帧走小模型）
        }
        val known = calibFrameRect() ?: boardRect
        if (known != null && known.cell >= 4) {
            val m = (known.cell * 8 * 0.06f).toInt()   // 外扩 6% 容错
            val x = (known.left - m).coerceAtLeast(0)
            val y = (known.top - m).coerceAtLeast(0)
            val w = (known.cell * 8 + 2 * m).coerceAtMost(bmp.width - x)
            val h = (known.cell * 8 + 2 * m).coerceAtMost(bmp.height - y)
            if (w >= 64 && h >= 64) {
                val crop = Bitmap.createBitmap(bmp, x, y, w, h)
                val rec = try { BoardDetector.recognizeByYolo(crop, yolo, null, x, y) } catch (_: Throwable) { null }
                crop.recycle()
                if (rec != null) return rec
                // 裁剪识别失败（棋盘可能移位）→ 落到全帧找盘
            }
        }
        // 全帧找盘
        val full = try { BoardDetector.recognizeByYolo(bmp, yolo) } catch (_: Throwable) { null } ?: return null
        // 二次推理：裁剪棋盘区域（全帧下棋子太小/框已找到但棋子丢）
        val r = full.rect
        val m = (r.cell * 8 * 0.08f).toInt()
        val x = (r.left - m).coerceAtLeast(0)
        val y = (r.top - m).coerceAtLeast(0)
        val w = (r.cell * 8 + 2 * m).coerceAtMost(bmp.width - x)
        val h = (r.cell * 8 + 2 * m).coerceAtMost(bmp.height - y)
        if (w < 64 || h < 64) return full
        val crop = Bitmap.createBitmap(bmp, x, y, w, h)
        val rec2 = try { BoardDetector.recognizeByYolo(crop, yolo, null, x, y) } catch (_: Throwable) { null }
        crop.recycle()
        return rec2 ?: full
    }

    /** 升变弹框处理结果 */
    private enum class PromoPickResult { PICKED, NO_DIALOG, TIMEOUT, WAIT }

    /**
     * 升变弹框视觉选择：
     * 弹框里的皇后 = 棋子类检测且（尺寸>1.2格 或 偏离网格>0.35格）——盘面真棋子不会同时满足。
     * 关键三态：
     *  PICKED    —— 找到弹框皇后并已点击（继续等下一帧）
     *  NO_DIALOG —— 已点过弹框(≥1次)但弹框皇后消失 → 升变已完成，底线"兵"是误识别 → 调用方立即改判
     *  WAIT      —— 间隔未到/超时保护
     */
    private fun handlePromotionPicker(bmp: Bitmap, rec: BoardDetector.Recognition, yolo: YoloChessDetector): PromoPickResult {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now > promoDeadline) {
            promoPhase = PromoPhase.OFF
            mainHandler.post { tvStatus?.text = "升变选择超时…" }
            return PromoPickResult.TIMEOUT
        }
        // 点击间隔内（给对弈 App 反应时间）
        if (now - lastPromoTapAt < 1200) return PromoPickResult.WAIT

        val r = rec.rect
        val cell = r.cell
        val queenIdx = if (mySide == Side.WHITE) 2 else 8   // LABEL_TO_FEN: 'Q'=2 / 'q'=8
        // 升变目标格中心（capture 坐标）——首次兜底点击点 & 距离参照
        val promoToCap: Pair<Float, Float>? = verifyUci?.takeIf { it.length >= 4 }
            ?.let { squareToScreen(rec, r, it.substring(2, 4).uppercase()) }

        var tapCap: Pair<Float, Float>? = null
        try {
            val m = (cell * 8 * 0.15f).toInt()
            val x = (r.left - m).coerceAtLeast(0)
            val y = (r.top - m).coerceAtLeast(0)
            val w = (cell * 8 + 2 * m).coerceAtMost(bmp.width - x)
            val h = (cell * 8 + 2 * m).coerceAtMost(bmp.height - y)
            if (w >= 64 && h >= 64) {
                val crop = Bitmap.createBitmap(bmp, x, y, w, h)
                val dets = try { yolo.detect(crop) } catch (_: Throwable) { null }
                crop.recycle()
                if (dets != null) {
                    var bestDist = Float.MAX_VALUE
                    for (d in dets) {
                        if (d.labelIdx != queenIdx) continue
                        val cx0 = d.cx + x; val cy0 = d.cy + y   // → capture 坐标
                        // 特征A：弹框棋子比格宽大（渲染成大图标）
                        val oversized = d.w > cell * 1.2f || d.h > cell * 1.2f
                        // 特征B：中心偏离最近格中心（弹框浮在格间/格上方）
                        val col = ((cx0 - r.left) / cell).toInt().coerceIn(0, 7)
                        val row = ((cy0 - r.top) / cell).toInt().coerceIn(0, 7)
                        val ccx = r.left + col * cell + cell / 2f
                        val ccy = r.top + row * cell + cell / 2f
                        val offGrid = kotlin.math.abs(cx0 - ccx) > cell * 0.35f ||
                                kotlin.math.abs(cy0 - ccy) > cell * 0.35f
                        if (!oversized && !offGrid) continue
                        val dist = promoToCap?.let { p ->
                            (cx0 - p.first) * (cx0 - p.first) + (cy0 - p.second) * (cy0 - p.second)
                        } ?: 0f
                        if (dist < bestDist) { bestDist = dist; tapCap = cx0 to cy0 }
                    }
                }
            }
        } catch (_: Throwable) {}

        // ── 关键判据：弹框皇后消失 ──
        // 已点过弹框（≥1 次）却再找不到弹框皇后 → 弹框已关闭 → 升变已完成，
        // 底线"兵"是升变后新后被误识别 → 调用方立即改判（根除卡死）
        if (tapCap == null && promoTaps >= 1) {
            return PromoPickResult.NO_DIALOG
        }
        if (tapCap == null && promoTaps == 0 && promoToScreen == null) {
            return PromoPickResult.NO_DIALOG   // 从未有目标坐标（异常防御）
        }

        val capW = lastCapW.coerceAtLeast(1)
        val capH = lastCapH.coerceAtLeast(1)
        val sx = realScreenWidth.toFloat() / capW
        val sy = realScreenHeight.toFloat() / capH
        val tapReal: Pair<Float, Float> = when {
            tapCap != null -> tapCap!!.first * sx to tapCap!!.second * sy
            else -> {
                // v1.13.9（用户 9/11 指令"点不符合格子的升变棋子"+9/10 图1 实锤）：
                // 视觉没找到弹框皇后 → 兜底点【目标格下方 3.5 格】=弹框按钮行
                // （鳕鱼/lichess 系升变选择框在目标格下方展开，白后按钮=行首或
                // 最靠近目标列）——绝不再点目标格（点了弹框等于没点，永卡）。
                val px = promoToScreen!!.first
                val py = promoToScreen!!.second
                // 目标格是 y 已知（to 格屏幕坐标）——按钮行=+3.5 格（cell≈屏高/8
                // 棋盘区的格）——用 boardRect 的 cell 尺寸精确推
                val cellPx = boardRect?.cell?.times(sy) ?: (realScreenHeight / 12f)
                // 白方升变（目标在顶）按钮行在目标格【下方】；黑方升变在上方——
                // 按视角判定：目标在屏幕顶 ⟺ (我执白)==(白在底)
                val targetAtTop = (mySide == Side.WHITE) == recWhiteAtBottom
                val ny = if (targetAtTop) py + 3.5f * cellPx else py - 3.5f * cellPx
                px to ny
            }
        }
        if (promoTaps >= 5) return PromoPickResult.WAIT   // 点击上限：不再空敲
        promoTaps++
        lastPromoTapAt = now
        mainHandler.post { tvStatus?.text = "升变选择中…${if (tapCap != null) "点皇后" else "点弹框按钮行"}($promoTaps)" }
        ChessAccessibilityService.tapScreen(tapReal.first, tapReal.second) { ok ->
            if (!ok) mainHandler.post { tvStatus?.text = "升变点击被系统取消！" }
        }
        return PromoPickResult.PICKED
    }

    /** 落子验证失败处理：重试点击 → 3次后重定位 → 仍失败则怀疑轮次错误转等待 */
    private fun handleTapFailed(): SyncResult {
        val uci = verifyUci
        tapAttempts++
        when {
            tapAttempts <= 3 -> {
                mainHandler.post { tvStatus?.text = "落子未生效，重试($tapAttempts/3)…" }
                if (uci != null) {
                    // v1.13.7（图1 实测：弹框开着程序反复点格子）：升变步重试=弹框已开
                    // （部分 App 点目标格先弹选择框、兵不动）——继续点格子永远无效，
                    // 直接进 PICKING 视觉找弹框皇后按钮
                    if (uci.length >= 5 && promoPhase == PromoPhase.OFF) {
                        promoPhase = PromoPhase.PICKING
                        promoGraceUntil = android.os.SystemClock.elapsedRealtime() + 1200
                        promoTaps = 0
                        verifyDeadline = android.os.SystemClock.elapsedRealtime() + 16000
                        mainHandler.post { tvStatus?.text = "升变弹框检测中·视觉找皇后" }
                        return SyncResult.SYNCED_WAIT
                    }
                    verifyDeadline = android.os.SystemClock.elapsedRealtime() + 3500
                    mainHandler.post { performMove(uci) }   // 主线程执行（performMove 内直接操作悬浮窗控件）
                }
                return SyncResult.SYNCED_WAIT
            }
            tapAttempts == 4 -> {
                // 点击三连失败：坐标可能漂移 → 强制重定位后再试一次
                mainHandler.post { tvStatus?.text = "落子失败×3，重新定位后重试…" }
                verifyUci = null; verifyPrePlacement = null
                boardRect = null
                return SyncResult.RELOCATE_NEEDED
            }
            else -> {
                // 重定位后仍失败 → 大概率不是我的回合（轮次跟踪错了），转等待对方
                mainHandler.post { tvStatus?.text = "疑似非我方行棋，转监视…" }
                verifyUci = null; verifyPrePlacement = null
                lastSentMove = null   // 那步没走成，"已发送"记录作废（守卫不许拦正常等待）
                reloadStm(placementOf(extBoard.fen), mySide == Side.BLACK)
                waitSince = android.os.SystemClock.elapsedRealtime()
                return SyncResult.SYNCED_WAIT
            }
        }
    }

    /** 在 board 当前行棋方的合法走子中，找到走后摆位==target 的那步（只搜当前方）。
     *  防弹：illegal 局面（如轮次翻转后"不该走方被将军"）chesslib legalMoves 会抛
     *  MoveGeneratorException——v1.5.0 用户实测"异常×3 MoveGeneratorException"的来源 */
    private fun findStmMoveToPlacement(board: Board, target: String): com.github.bhlangonijr.chesslib.move.Move? {
        val moves = try { board.legalMoves() } catch (_: Throwable) { return null }
        for (mv in moves) {
            try {
                board.doMove(mv)
                val hit = placementOf(board.fen) == target
                board.undoMove()
                if (hit) return mv
            } catch (_: Exception) {
                try { board.undoMove() } catch (_: Exception) {}
            }
        }
        return null
    }

    /** 同一局面但行棋方翻转的副本（轮次纠偏用）。
     *  防弹：翻转后若"不该走方被将军"（非法局面），chesslib legalMoves 会抛异常 → 返回 null */
    private fun flipStm(board: Board): Board? = ChessSyncRules.flipStm(board)

    /** 摆位差一步合法棋（任意颜色）：返回那步棋。先搜当前行棋方，再搜对方（本地轮次落后一步的情形） */
    private fun findMoveToPlacement(board: Board, target: String): com.github.bhlangonijr.chesslib.move.Move? {
        findStmMoveToPlacement(board, target)?.let { return it }
        val flipped = flipStm(board) ?: return null
        return findStmMoveToPlacement(flipped, target)
    }

    /** 摆位差两步合法棋（本地漏掉一步+对方已回应）：找到则把两步都应用到 board，返回 true */

    // ─────────────── 引擎决策与落子 ───────────────

    private fun maybeAutoPlay() {
        if (state == State.THINK || state == State.PLAY || state == State.NEW_GAME_SYNC) return
        // v3.1.0：pausedPendingResume 守卫移除（acSync 专属语义——回退鹏飞式后
        // 无人置位，留着只会是回归隐患）
        // 上一步还在闭环验证中（等屏幕确认）→ 不叠加新落子
        if (verifyUci != null) return
        // 思考模式优先占用引擎：只算不下（连线中点思考 = 暂停自动落子，暂停思考后恢复）
        if (thinkPhase != ThinkPhase.OFF) return
        // 记录模式（v1.10.0）：只思考显示建议，绝不代走（用户口述"以我的招法为准"）
        if (recordMode) { thinkForDisplay(); return }
        // 鹏飞 _lastSentMove 总闸（v1.10.5）：上一手已发送且屏幕已确认（verify 空）但
        // 轮次仍指我 = 轮次跟踪被污染——绝不再次出手（等屏幕变化追平纠正轮次，
        // 或用户手动「我」）。这是"对方没下我连下两手"的最终封锁。
        if (lastSentMove != null && verifyUci == null && extBoard.sideToMove == mySide) {
            val confirmed = lastMoveDesc.equals(lastSentMove, ignoreCase = true)
            if (confirmed) {
                mainHandler.post { tvStatus?.text = "已走 $lastSentMove · 等待对方（若卡住点「我」钮）" }
                return
            }
        }
        if (extBoard.sideToMove != mySide) return
        // 引擎诊断：未启动时明确上屏（不再静默"无响应"）
        val eng = engine
        if (eng == null || !eng.isRunning()) {
            mainHandler.post { tvStatus?.text = "引擎未启动，正在拉起…" }
            workHandler.post {
                val e2 = engine ?: StockfishEngine(id = "auto", listener = this).also { engine = it }
                val ok = e2.isRunning() || e2.start(this)
                mainHandler.post {
                    if (!ok) {
                        engine = null
                        tvStatus?.text = "引擎启动失败（so 缺失/存储不足）"
                    }
                }
            }
            return
        }
        // 注意：isMated/isDraw 在畸形局面会从 chesslib 抛 RuntimeException（Board.isStaleMate 的
        // catch 会 rethrow），必须包住——识别错误时绝不能因此崩溃
        var mated = false
        var draw = false
        try {
            mated = extBoard.isMated
            draw = !mated && extBoard.isDraw
        } catch (_: Throwable) {
            mated = false; draw = false
        }
        if (mated || draw) {
            mainHandler.post {
                tvStatus?.text = when {
                    mated -> "对局结束：将杀"
                    else -> "对局结束：和棋"
                }
            }
            return
        }
        state = State.THINK
        val token = ++thinkingToken
        // ── 我的库闪击（v1.10.0 最高优先级）：用户自录谱命中 → 0ms 直接落子 ──
        // 习题中盘/残局重复出现，命中即瞬间应对（用户核心需求：0.1 秒内出子）
        try {
            // v3.4.8 开局禁闪（9/25 送象回归案根治）：旧门"≤6手且fresh形态"挡不住
            // 1.e4 后的毒谱命中（31子即非fresh，毒库照常闪出 Ba6）。开局 12 手
            // 一律不查我的库——与开局书窗口对齐，理论区交给书/引擎；中残局闪击
            // （习题谱场景）不受影响。旧 bug 期自动录进的毒谱由此整段作废。
            val nearOpening = extBoard.moveCounter <= 12
            if (!nearOpening) {
                val myMove = MyBook.probe(placementOf(extBoard.fen), mySide == Side.WHITE)
                // v3.4.4 守卫：库步必须当前局面合法（索引不含易位权——防错位命中）
                val legal = myMove != null && try {
                    extBoard.legalMoves().any { it.toString().equals(myMove, ignoreCase = true) }
                } catch (_: Throwable) { false }
                if (myMove != null && legal) {
                    val mv0 = myMove
                    pendingMoveSrc = "mybook"
                    mainHandler.post { tvStatus?.text = "⚡我的库命中 · 闪击 $mv0" }
                    performMove(mv0)
                    return
                }
            }
        } catch (_: Throwable) {}
        // ── 开局库优先：前 12 回合内查书直接出步（0ms，理论主流变化）──
        if (OpeningBook.ready && extBoard.moveCounter <= 12) {
            try {
                val bookMove = OpeningBook.probe(extBoard)
                if (bookMove != null) {
                    val bm = bookMove
                    pendingMoveSrc = "book"
                    // v3.4.9 审计：书候选集留档（判断加权随机选中项是否异常）
                    mainHandler.post {
                        tvStatus?.text = "📖开局库出 $bm（第${extBoard.moveCounter}手）"
                        tvEval?.text = ""; tvPv?.text = ""
                    }
                    performMove(bm)
                    return
                }
            } catch (_: Throwable) {}
        }
        // 每步实时读引擎设置（主界面 设置→引擎 可改）
        val (cfgDepth, cfgMt) = readEngineSettings(this)
        depthSetting = cfgDepth
        cfgMoveTimeMs = cfgMt ?: 700L
        // v1.11.3：对弈请求纳入引擎监视（ THINK 看门狗基准 + 全局看门狗心跳——
        //  中局僵死根因：requestBestMove 无 bestmove 无 info 时旧看门狗完全盲区）
        playSince = android.os.SystemClock.elapsedRealtime()
        engineSearching = true
        searchStartedAt = playSince
        lastEngineInfoAt = playSince
        mainHandler.post {
            tvStatus?.text = "我方行棋 · 引擎思考中…"
            tvEval?.text = ""; tvPv?.text = ""
        }
        engine?.setSkillLevel(20)
        engine?.requestBestMove(extBoard.fen, depth = cfgDepth, moveTimeMs = cfgMt) { best, _ ->
            if (token != thinkingToken) return@requestBestMove
            mainHandler.post {
                if (best != null && running) {
                    // 最后防线（v1.7.3）：着法棋子颜色必须是我方——状态层任何错乱
                    // 都不可能再让 App 去动对方的棋子（用户实测"点了执白还动对方棋子"）
                    val fromOk = try {
                        best.length >= 4 &&
                            extBoard.getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(
                                best.substring(0, 2).uppercase())).pieceSide == mySide
                    } catch (_: Throwable) { false }
                    if (fromOk) {
                        // v3.4.12 终极守卫（j1 铁证：v3.4.11 仍送 ▶f1a6 ←engine @互换盘）：
                        // ① extBoard 互换签名检查——怪盘上的引擎步绝不出手（怪盘=
                        // 识别互换串穿透重建——数据不可信，重置重同步）
                        val placNow = placementOf(extBoard.fen)
                        val kqSwapped = try {
                            // 互换签名=王在后位+后在王位（成对反转）。标准形态
                            // （d8='q' e8='k'）绝不能误判——否则正常局全被拒。
                            val r8 = placNow.split("/")[0]; val r1 = placNow.split("/")[7]
                            (r8.length==8 && r8[3]=='k' && r8[4]=='q') ||
                            (r1.length==8 && r1[3]=='K' && r1[4]=='Q')
                        } catch (_: Throwable) { false }
                        if (kqSwapped) {
                            state = State.WATCH
                            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                            lastSentMove = null
                            resetCandidateFen = placementOf(extBoard.fen)
                            resetCandidateCount = 1
                            auditShot(best, "⚠互换怪盘·拒出引擎步·重同步")
                            mainHandler.post { tvStatus?.text = "⚠本地盘异常(互换)·重同步中" }
                        } else if (isHangingMove(extBoard, best)) {
                            // ② v3.4.12 悬挂门扩展到引擎步（前8手）：正常引擎步
                            // 永不悬挂——悬挂=盘或链有病，拦下换重同步（book/mybook
                            // 已在 performMove 入口拦，engine 此处补齐=全来源覆盖）
                            auditShot(best, "⚠引擎步悬挂拦截·重同步")
                            state = State.WATCH
                            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                            lastSentMove = null
                            resetCandidateFen = placementOf(extBoard.fen)
                            resetCandidateCount = 1
                            mainHandler.post { tvStatus?.text = "⚠拦截悬挂引擎步 $best · 重同步中" }
                        } else {
                            pendingMoveSrc = "engine"; performMove(best)
                        }
                    }
                    else {
                        state = State.WATCH
                        tvStatus?.text = "着法校验失败（非我方棋子），重新同步…"
                    }
                }
                else if (running) {
                    state = State.WATCH
                    tvStatus?.text = "引擎未返回走法，重试中…"
                }
            }
        }
    }

    /** 模拟点击落子（主线程跑，异常防护：任何失败只回 WATCH 状态，绝不杀进程） */
    /** v3.4.9 悬挂步拦截后的引擎兜底：不点击书步，直接进 THINK 让引擎选步 */
    private fun engineThinkFallback(rejectedUci: String) {
        state = State.WATCH
        try {
            val fen = extBoard.fen
            engine?.requestBestMove(fen, depth = depthSetting ?: 12, moveTimeMs = cfgMoveTimeMs ?: 700L) { best, _ ->
                mainHandler.post {
                    if (best != null && running) {
                        pendingMoveSrc = "engine"
                        val fromOk = try {
                            best.length >= 4 &&
                                extBoard.getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(
                                    best.substring(0, 2).uppercase())).pieceSide == mySide
                        } catch (_: Throwable) { false }
                        if (fromOk) performMove(best)
                        else auditShot(rejectedUci, "⚠兜底引擎步 $best 校验失败")
                    } else {
                        auditShot(rejectedUci, "⚠兜底引擎无步——等待重试")
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /** v3.4.9 出手审计：来源+步+局面快照+书候选，持久上屏（不被状态刷新覆盖）
     *  + 同步写文件日志（filesDir/shot_audit.log，200KB 滚动）。用户拍照即取证。 */
    private fun auditShot(uci: String, extra: String = "") {
        try {
            val fen12 = extBoard.fen.take(16)
            val line = "▶$uci ←${pendingMoveSrc} @$fen12 $extra"
            mainHandler.post { tvAudit?.text = line }
            synchronized(shotLog) {
                shotLog.append(java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(java.util.Date()))
                    .append(' ').append(line).append('\n')
                if (shotLog.length > 200_000) shotLog.delete(0, shotLog.length - 100_000)
                try {
                    val f = java.io.File(filesDir, "shot_audit.log")
                    f.writeText(shotLog.toString())
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** v3.4.9 开局送子门（前 8 手，仅书步/库步——引擎步信任引擎）：
     *  9/20 铁律"守卫拦得住非法，拦不住恰好合法的烂步"的补全。书哈希若撞
     *  错条目（或数据腐坏），返回的步是合法的（守卫放行）但荒谬（送子）。
     *  判据：非吃子非升变，to 格上的我方子（价值≥3）走后被对方攻击且无我方
     *  任何子保护 → 送子嫌疑 → 拦截，改走引擎。正常理论步（Nf3/Bc4 等）的
     *  to 格要么不被攻击要么有兵链保护，永不误拦。 */
    private fun isHangingMove(board: com.github.bhlangonijr.chesslib.Board, uci: String): Boolean {
        try {
            if (board.moveCounter > 8) return false
            if (uci.length >= 5) return false   // 升变不走此门
            val from = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(0,2).uppercase())
            val to = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(2,4).uppercase())
            val victim = board.getPiece(to)
            if (victim != com.github.bhlangonijr.chesslib.Piece.NONE) return false   // 吃子不走此门
            val mover = board.getPiece(from)
            val cheap = mover == com.github.bhlangonijr.chesslib.Piece.WHITE_PAWN ||
                    mover == com.github.bhlangonijr.chesslib.Piece.BLACK_PAWN
            if (cheap) return false   // 兵步不走此门（弃兵是正常开局手段）
            val b2 = com.github.bhlangonijr.chesslib.Board()
            b2.loadFromFen(board.fen)
            val mv = ChessSyncRules.moveFromUci(b2, uci) ?: return false
            b2.doMove(mv)
            // to 格在 b2 上是我方子；被对方攻击且无我方保护 = 悬挂
            if (b2.getPiece(to) == com.github.bhlangonijr.chesslib.Piece.NONE) return false
            val opp = b2.sideToMove   // 走后轮对方
            val me = if (opp == com.github.bhlangonijr.chesslib.Side.WHITE)
                com.github.bhlangonijr.chesslib.Side.BLACK else com.github.bhlangonijr.chesslib.Side.WHITE
            val attackedByOpp = try { b2.squareAttackedBy(to, opp) != 0L } catch (_: Throwable) { return false }
            if (!attackedByOpp) return false
            val protectedByMe = try { b2.squareAttackedBy(to, me) != 0L } catch (_: Throwable) { return false }
            return !protectedByMe
        } catch (_: Throwable) { return false }
    }

    private fun performMove(uci: String) {
        try {
            performMoveInternal(uci)
        } catch (t: Throwable) {
            state = State.WATCH
            try {
                tvStatus?.text = "落子异常：${t.javaClass.simpleName}"
            } catch (_: Throwable) {}
        }
    }

    private fun performMoveInternal(uci: String) {
        // v3.4.9 开局送子门：书步/库步若为悬挂步（合法但送子）→ 拦截改走引擎。
        // 引擎步信任引擎（引擎不会送；若引擎出鬼步=局面错位，审计行会暴露）。
        if (pendingMoveSrc == "book" || pendingMoveSrc == "mybook") {
            if (isHangingMove(extBoard, uci)) {
                auditShot(uci, "⚠悬挂步拦截（送子嫌疑）→改走引擎")
                mainHandler.post { tvStatus?.text = "⚠拦截悬挂步 $uci · 改走引擎" }
                pendingMoveSrc = "engine"
                engineThinkFallback(uci)
                return
            }
        }
        state = State.PLAY
        playSince = android.os.SystemClock.elapsedRealtime()   // 看门狗计时起点
        lastSentMove = uci   // 鹏飞 _lastSentMove：物理发送记录（等对方期间禁翻轮的铁证）
        auditShot(uci)
        val rect = boardRect ?: run { state = State.WATCH; return }
        val rec = lastRec ?: run { state = State.WATCH; return }
        if (uci.length < 4) { state = State.WATCH; return }
        val fromName = uci.substring(0, 2).uppercase()
        var toName = uci.substring(2, 4).uppercase()

        // ── v1.13.7 出手前实体校验（幽灵步终极防线，用户 9/10 04:14 症状根治）──
        // extBoard 幽灵步（系统性误检连双帧确认都骗过）会让引擎算出"点空格/点对方子"
        // 的步——点击无效→verify 重试→乱点。守卫：from 格在最近识别里【明确不是我方
        // 棋子】（对方子/异色）→ 拒绝出手+触发重同步；识别为空（丢检噪声）放行。
        // 这是物理层最后防线，任何状态机错误都过不去这关。
        try {
            val cellIdx = cellIndexOf(rec, fromName)
            if (cellIdx != null) {
                val t = rec.types[cellIdx.first][cellIdx.second]
                val isMyPiece = if (mySide == Side.WHITE) t in "PNBRQK" else t in "pnbrqk"
                if (t != '?' && t != '.' && !isMyPiece) {
                    state = State.WATCH
                    verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                    lastSentMove = null
                    resetCandidateFen = placementOf(extBoard.fen)   // 触发同步自愈链
                    resetCandidateCount = 1
                    mainHandler.post { tvStatus?.text = "拒走 $uci（$fromName 非我方子）·重同步" }
                    return
                }
            }
        } catch (_: Throwable) {}
        // ── 王车易位：各对弈 App 点击目标不统一 ──
        // 有的认"王→目标格"（e1→g1，即王移动方向的两格），有的认"王→车"（e1→h1）。
        // 策略（用户实测经验）：首次点目标格；闭环验证失败重试时换点车，再失败换回目标格……交替适应所有软件。
        val isCastle = fromName[0] == 'E' && (toName[0] == 'G' || toName[0] == 'C') && fromName[1] == toName[1]
        val castleTapRook = isCastle && tapAttempts % 2 == 1   // 奇数次尝试 → 点车
        if (castleTapRook) toName = (if (toName[0] == 'G') "H" else "A") + toName[1]
        val from = squareToScreen(rec, rect, fromName)
        val to = squareToScreen(rec, rect, toName)
        if (from == null || to == null) { state = State.WATCH; return }

        // 关键：识别帧是半分辨率，无障碍点击是真实屏幕坐标。
        // 用"真实物理屏 ÷ 实际捕获帧尺寸"精确换算（不用存储的 screenWidth，杜绝任何尺寸来源误差）
        val capW = lastCapW.coerceAtLeast(1)
        val capH = lastCapH.coerceAtLeast(1)
        val sx = realScreenWidth.toFloat() / capW
        val sy = realScreenHeight.toFloat() / capH
        val fromX = from.first * sx; val fromY = from.second * sy
        val toX = to.first * sx; val toY = to.second * sy

        val isPromo = uci.length >= 5
        pendingPromo = if (isPromo) fromName to toName else null

        tvStatus?.text = if (isCastle)
            "易位 $uci → ${if (castleTapRook) "点车(${toName})" else "点目标格(${toName})"}"
        else
            "落子 $uci → 点击(${fromX.toInt()},${fromY.toInt()})"
        // v2.0 AC3.0 三元组登记：点击前基准 + 预期后基准（确定性比较的根基）
        if (verifyUci == null) {
            verifyPrePlacement = placementOf(extBoard.fen)
            verifyUci = uci
            clickBeforeSig = placementOf(extBoard.fen)
            // 预期后 = extBoard 试走该步（棋盘事实提前算好——屏幕对照用）
            try {
                val b2 = Board(); b2.loadFromFen(extBoard.fen)
                val mv2 = moveFromUci(b2, uci)
                if (mv2 != null) b2.doMove(mv2)
                clickAfterSig = placementOf(b2.fen)
                clickAfterUci = uci
            } catch (_: Throwable) { clickAfterSig = ""; clickAfterUci = uci }
        }
        val secondDelay = when {
            tapAttempts > 0 -> if (paceTurbo) 100L else 180L   // v2.0 提速：重试稍慢（对方App忙）
            else -> if (paceTurbo) 50L else 70L   // v2.0 提速（用户实测 2s/步→目标 <0.5s）
        }
        lastTapAt = android.os.SystemClock.elapsedRealtime()   // 判边校准禁区锚点：点过屏幕=那步是我们的
        val ok = ChessAccessibilityService.tapScreen(fromX, fromY) { tapped ->
            if (!tapped) {
                mainHandler.post { tvStatus?.text = "点击被系统取消！检查无障碍服务是否被系统杀掉" }
            }
        }
        if (!ok) {
            tvStatus?.text = "无障碍服务未开启！"
            state = State.IDLE
            return
        }
        mainHandler.postDelayed({
            ChessAccessibilityService.tapScreen(toX, toY)
            state = State.WATCH
            val tNow = android.os.SystemClock.elapsedRealtime()
            if (isPromo) {
                // 升变：点完 from/to 后对弈 App 弹选择框——不再盲点！
                // 进入视觉选择流程（tick→handlePromotionPicker：模型找弹框里的皇后）
                promoPhase = PromoPhase.PICKING
                promoDeadline = tNow + 12000
                promoGraceUntil = tNow + 2500
                promoTaps = 0
                promoToScreen = toX to toY
                verifyDeadline = tNow + 16000 + 1000L * tapAttempts
            } else {
                verifyDeadline = tNow + 3500 + 1000L * tapAttempts
            }
        }, secondDelay)
    }

    /** 棋盘坐标 → 屏幕坐标（优先网格线性拟合=亚像素精度，回退矩形均分） */
    /** v1.14.2 实体校验：标准格名（如 E2）→ 识别网格 (row, col) 索引 */
    private fun cellIndexOf(rec: BoardDetector.Recognition, squareName: String): Pair<Int, Int>? {
        return try {
            val f = squareName[0] - 'A'
            val r = squareName[1] - '1'
            if (f !in 0..7 || r !in 0..7) return null
            // 逆映射 squareAt：白在底 → 屏幕 row0=rank8
            val col: Int; val row: Int
            if (rec.whiteAtBottom) { col = f; row = 7 - r } else { col = 7 - f; row = r }
            row to col
        } catch (_: Exception) { null }
    }

    /** v3.1.1 王重复仲裁（用户 9/15 14:53"白王2个·推算失败"根治）：王在对局中
     *  永不新增——K/k 出现 2 个时，非锚点王位的那个必是误判（复刻实锤：rect 偏
     *  ±2px 时黑王被读成白王）。锚点王位=真王位；误判格按锚点同格类型改判（锚点
     *  该格是什么就改成什么，含清空——锚点王位若也读错由 repairKings 补全闭环）。 */
    private fun arbitrateKingsFromAnchor(rec: BoardDetector.Recognition): BoardDetector.Recognition? {
        val anchor = lastAcceptedSig.ifEmpty { placementOf(extBoard.fen) }
        if (anchor.isEmpty()) return null
        val anchorCells = ChessSyncRules.expandPlacement(anchor) ?: return null
        // 锚点王位（file, rank）
        var wk: Pair<Int, Int>? = null
        var bk: Pair<Int, Int>? = null
        for (i in 0 until 64) {
            if (anchorCells[i] == 'K') wk = (i % 8) to (7 - i / 8)
            if (anchorCells[i] == 'k') bk = (i % 8) to (7 - i / 8)
        }
        // 数当前识别的王数——只有"王多了"才仲裁（丢了走 repairKings）
        val ks = ArrayList<Pair<Int, Int>>()   // 屏幕坐标 (row, col)
        val kks = ArrayList<Pair<Int, Int>>()
        for (r in 0 until 8) for (c in 0 until 8) {
            if (rec.types[r][c] == 'K') ks.add(r to c)
            if (rec.types[r][c] == 'k') kks.add(r to c)
        }
        val wkScreen = wk?.let { cellIndexOf(rec, "${'A' + it.first}${'1' + it.second}") }
        val bkScreen = bk?.let { cellIndexOf(rec, "${'A' + it.first}${'1' + it.second}") }
        val needArbK = ks.size > 1 && wkScreen != null
        val needArbKk = kks.size > 1 && bkScreen != null
        if (!needArbK && !needArbKk) return null
        val types = Array(8) { r -> rec.types[r].clone() }
        val cellsArr = Array(8) { r -> rec.cells[r].clone() }
        var fixed = false
        for ((all, kingScreen) in listOf(ks to wkScreen, kks to bkScreen)) {
            if (kingScreen != null && all.size > 1) {
                for ((row, col) in all) {
                    if (row == kingScreen.first && col == kingScreen.second) continue   // 真王位保留
                    // 误判格：改判为锚点同格类型
                    val name = try { BoardDetector.squareAt(rec, row, col).name } catch (_: Exception) { continue }
                    val f = name[0] - 'A'; val r2 = name[1] - '1'
                    if (f !in 0..7 || r2 !in 0..7) continue
                    val idx = (7 - r2) * 8 + f
                    val correct = anchorCells[idx]
                    if (types[row][col] != correct) {
                        types[row][col] = correct
                        cellsArr[row][col] = when {
                            correct == '.' -> BoardDetector.CellState.EMPTY
                            correct.isUpperCase() -> BoardDetector.CellState.WHITE
                            else -> BoardDetector.CellState.BLACK
                        }
                        fixed = true
                    }
                }
            }
        }
        if (!fixed) return null
        return try {
            rec.copy(types = types, cells = cellsArr)
        } catch (_: Throwable) { null }
    }

    /** v3.1.7 锚点引导推算（用户 22:26"29子王数正常却推算失败"根治）：
     *  识别帧大部分正确（如 29/32）但局部错读触发 inferFen 拒绝时，用锚点
     *  （lastAcceptedSig/extBoard——只经合法步更新，AC3.0 吸收门保证不被污染）
     *  枚举全部合法单步，与识别帧逐格对照：差异 ≤2 格（类型抖动级）的解释 =
     *  该步真实发生。返回"锚点+该步"的 FEN（错读格被棋盘事实纠正）。
     *  学界依据：arXiv 2310.04086 增量识别；AC3.0 同哲学（对不上不猜，但 29/32
     *  正确时"最近的合法解释"在数学上近乎唯一）。仅在推算失败后触发——正常帧
     *  走 2 格铁律路径，行为不变。 */
    private fun anchorGuidedInfer(rec: BoardDetector.Recognition): String? {
        return try {
            val anchorSig = lastAcceptedSig.ifEmpty { placementOf(extBoard.fen) }
            if (anchorSig.isEmpty()) return null
            // 识别帧的 64 格字符表（屏幕 row0=顶）
            val obsGrid = Array(8) { r -> CharArray(8) { c -> rec.types[r][c] } }
            var bestFen: String? = null
            var bestDiff = 3   // 容忍上限=2 格类型抖动
            // 双轮次枚举（锚点后可能轮对方也可能轮我——verify 场景）
            val boards = ArrayList<Board>(2)
            boards.add(extBoard)
            ChessSyncRules.flipStm(extBoard)?.let { boards.add(it) }
            for (b in boards) {
                val moves = try { b.legalMoves() } catch (_: Throwable) { emptyList<com.github.bhlangonijr.chesslib.move.Move>() }
                for (m in moves) {
                    try {
                        b.doMove(m)
                        val after = placementOf(b.fen)
                        b.undoMove()
                        if (after.isEmpty()) continue
                        // 差异计数：锚点+步 后摆位 vs 识别帧
                        var d = 0
                        var ok = true
                        val afterCells = ChessSyncRules.expandPlacement(after) ?: continue
                        val anchorCells = ChessSyncRules.expandPlacement(anchorSig) ?: continue
                        for (i in 0 until 64) {
                            // 摆位索引 i（FEN 序 rank8→1，file a→h）→ 屏幕 (row,col)
                            // 白在底：row=i/8, col=i%8；黑在底（执黑视角）：镜像
                            val row = if (rec.whiteAtBottom) i / 8 else 7 - i / 8
                            val col = if (rec.whiteAtBottom) i % 8 else 7 - i % 8
                            val ch = afterCells[i]
                            if (ch != obsGrid[row][col]) {
                                d++
                                if (d > bestDiff) { ok = false; break }
                            }
                        }
                        if (ok && d < bestDiff) {
                            bestDiff = d
                            b.doMove(m)
                            bestFen = b.fen
                            b.undoMove()
                        }
                    } catch (_: Exception) {
                        try { b.undoMove() } catch (_: Exception) {}
                    }
                }
            }
            bestFen
        } catch (_: Throwable) { null }
    }

    /** v3.2.0 预期盘基准的锚点引导匹配（verify 快速通道 ② 噪声容忍版）：
     *  基准=Board 实例（verify 场景=预期盘 ClickAfter），枚举其全部合法单步，
     *  "基准+某步"与识别摆位差异 ≤2 格的最小解释=对方真实步。verify 在途=我方
     *  点击物理先验（AC3.0 ClickAfter 语义），单帧闭环不双帧确认（与旧通道一致）。 */
    private fun anchorGuidedMatchOnBoard(base: Board, obs: String): Pair<com.github.bhlangonijr.chesslib.move.Move, String>? {
        return try {
            var best: Pair<com.github.bhlangonijr.chesslib.move.Move, String>? = null
            var bestDiff = 2   // 噪声容忍上限（对方步 2 格+0 噪声=精确；1 格类型错读=容忍）
            val moves = try { base.legalMoves() } catch (_: Throwable) { emptyList<com.github.bhlangonijr.chesslib.move.Move>() }
            for (m in moves) {
                try {
                    base.doMove(m)
                    val after = placementOf(base.fen)
                    base.undoMove()
                    if (after.isEmpty()) continue
                    val d = diffCells(after, obs)
                    if (d < bestDiff) {
                        bestDiff = d
                        best = m to after
                    }
                } catch (_: Exception) {
                    try { base.undoMove() } catch (_: Exception) {}
                }
            }
            best
        } catch (_: Throwable) { null }
    }

    /** v3.1.9 锚点引导匹配（用户 00:14"对方步+噪声 3-4 格 diff 过不了 2 格铁律"
     *  卡死根治）：锚点枚举全部合法单步，"锚点+某步"与识别摆位逐格对照，差异
     *  ≤2 格（纯噪声级）的最小解释 = 真相。误匹配概率平方级小（AI 验证 +
     *  arXiv 2310.04086 增量识别标准）。产物走双帧确认管线（pendingStepConfirm
     *  复现检查）——识别噪声抖动下帧必变、真步稳定复现，幽灵步双防线不变。 */
    private fun anchorGuidedMatch(anchorSig: String, obs: String): Pair<com.github.bhlangonijr.chesslib.move.Move, String>? {
        return try {
            val base = ChessSyncRules.buildBoard(anchorSig, true)
                ?: ChessSyncRules.buildBoard(anchorSig, false)
                ?: return null
            val boards = ArrayList<Board>(2)
            boards.add(base)
            ChessSyncRules.flipStm(base)?.let { boards.add(it) }
            var best: Pair<com.github.bhlangonijr.chesslib.move.Move, String>? = null
            var bestDiff = 2   // 噪声容忍上限
            for (b in boards) {
                val moves = try { b.legalMoves() } catch (_: Throwable) { emptyList<com.github.bhlangonijr.chesslib.move.Move>() }
                for (m in moves) {
                    try {
                        b.doMove(m)
                        val after = placementOf(b.fen)
                        b.undoMove()
                        if (after.isEmpty()) continue
                        val d = diffCells(after, obs)
                        if (d < bestDiff) {
                            bestDiff = d
                            best = m to after
                        }
                    } catch (_: Exception) {
                        try { b.undoMove() } catch (_: Exception) {}
                    }
                }
            }
            best
        } catch (_: Throwable) { null }
    }

    /** v1.14.3 王位补全修复（用户 9/12 01:36 死锁链破案）：锚点为空（首帧
     *  推算失败→anchor 永不建立→补全永不生效→永久瘫痪）——锚源兜底 extBoard
     *  （连线默认构造=标准开局，王 e1/e8；追平后=最新共识局面）——补全永远可用。 */
    private fun repairKingsFromAnchor(rec: BoardDetector.Recognition): BoardDetector.Recognition? {
        val anchor = lastAcceptedSig.ifEmpty { placementOf(extBoard.fen) }
        if (anchor.isEmpty()) return null
        val cells = ChessSyncRules.expandPlacement(anchor) ?: return null
        // 摆位展开序=FEN 序（rank8→rank1，file a→h）：索引 i → file=i%8, rank=7-i/8
        var wk: Pair<Int, Int>? = null   // (file, rank) 标准坐标
        var bk: Pair<Int, Int>? = null
        for (i in 0 until 64) {
            if (cells[i] == 'K') wk = (i % 8) to (7 - i / 8)
            if (cells[i] == 'k') bk = (i % 8) to (7 - i / 8)
        }
        if (wk == null && bk == null) return null
        // 深拷贝 types 修王
        val types = Array(8) { r -> rec.types[r].clone() }
        var fixed = false
        listOf('K' to wk, 'k' to bk).forEach { (ch, pos) ->
            if (pos != null) {
                val name = "${'A' + pos.first}${'1' + pos.second}"
                cellIndexOf(rec, name)?.let { (row, col) ->
                    if (types[row][col] != ch) { types[row][col] = ch; fixed = true }
                }
            }
        }
        if (!fixed) return null
        // 重建 Recognition（copy cells 同步 WHITE/BLACK 王占位）
        val cellsArr = Array(8) { r -> rec.cells[r].clone() }
        wk?.let { p -> cellIndexOf(rec, "${'A' + p.first}${'1' + p.second}")?.let { (row, col) -> cellsArr[row][col] = BoardDetector.CellState.WHITE } }
        bk?.let { p -> cellIndexOf(rec, "${'A' + p.first}${'1' + p.second}")?.let { (row, col) -> cellsArr[row][col] = BoardDetector.CellState.BLACK } }
        return try {
            rec.copy(types = types, cells = cellsArr)
        } catch (_: Throwable) { null }
    }

    private fun squareToScreen(rec: BoardDetector.Recognition, rect: BoardDetector.BoardRect, squareName: String): Pair<Float, Float>? {
        val f = squareName[0] - 'A'
        val r = squareName[1] - '1'
        // 逆映射 squareAt
        val col: Int; val row: Int
        if (rec.whiteAtBottom) { col = f; row = 7 - r } else { col = 7 - f; row = r }
        val fx = rec.fitX
        val fy = rec.fitY
        if (fx != null && fy != null) {
            // 拟合网格线：32 个棋子中心最小二乘出的直线，比"框宽÷8"准得多
            return (fx[0] * col + fx[1]) to (fy[0] * row + fy[1])
        }
        return rect.centerOf(col, row)
    }

    // ─────────────── 「思考」按钮：无限分析，只算不下 ───────────────

    /** 思考状态：OFF 未思考 / RUNNING 思考中 / STOPPING 暂停中（等 bestmove 收尾） */
    private enum class ThinkPhase { OFF, RUNNING, STOPPING }
    @Volatile private var thinkPhase = ThinkPhase.OFF
    /** 引擎自愈重启计数（v1.10.0） */
    private var engineRestarts = 0
    /** 自愈进行中互斥（v1.10.7：防死亡通知成簇触发重建风暴） */
    @Volatile private var healingNow = false
    /** 引擎最后一条 info 到达时刻（看门狗心跳） */
    @Volatile private var lastEngineInfoAt = 0L
    /** 引擎是否有 go infinite 在跑（思考/监视中）——看门狗只在这种状态下管假死 */
    @Volatile private var engineSearching = false
    /** v3.1.5 引擎搜索开始时刻（engineSearching 卡死看门狗基准） */
    @Volatile private var searchStartedAt = 0L

    /** 引擎看门狗（v1.12.0 重写，鹏飞 _checkEngineTimerAction 精确复刻）：
     *  根本稳定支柱②——每 5 秒向引擎发 UCI 标准探活命令 isready：
     *  · 管道持续活跃 → 进程组永不被系统判定"闲置冻结"
     *  · 引擎必回 readyok → listener.onReady() 刷新心跳
     *  · 15 秒无任何回包（连探活都哑）→ 才走重启链（此时是真死了）
     *  这不是"自愈补丁"：探活命令流让假死无从发生。 */
    private fun scheduleEngineWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    val e = engine
                    if (e != null && e.isRunning() && healingNow.not()) {
                        e.send("isready")   // 保活+探活（UCI 标准，任何状态可发）
                        val now = android.os.SystemClock.elapsedRealtime()
                        val silent = if (lastEngineInfoAt == 0L) 0L else now - lastEngineInfoAt
                        if (silent > 15_000) {
                            mainHandler.post { tvStatus?.text = "引擎无响应 ${silent/1000}秒 · 重启中…" }
                            onEngineDead()
                        }
                        // v3.1.5（云手机实测 9/16 16:40 破案："我方回合·引擎思考中"永不出手）：
                        // 引擎进程活着、info 持续输出（silent 不触发）——但 bestmove 回调
                        // 丢失 → engineSearching 卡 true → 自愈条件 !engineSearching 永不满足
                        // → "思考中"死锁。修复：engineSearching 持续超 60 秒（远超思考上限
                        // 3 秒+缓冲）仍无 bestmove = 回调链死 → 强制复位 + stop 引擎 + 回
                        // WATCH 让 maybeAutoPlay 幂等重试。这是结构级兜底，不是猜测修复。
                        if (engineSearching && searchStartedAt > 0L && now - searchStartedAt > 60_000) {
                            mainHandler.post { tvStatus?.text = "思考回调丢失 · 强制复位重试" }
                            try { e.send("stop") } catch (_: Throwable) {}
                            engineSearching = false
                            searchStartedAt = 0L
                        }
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    // ─────────────── 「我的库」记录模式（v1.10.2 整谱收集）───────────────
    /** 记录模式：引擎只思考不代走，双方着法依屏幕追平入谱（用户口述需求） */
    @Volatile private var recordMode = false
    /** 当前谱已记录手数 */
    private var recordMoveCount = 0
    /** 当前谱数据（起始局面 + 着法序列）——换题/关闭时封谱入库 */
    private var recordStartPlacement: String? = null
    private var recordStartWhite = true
    private val recordMoves = ArrayList<String>()
    /** 上次思考建议的局面（防重发：局面没变不重复 goInfinite） */
    @Volatile private var lastThinkFen: String? = null
    /** 记录模式下被拦截的思考：引擎仍思考显示建议（只算不下） */
    private fun thinkForDisplay() {
        try {
            val e = engine ?: return
            if (!e.isRunning()) return
            if (extBoard.sideToMove != mySide) return
            val fen = extBoard.fen
            if (fen == lastThinkFen) return   // 同局面不重发（防搜索重置风暴）
            lastThinkFen = fen
            engineSearching = true; searchStartedAt = android.os.SystemClock.elapsedRealtime(); lastEngineInfoAt = searchStartedAt
            e.goInfinite(fen)
        } catch (_: Throwable) {}
    }

    /** 封存当前谱入库（换题或记录结束时调） */
    private fun sealRecordLine(reasonHuman: String) {
        val start = recordStartPlacement
        if (start != null && recordMoves.isNotEmpty()) {
            val line = MyBook.BookLine(
                title = reasonHuman,
                startPlacement = start,
                startWhite = recordStartWhite,
                moves = recordMoves.toCollection(ArrayList())
            )
            MyBook.putLine(line)
        }
        recordStartPlacement = null
        recordMoves.clear()
        recordMoveCount = 0
    }
    @Volatile private var thinkFen: String? = null

    /** 主线程/工作线程均可调：切换思考开/停 */
    fun toggleThink() {
        when (thinkPhase) {
            ThinkPhase.RUNNING -> {
                thinkPhase = ThinkPhase.STOPPING
                engine?.stop()
                mainHandler.post {
                    btnFloatThinkRef?.text = "思考"
                    tvStatus?.text = "思考已暂停"
                }
            }
            else -> startThink()
        }
    }

    /** 抓当前屏幕局面并启动无限分析（工作线程） */
    private fun startThink() {
        try {
            startThinkInternal()
        } catch (t: Throwable) {
            thinkPhase = ThinkPhase.OFF
            mainHandler.post {
                btnFloatThinkRef?.text = "思考"
                tvStatus?.text = "思考启动失败：${t.javaClass.simpleName}"
            }
        }
    }

    private fun startThinkInternal() {
        val yolo = YoloChessDetector.get()
        if (yolo == null) {
            mainHandler.post { tvStatus?.text = "AI 识别引擎未就绪，正在加载…" }
            YoloChessDetector.ensureLoaded(this) { ok, err ->
                mainHandler.post {
                    tvStatus?.text = if (ok) "AI 就绪，请再点一次思考" else "AI 模型加载失败: $err"
                }
            }
            return
        }
        val bmp = capture() ?: run {
            mainHandler.post { tvStatus?.text = "无法截取屏幕，稍后重试" }
            return
        }
        try {
            val rec = try { recognizeScreen(bmp, yolo) }
            catch (t: Throwable) {
                // 异常必须上屏：静默吞掉=用户只看到"未检测到棋盘"却无从定位（v1.4.4 的教训）
                mainHandler.post { tvStatus?.text = "识别异常 ${t.javaClass.simpleName}" }
                return
            }
            if (rec == null) {
                mainHandler.post { tvStatus?.text = "未检测到棋盘，切到对弈界面再试" }
                return
            }
            boardRect = rec.rect
            lastRec = rec
            val fen = BoardDetector.inferFen(rec) ?: run {
                mainHandler.post {
                    tvStatus?.text = "识别${rec.pieceCount}子，局面推算失败"
                    tvPv?.text = BoardDetector.asciiBoard(rec)
                }
                return
            }
            thinkingToken++               // 作废在途对弈请求回调
            thinkFen = fen
            thinkPhase = ThinkPhase.RUNNING
            engine?.setSkillLevel(20)
            engineSearching = true; searchStartedAt = android.os.SystemClock.elapsedRealtime(); lastEngineInfoAt = searchStartedAt
            engine?.goInfinite(fen)
            mainHandler.post {
                btnFloatThinkRef?.text = "暂停"
                tvStatus?.text = "思考中（不限时长）· 再点暂停"
                tvEval?.text = ""
            }
        } finally {
            bmp.recycle()
        }
    }

    /** 局面变化时跟随重启分析（连线思考/对局进行中自动同步新局面） */
    private fun followThinkIfNeeded(fenNow: String) {
        if (thinkPhase != ThinkPhase.RUNNING) return
        if (fenNow == thinkFen) return
        thinkFen = fenNow
        thinkingToken++
        engineSearching = true; searchStartedAt = android.os.SystemClock.elapsedRealtime(); lastEngineInfoAt = searchStartedAt
        engine?.goInfinite(fenNow)
    }

    // ─────────────── 引擎回调 ───────────────

    /** 引擎 readyok 探活回包（v1.12.0 支柱②心跳源——isready 的应答刷心跳） */
    override fun onReady() {
        lastEngineInfoAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onInfo(info: StockfishEngine.Info) {
        lastEngineInfoAt = android.os.SystemClock.elapsedRealtime()   // 看门狗心跳
        mainHandler.post {
            // 分数统一转白方视角显示（引擎分数是"行棋方视角"，直接显示会在对方回合符号颠倒——
            // 用户看到的"大优突然变负"一半是这个显示 bug）
            val whiteToMove = when {
                thinkPhase == ThinkPhase.RUNNING -> thinkFen?.contains(" w ") ?: true
                else -> extBoard.sideToMove == Side.WHITE
            }
            val score = when {
                info.scoreMate != null -> {
                    val m = info.scoreMate!!
                    val mWhite = if (whiteToMove) m else -m
                    "白方${if (mWhite > 0) "+" else ""}${mWhite}步杀"
                }
                info.scoreCp != null -> {
                    val cp = info.scoreCp!!
                    val cpWhite = if (whiteToMove) cp else -cp
                    String.format("%+.2f", cpWhite / 100.0)
                }
                else -> ""
            }
            if (score.isNotEmpty()) tvEval?.text = "评估 $score（白方视角） · 深度 ${info.depth}"
            if (info.pv.isNotEmpty()) {
                tvDepthReached?.text = "d${info.depth}"
                tvPv?.text = "走法 " + info.pv.take(6).joinToString(" ")
            }
        }
    }

    override fun onBestMove(bestMove: String?, ponder: String?) {
        engineSearching = false
        searchStartedAt = 0L
        // 无限思考的暂停收尾：显示结论并复位按钮
        if (thinkPhase == ThinkPhase.STOPPING) {
            thinkPhase = ThinkPhase.OFF
            val lastInfo = engine?.lastInfo
            mainHandler.post {
                btnFloatThinkRef?.text = "思考"
                val depth = lastInfo?.depth?.let { "深度$it" } ?: ""
                val best = bestMove ?: "—"
                val pv = lastInfo?.pv?.take(4)?.joinToString(" ") ?: ""
                tvStatus?.text = "思考暂停 · $depth · 最佳 $best $pv"
            }
        }
        // RUNNING 时收到 bestmove = 旧对弈请求尾流，忽略
    }

    override fun onEngineDead() {
        // v1.10.7：自愈互斥闸——引擎死亡通知可能成簇到达（旧实例退出+自愈过程再触发），
        // 重复执行"quit+重建"会互相踩（重建中被 quit → 永远起不来）。
        if (healingNow) return
        healingNow = true
        // v1.10.6：思考状态保留（旧版无条件 OFF → 用户点思考后引擎死过一次，
        // "显示思考"却永远待机——现在 thinkPhase 原样保留，自愈后自动恢复思考）
        val resumeThink = thinkPhase == ThinkPhase.RUNNING
        val resumeFen = thinkFen
        val wasRunning = running
        mainHandler.post {
            btnFloatThinkRef?.text = if (resumeThink) "恢复中…" else "思考"
            tvStatus?.text = "引擎异常 · 自动重启中…"
        }
        workHandler.post {
            try {
                val dead = engine
                if (dead != null) { try { dead.quit() } catch (_: Throwable) {} }
                engine = null
                if (engineRestarts < 5) {
                    engineRestarts++
                    val e = StockfishEngine(id = "auto-$engineRestarts", listener = this)
                    val ok = e.start(this)
                    engine = if (ok) e else null
                    mainHandler.post {
                        tvStatus?.text = if (ok) "引擎已重启（第${engineRestarts}次）· 接续中"
                        else "引擎重启失败，请检查存储空间后重试"
                        btnFloatThinkRef?.text = if (ok && resumeThink) "暂停" else "思考"
                    }
                    if (ok) {
                        // v1.10.6 恢复链（按优先级）：
                        // 1) 死前在思考 → NNUE 就绪后自动重发思考局面（goInfinite 内部挂起）
                        if (resumeThink && resumeFen != null) {
                            thinkPhase = ThinkPhase.RUNNING
                            engineSearching = true; searchStartedAt = android.os.SystemClock.elapsedRealtime(); lastEngineInfoAt = searchStartedAt
                            (engine as? StockfishEngine)?.goInfinite(resumeFen)
                        }
                        // 2) 连线中 → 接续对局/监视
                        else if (wasRunning) {
                            if (state == State.THINK || state == State.WATCH) {
                                if (extBoard.sideToMove == mySide) maybeAutoPlay()
                                else {
                                    engineSearching = true; searchStartedAt = android.os.SystemClock.elapsedRealtime(); lastEngineInfoAt = searchStartedAt
                                    (engine as? StockfishEngine)?.goInfinite(extBoard.fen)
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                engine = null
            } finally {
                // 重启完成后延迟解锁（吸收残余死亡通知的余波）
                mainHandler.postDelayed({ healingNow = false }, 1500)
            }
        }
    }

    // ─────────────── 手动棋盘校准 ───────────────

    private fun calibPrefs() = getSharedPreferences("board_calib", Context.MODE_PRIVATE)

    /** 已保存的真实屏幕校准矩形（无则 null） */
    private fun loadCalibRealRect(): android.graphics.RectF? {
        val p = calibPrefs()
        if (!p.getBoolean("valid", false)) return null
        val l = p.getFloat("l", -1f); val t = p.getFloat("t", -1f)
        val r = p.getFloat("r", -1f); val b = p.getFloat("b", -1f)
        if (l < 0 || t < 0 || r - l < 200 || b - t < 200) return null
        return android.graphics.RectF(l, t, r, b)
    }

    /** 校准矩形（真实屏px）→ 当前捕获帧坐标 BoardRect；未校准返回 null */
    private fun calibFrameRect(): BoardDetector.BoardRect? {
        val real = loadCalibRealRect() ?: return null
        val capW = lastCapW.coerceAtLeast(1)
        val capH = lastCapH.coerceAtLeast(1)
        val sx = realScreenWidth.toFloat() / capW
        val sy = realScreenHeight.toFloat() / capH
        val left = (real.left / sx).toInt()
        val top = (real.top / sy).toInt()
        val cell = ((real.width() / sx) / 8f).toInt().coerceAtLeast(4)
        return BoardDetector.BoardRect(left, top, cell)
    }

    /** 打开校准覆盖层：8×8 网格拖拽对齐真实棋盘 */
    private fun showCalibration() {
        if (calibrating) return
        calibrating = true
        mainHandler.post {
            try {
                val ctx = android.view.ContextThemeWrapper(this, R.style.Theme_AutoChess_Float)
                val container = android.widget.FrameLayout(ctx)

                val grid = CalibrationView(ctx)
                val init = loadCalibRealRect() ?: run {
                    // 未校准过：用最近识别的棋盘矩形换算到真实屏；再不行给默认居中
                    val br = boardRect
                    if (br != null && screenWidth > 0) {
                        val sx = realScreenWidth.toFloat() / screenWidth
                        val sy = realScreenHeight.toFloat() / screenHeight
                        val size = br.cell * 8f
                        android.graphics.RectF(
                            br.left * sx, br.top * sy,
                            (br.left + size) * sx, (br.top + size) * sy)
                    } else {
                        val w = realScreenWidth * 0.88f
                        val left = (realScreenWidth - w) / 2f
                        android.graphics.RectF(left, realScreenHeight * 0.28f, left + w, realScreenHeight * 0.28f + w)
                    }
                }
                grid.setInitial(init.left, init.top, init.right, init.bottom)
                container.addView(grid, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))

                // 底部确认/取消按钮栏
                val bar = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(24, 16, 24, 40)
                    setBackgroundColor(0xE61A2030.toInt())
                }
                val btnOk = android.widget.Button(ctx).apply {
                    text = "✓ 确认校准"
                    textSize = 15f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnCancel = android.widget.Button(ctx).apply {
                    text = "取消"
                    textSize = 15f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                btnOk.setOnClickListener {
                    val r = android.graphics.RectF(grid.boardRect)
                    calibPrefs().edit()
                        .putBoolean("valid", true)
                        .putFloat("l", r.left).putFloat("t", r.top)
                        .putFloat("r", r.right).putFloat("b", r.bottom)
                        .apply()
                    hideCalibration()
                    val w = (r.right - r.left).toInt()
                    val h = (r.bottom - r.top).toInt()
                    mainHandler.post {
                        tvStatus?.text = "校准完成 ${w}×${h} · 连线中"
                    }
                }
                btnCancel.setOnClickListener { hideCalibration() }
                bar.addView(btnOk)
                bar.addView(btnCancel)
                container.addView(bar, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM))

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT)
                windowManager.addView(container, params)
                calibOverlay = container
            } catch (t: Throwable) {
                calibrating = false
                try {
                    android.widget.Toast.makeText(this, "校准界面打开失败：${t.message}", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hideCalibration() {
        try { calibOverlay?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        calibOverlay = null
        calibrating = false
    }

    // ─────────────── 悬浮窗 ───────────────

    /** 悬浮窗显示的安全包装：addView 失败（权限被收回/ROM限制）不崩溃，服务继续靠通知栏运行 */
    private fun showFloatingPanel() {
        try {
            showFloatingPanelInternal()
        } catch (t: Throwable) {
            panelView = null
            // 详情落盘，便于远程排查（crash 目录与主 App 共享）
            try {
                val dir = java.io.File(getExternalFilesDir(null), "crash")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, "float_error.txt").writeText(
                    "time=${System.currentTimeMillis()}\n${t.stackTraceToString()}")
            } catch (_: Throwable) {}
            try {
                android.widget.Toast.makeText(this, "悬浮窗显示失败：${t.message}，请检查悬浮窗权限", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showFloatingPanelInternal() {
        // Service 上下文没有 Material 主题，必须包装主题再 inflate，
        // 否则布局里的主题属性（?attr/...）解析直接抛异常
        val themedCtx = android.view.ContextThemeWrapper(this, R.style.Theme_AutoChess_Float)
        // 悬浮窗样式：经典面板 / 鹏飞竖条（设置→悬浮窗样式 切换，重开悬浮窗生效）
        val stylePref = getSharedPreferences("engine_settings", MODE_PRIVATE)
            .getString("float_style", "classic")
        val layoutId = if (stylePref == "strip") R.layout.floating_panel_strip else R.layout.floating_panel
        val view = LayoutInflater.from(themedCtx).inflate(layoutId, null)
        panelView = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 220
        }
        windowManager.addView(view, params)

        tvStatus = view.findViewById(R.id.tvFloatStatus)
        tvEval = view.findViewById(R.id.tvFloatEval)
        tvPv = view.findViewById(R.id.tvFloatPv)
        try { tvAudit = view.findViewById(R.id.tvFloatAudit) } catch (_: Throwable) { tvAudit = null }
        tvDepthReached = view.findViewById(R.id.tvDepthReached)
        statusDot = view.findViewById(R.id.statusDot)
        // ── 控件绑定（每段独立 try：任何一段失败不拖垮其余按钮——
        //    v1.6.0 教训：btnConnect 类型不匹配 CCE 把后面所有监听全跳过了）──
        try {
            btnConnect = view.findViewById(R.id.btnFloatConnect)
        } catch (_: Throwable) { btnConnect = null }
        try {
            btnFloatThinkRef = view.findViewById(R.id.btnFloatThink)
        } catch (_: Throwable) { btnFloatThinkRef = null }
        rgSide = view.findViewById(R.id.rgSide)

        // 拖拽（v1.12.0 用户拍板）：横条样式=仅纵向可拖（鹏飞横条行为——水平钉死，
        // 上下自适应避挡）；经典面板=全向拖动
        val isStrip = stylePref == "strip"
        val header = view.findViewById<View>(R.id.floatHeader)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 3 || abs(dy) > 3) moved = true
                    if (isStrip) {
                        // 横条：只动 y（水平位置固定）
                        params.y = (startY + dy.toInt()).coerceIn(0, (realScreenHeight - 160).coerceAtLeast(0))
                    } else {
                        params.x = (startX + dx.toInt()).coerceIn(0, (realScreenWidth - 120).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (realScreenHeight - 160).coerceAtLeast(0))
                    }
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // ── 整个面板可拖动（v1.7.0）──
        // 事件派发顺序：按钮等可点击子控件先消费（点击不受影响），
        // 落在空白/信息区的事件才会到面板根 → 根消费 DOWN 拿到手势 → 任意空白处拖动
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    if (moved) {
                        if (isStrip) {
                            // 横条：只动 y（v1.12.0 用户拍板：左右钉死、上下可移）
                            params.y = (startY + dy.toInt()).coerceIn(0, (realScreenHeight - 160).coerceAtLeast(0))
                        } else {
                            params.x = (startX + dx.toInt()).coerceIn(0, (realScreenWidth - 120).coerceAtLeast(0))
                            params.y = (startY + dy.toInt()).coerceIn(0, (realScreenHeight - 160).coerceAtLeast(0))
                        }
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 未构成拖动的按下（点了空白处）不算点击，仅吞掉
                    moved
                }
                else -> false
            }
        }

        // ── 监听绑定（分段 try，单点失败不连坐）──
        try {
            // 最小化：横向条样式 → 仅折叠信息行（按钮全保留）；经典面板 → 缩成圆点
            view.findViewById<ImageView>(R.id.btnFloatMinimize).setOnClickListener {
                val infoRow = view.findViewById<View?>(R.id.floatInfoRow)
                if (infoRow != null) {
                    infoRow.visibility = if (infoRow.visibility == View.GONE) View.VISIBLE else View.GONE
                } else {
                    swapToMini(params.x, params.y)
                }
            }
        } catch (_: Throwable) {}

        try {
            // 闪电：立即落子——引擎思考中直接 stop，当前最优着法立刻执行
            view.findViewById<View?>(R.id.btnFloatBolt)?.setOnClickListener {
                workHandler.post {
                    try {
                        engine?.stop()
                        if (thinkPhase == ThinkPhase.RUNNING) {
                            thinkPhase = ThinkPhase.STOPPING
                            mainHandler.post { btnFloatThinkRef?.text = "思" }
                        }
                        mainHandler.post { tvStatus?.text = "⚡ 立即落子" }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        try {
            // 关闭（横条 × 钮）：停止服务、撤悬浮窗（等同断开+退出）
            view.findViewById<View?>(R.id.btnFloatClose)?.setOnClickListener {
                try {
                    stopService(Intent(this, AutoPlayService::class.java))
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        try { btnConnect?.setOnClickListener { toggleConnect() } } catch (_: Throwable) {}
        try {
            // 手动纠偏（v1.8.0）：我方回合 / 对方回合——极端情况的用户保险丝
            view.findViewById<View?>(R.id.btnFloatMyTurn)?.setOnClickListener { forceMyTurn() }
            view.findViewById<View?>(R.id.btnFloatOppTurn)?.setOnClickListener { forceOpponentTurn() }
        } catch (_: Throwable) {}
        try {
            // 我的库记录模式（v1.10.0）：连线录谱——引擎只思考建议绝不代走，
            // 我方走子入谱（屏幕追平确认时），换题自动封谱入库
            view.findViewById<View?>(R.id.btnFloatRecord)?.let { btn ->
                (btn as? Button)?.let { btnFloatRecordRef = it }
                // 恢复上次会话的高亮状态（服务重建场景）
                if (recordMode) btn.setBackgroundColor(0xFF880E4F.toInt())
                btn.setOnClickListener {
                    workHandler.post { toggleRecordMode() }
                }
            }
        } catch (_: Throwable) {}
        try {
            // 思考（无限分析：点开始持续思考当前局面，再点暂停；局面变化自动跟随）
            view.findViewById<Button>(R.id.btnFloatThink).setOnClickListener {
                workHandler.post { toggleThink() }
            }
        } catch (_: Throwable) {}

        // 选边（v1.8.0 增强：点执白/执黑立即生效——设边+清验证+翻轮我方+立即出手）
        rgSide?.setOnCheckedChangeListener { _, id ->
            sideAuto = id == R.id.rbSideAuto
            if (!sideAuto) {
                mySide = if (id == R.id.rbSideWhite) Side.WHITE else Side.BLACK
                if (running) {
                    workHandler.post {
                        try {
                            verifyUci = null; verifyPrePlacement = null; tapAttempts = 0
                            waitSince = 0L
                            reloadStm(placementOf(extBoard.fen), mySide == Side.WHITE)
                            maybeAutoPlay()
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        // 层数控件已移除——引擎参数在主界面 设置→引擎 里配置

        // 手动校准棋盘（坐标对不上时的终极兜底）
        view.findViewById<Button>(R.id.btnFloatCalib).setOnClickListener {
            mainHandler.post { showCalibration() }
        }

        // 重定位（清空校准回到自动识别）
        view.findViewById<Button>(R.id.btnFloatRelocate).setOnClickListener {
            calibPrefs().edit().putBoolean("valid", false).apply()
            boardRect = null
            tvStatus?.text = "已清除校准 · 自动识别中…"
        }

        renderSideSelection()
    }

    private var lastMoveDesc = "（无）"

    private fun renderSideSelection() {
        val rg = rgSide ?: return
        rg.setOnCheckedChangeListener(null)
        rg.check(when {
            sideAuto -> R.id.rbSideAuto
            mySide == Side.WHITE -> R.id.rbSideWhite
            else -> R.id.rbSideBlack
        })
        rg.setOnCheckedChangeListener { _, id ->
            sideAuto = id == R.id.rbSideAuto
            if (!sideAuto) {
                mySide = if (id == R.id.rbSideWhite) Side.WHITE else Side.BLACK
                if (running) maybeAutoPlay()
            }
        }
    }

    private fun renderState() {
        mainHandler.post {
            val (text, color) = when (state) {
                State.IDLE -> "待命 · 点击「连线」开始" to "#666C7A"
                State.CONNECTING -> "连接中 · 定位棋盘…" to "#F7C948"
                State.WATCH -> "对方行棋中 · 监视屏幕" to "#4CAF7D"
                State.THINK -> "引擎思考中…" to "#5FA8F5"
                State.PLAY -> "模拟落子中…" to "#E25B5B"
                State.NEW_GAME_SYNC -> "新局同步 · 对齐确认中" to "#F7C948"
                State.PAUSED_VERIFY_LOST -> "验证失联 · 已暂停（点我方回合恢复）" to "#FF5252"
            }
            tvStatus?.text = text
            statusDot?.setBackgroundColor(android.graphics.Color.parseColor(color))
            (btnConnect as? Button)?.text = if (running) getString(R.string.disconnect) else getString(R.string.connect)
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun swapToMini(x: Int, y: Int) {
        try {
            removePanel()
            // 紧凑条：♞展开 + 实时状态灯 + ⏹断开 —— 缩小后连线照常运行
            // v1.8.0：整条可拖拽 + 放大（用户要求：缩小了也必须能移动，别太小）
            val ctx = android.view.ContextThemeWrapper(this, R.style.Theme_AutoChess_Float)
            val bar = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundColor(0xF01A2030.toInt())
                setPadding(18, 12, 10, 12)
            }
            val horse = TextView(ctx).apply {
                text = "♞"; textSize = 22f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 0, 12, 0)
            }
            val miniDot = View(ctx)
            val stop = TextView(ctx).apply {
                text = "⏹"; textSize = 20f
                setTextColor(0xFFFFA07A.toInt())
                setPadding(14, 0, 10, 0)
            }
            bar.addView(horse)
            bar.addView(miniDot.apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(12, 12).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                setBackgroundColor(0xFF666C7A.toInt())
            })
            bar.addView(stop)

            miniView = bar
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
            windowManager.addView(bar, params)

            // 状态灯引用切到迷你条：dot 更新逻辑照常生效
            statusDot = miniDot

            // 整条拖拽（子控件点击优先；空白处按下拖动）
            var mDownX = 0f; var mDownY = 0f; var mStartX = 0; var mStartY = 0; var mMoved = false
            bar.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        mDownX = e.rawX; mDownY = e.rawY
                        mStartX = params.x; mStartY = params.y; mMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - mDownX; val dy = e.rawY - mDownY
                        if (abs(dx) > 5 || abs(dy) > 5) mMoved = true
                        if (mMoved) {
                            params.x = (mStartX + dx.toInt()).coerceIn(0, (realScreenWidth - 80).coerceAtLeast(0))
                            params.y = (mStartY + dy.toInt()).coerceIn(0, (realScreenHeight - 80).coerceAtLeast(0))
                            windowManager.updateViewLayout(bar, params)
                        }
                        true
                    }
                    else -> false
                }
            }

            // ♞ 点击 → 展开完整面板
            horse.setOnClickListener {
                try { windowManager.removeView(bar) } catch (_: Throwable) {}
                miniView = null
                showFloatingPanel()
            }
            // ⏹ 点击 → 断开连线
            stop.setOnClickListener { toggleConnect() }
            // 整条可拖拽（带边界钳制）
            var downX = 0f; var downY = 0f; var sx = 0; var sy = 0
            bar.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        sx = params.x; sy = params.y; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (sx + (e.rawX - downX).toInt()).coerceIn(0, (realScreenWidth - 80).coerceAtLeast(0))
                        params.y = (sy + (e.rawY - downY).toInt()).coerceIn(0, (realScreenHeight - 80).coerceAtLeast(0))
                        windowManager.updateViewLayout(bar, params)
                        true
                    }
                    else -> false
                }
            }
        } catch (_: Throwable) {
            // 迷你条失败兜底：还原完整面板，绝不让用户失去控制
            try { showFloatingPanel() } catch (_: Throwable) {}
        }
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        miniView?.let { runCatching { windowManager.removeView(it) } }
        miniView = null
    }

    // ─────────────── 通知 ───────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "自动连线", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutoPlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_stat_chess)
            .setContentTitle("AutoChess 自动连线")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNoti(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "autochess_autoplay"
        private const val NOTI_ID = 9527
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_STOP = "com.rogic.autochess.STOP"
        /** 设置切换悬浮窗样式 → 服务即时重建面板 */
        private const val ACTION_REFRESH_PANEL = "com.rogic.autochess.REFRESH_PANEL"
        // 事件驱动轮询：局面活跃（对方刚走/我方思考落子）快扫；稳定等待快速感知。
        // 极速模式（pace=turbo）：轮询/点击节奏全部减半
        private const val ACTIVE_MS = 80L
        private const val IDLE_MS = 150L
        private const val ACTIVE_TURBO_MS = 40L
        private const val IDLE_TURBO_MS = 80L

        /** App 内对局镜像回调：主界面注册后实时接收连线识别的局面 (fen, 我方最后落子uci) */
        @Volatile var positionListener: ((fen: String, lastMove: String?) -> Unit)? = null

        /** 引擎设置（主界面 设置→引擎 写入；这里每次对局读取） */
        fun readEngineSettings(context: Context): Pair<Int, Long?> {
            val p = context.getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
            val depth = p.getInt("depth", 12).coerceIn(1, 30)
            val mt = p.getLong("movetime_ms", 0L)
            return depth to (if (mt > 0) mt else null)
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AutoPlayService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }
    }
}
