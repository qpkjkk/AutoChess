package com.rogic.autochess

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rogic.autochess.chess.BoardView
import com.rogic.autochess.chess.Game
import com.rogic.autochess.chess.PieceAssets
import com.rogic.autochess.chess.San
import com.rogic.autochess.engine.StockfishEngine
import com.rogic.autochess.autoplay.AutoPlayService
import com.rogic.autochess.autoplay.ChessAccessibilityService

class MainActivity : AppCompatActivity(), Game.Listener, StockfishEngine.Listener {

    private lateinit var game: Game
    private lateinit var boardView: BoardView
    private lateinit var tvEngineInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var evalBar: FrameLayout
    private lateinit var evalWhite: View
    private lateinit var moveListContainer: LinearLayout
    private lateinit var moveListScroll: ScrollView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnUndo: Button
    private lateinit var btnThink: Button

    /** 无限思考状态：0=关 1=思考中 2=暂停中（等 bestmove 收尾） */
    private var analysisPhase = 0
    private var analysedFen: String? = null

    private var engine: StockfishEngine? = null
    private var engineRestarts = 0
    @Volatile private var pendingEngineMove = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestGeneration = 0
    private var engineThinking = false
    /** 当前界面主题名（default/white/bluewhite/blue/pink） */
    private var appTheme: String? = "default"

    // ─────────────── 生命周期 ───────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            bootStep("onCreate begin")
            setContentView(R.layout.activity_main)
            bootStep("setContentView ok")
            // 界面主题（设置→界面主题；浅色主题需遍历改文字色）
            appTheme = getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
                .getString("app_theme", "default")
            ThemeUtil.applyActivity(this, findViewById(android.R.id.content), appTheme)

            game = Game()
            game.listener = this
            bootStep("Game init ok")

            boardView = findViewById(R.id.boardView)
            tvEngineInfo = findViewById(R.id.tvEngineInfo)
            tvStatus = findViewById(R.id.tvStatus)
            evalBar = findViewById(R.id.evalBar)
            evalWhite = findViewById(R.id.evalWhite)
            moveListContainer = findViewById(R.id.moveListContainer)
            moveListScroll = findViewById(R.id.moveListScroll)
            btnPrev = findViewById(R.id.btnPrev)
            btnNext = findViewById(R.id.btnNext)
            btnUndo = findViewById(R.id.btnUndo)
            btnThink = findViewById(R.id.btnThink)
            bootStep("findViewById ok")

        PieceAssets.ensureLoaded(this)
        bootStep("piece assets ok")

        boardView.listener = object : BoardView.MoveListener {
            override fun onUserMove(from: Square, to: Square, promotion: Piece?) {
                // 注意括号：避免 promotion 为 null 时拼出 "e7e8null"
                val uci = from.name.lowercase() + to.name.lowercase() +
                        (promotion?.let { p -> promoLetter(p) } ?: "")
                val san = game.playMove(uci)
                if (san == null) {
                    Toast.makeText(this@MainActivity, "不合规则的走法", Toast.LENGTH_SHORT).show()
                    refreshBoard(false)
                } else if (analysisPhase == 1) {
                    // 分析(复盘)模式：走谁都行，引擎只跟随分析不自动应着
                    refreshBoard(true)
                } else {
                    maybeEngineMove()
                }
            }

            override fun onPromotionNeeded(from: Square, to: Square) {
                showPromotionDialog(from, to)
            }

            override fun onSquareTapped(square: Square) {
                // 提供合法目标（分析模式 = 复盘：任意一侧可动，不限人机轮次）
                val analyzing = analysisPhase == 1
                val piece = game.board.getPiece(square)
                if (piece != com.github.bhlangonijr.chesslib.Piece.NONE &&
                    piece.pieceSide == game.board.sideToMove &&
                    (game.humanCanMoveNow() || (analyzing && !game.gameOver && !game.isViewingHistory))
                ) {
                    val targets = game.board.legalMoves()
                        .filter { it.from == square }
                        .map { it.to }
                        .distinct()
                    boardView.setLegalTargets(square, targets)
                }
            }
        }

        findViewById<Button>(R.id.btnNewGame).setOnClickListener { showNewGameDialog() }
        findViewById<Button>(R.id.btnFlip).setOnClickListener {
            boardView.whiteAtBottom = !boardView.whiteAtBottom
            boardView.invalidate()
        }
        btnUndo.setOnClickListener { undoMove() }
        btnPrev.setOnClickListener { game.stepBack() }
        btnNext.setOnClickListener { game.stepForward() }
        btnPrev.setOnLongClickListener { game.jumpToStart(); true }
        btnNext.setOnLongClickListener { game.jumpToEnd(); true }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnMyBook).setOnClickListener {
            startActivity(Intent(this, MyBookActivity::class.java))
        }
        findViewById<Button>(R.id.btnAutoPlay).setOnClickListener { startAutoPlayFlow() }
        btnThink.setOnClickListener { toggleAnalysis() }

        game.reset(Game.Mode.HUMAN_ENGINE, Side.WHITE, 10)
        bootStep("game reset ok")
        refreshBoard(false)
        bootStep("refreshBoard ok")
        // 引擎异步启动：NNUE 权重拷贝（104MB）绝不能在主线程做，否则触发 ANR/闪退
        tvEngineInfo.text = "引擎启动中…"
        thread(name = "engine-boot") {
            val ok = ensureEngine()
            bootStep("engine boot ok=$ok")
            mainHandler.post {
                if (ok) {
                    tvEngineInfo.text = "引擎就绪"
                    if (pendingEngineMove) {
                        pendingEngineMove = false
                        maybeEngineMove()
                    }
                } else {
                    tvEngineInfo.text = "引擎启动失败，可尝试重开应用"
                }
            }
        }
        bootStep("onCreate done")
        // 存活心跳：区分“启动即死”与“启动后几秒死”
        mainHandler.postDelayed({ bootStep("SURVIVED 2s") }, 2000)
        mainHandler.postDelayed({ bootStep("SURVIVED 8s") }, 8000)
        } catch (t: Throwable) {
            bootStep("FATAL: ${t.javaClass.name}: ${t.message}")
            showFatalError(t)
        }
    }

    /** 启动失败时把异常直接显示到屏幕上（不依赖任何资源，纯代码构造） */
    private fun showFatalError(t: Throwable) {
        try {
            val text = "启动失败：${t.javaClass.simpleName}\n${t.message}\n\n" +
                    t.stackTraceToString().take(3000) +
                    "\n\n此页面信息请截图发给开发者。\n" +
                    "同时已写入 Android/data/com.rogic.autochess/files/crash/"
            val scroll = android.widget.ScrollView(this)
            val tv = TextView(this)
            tv.text = text
            tv.textSize = 12f
            tv.setTextColor(-0x1)
            tv.setPadding(40, 100, 40, 40)
            scroll.addView(tv)
            scroll.setBackgroundColor(-0xdededf)
            setContentView(scroll)
        } catch (_: Throwable) {
        }
    }

    /** 启动分步打点：任何一步崩溃都能精确定位最后成功的位置 */
    private fun bootStep(msg: String) {
        try {
            val line = "${System.currentTimeMillis()} [${Thread.currentThread().name}] $msg\n"
            for (base in listOf(filesDir, getExternalFilesDir(null))) {
                if (base == null) continue
                try {
                    val dir = java.io.File(base, "crash")
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, "boot_trace.txt").appendText(line)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.quit()
        AutoPlayService.positionListener = null
    }

    // ─────────────── 外部对局实时镜像 ───────────────

    /** 镜像模式：悬浮窗连线识别的局面实时显示在应用内棋盘 */
    private var mirrorMode = false
    private var mirrorLastFen: String? = null

    override fun onResume() {
        super.onResume()
        AutoPlayService.positionListener = { fen, lastMove ->
            mainHandler.post {
                if (!mirrorMode) {
                    mirrorMode = true
                    Toast.makeText(this, "检测到连线 · 已切换为对局镜像", Toast.LENGTH_SHORT).show()
                }
                if (fen != mirrorLastFen) {
                    mirrorLastFen = fen
                    try {
                        boardView.setPosition(fen, lastMove, true)
                        boardView.interactive = false
                        boardView.movableSide = "none"
                        tvStatus.text = "镜像中 · 对方棋盘实时同步"
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AutoPlayService.positionListener = null
    }

    /** 退出镜像：开新对局或本地操作时回到正常模式 */
    private fun exitMirrorMode() {
        if (mirrorMode) {
            mirrorMode = false
            mirrorLastFen = null
        }
    }

    // ─────────────── 无限思考（只算不下） ───────────────

    /** 思考按钮：引擎无限分析当前局面（持续输出深度/评分/线路），再点暂停 */
    private fun toggleAnalysis() {
        when (analysisPhase) {
            1 -> { // 思考中 → 暂停
                analysisPhase = 2
                engine?.stop()   // bestmove 到达后在 onBestMove 收尾
                // 深层搜索时 bestmove 偶发延迟：1.5 秒看门狗强制收尾（再补一次 stop）
                mainHandler.postDelayed({
                    if (analysisPhase == 2) {
                        try { engine?.stop() } catch (_: Throwable) {}
                    }
                }, 1500)
                mainHandler.postDelayed({
                    if (analysisPhase == 2) {
                        // bestmove 仍未到（引擎卡死/输出丢失）→ 直接收尾 UI
                        stopAnalysisUi()
                        tvEngineInfo.text = "分析已暂停（引擎响应超时）"
                    }
                }, 3000)
                btnThink.text = "收尾…"
            }
            else -> {
                val e = engine
                if (e == null || !e.isRunning()) {
                    Toast.makeText(this, "引擎还在启动，稍候再点", Toast.LENGTH_SHORT).show()
                    return
                }
                if (game.gameOver) {
                    Toast.makeText(this, "对局已结束", Toast.LENGTH_SHORT).show()
                    return
                }
                startAnalysis(game.displayFen())
            }
        }
    }

    private fun startAnalysis(fen: String) {
        requestGeneration++          // 作废在途对弈请求，防止思考结束补走旧棋
        analysisPhase = 1
        analysedFen = fen
        btnThink.text = "⏸ 暂停"
        engine?.goInfinite(fen)
    }

    private fun stopAnalysisUi() {
        analysisPhase = 0
        btnThink.text = "🧠 思考"
    }

    private fun followAnalysisIfNeeded() {
        if (analysisPhase != 1) return
        val fen = game.displayFen()
        if (fen != analysedFen) startAnalysis(fen)   // 跟随局面变化无缝重启
    }

    private val engineLock = Any()

    /** 线程安全：冷启动权重拷贝期间用户落子也不会拉起第二个引擎实例 */
    private fun ensureEngine(): Boolean {
        synchronized(engineLock) {
            engine?.let { if (it.isRunning()) return true }
            val e = StockfishEngine(id = "main", listener = this)
            val ok = e.start(this)
            if (ok) {
                engine = e
            } else {
                engine = null
            }
            return ok
        }
    }

    private fun maybeEngineMove() {
        if (game.gameOver || game.isViewingHistory) return
        // 思考模式优先占用引擎，不落子；暂停后由 onBestMove 恢复对弈
        if (analysisPhase != 0) return
        val engineTurn = when (game.mode) {
            Game.Mode.HUMAN_ENGINE -> game.board.sideToMove != game.humanSide
            Game.Mode.ENGINE_ENGINE -> true
            Game.Mode.HUMAN_HUMAN -> false
        }
        if (!engineTurn) return
        // 引擎未就绪（冷启动权重拷贝中）时挂起请求，boot 完成后补发，避免主线程等锁
        val e = engine
        if (e == null || !e.isRunning()) {
            pendingEngineMove = true
            return
        }
        val gen = ++requestGeneration
        engineThinking = true
        updateEngineInfoUi(null)
        val (skill, depth, movetime) = engineParams(game.engineLevel)
        e.setSkillLevel(skill)
        e.requestBestMove(game.board.fen, depth = depth, moveTimeMs = movetime) { best, _ ->
            mainHandler.post {
                engineThinking = false
                if (gen == requestGeneration && best != null && !game.gameOver) {
                    val san = game.playMove(best)
                    if (san != null && game.mode == Game.Mode.ENGINE_ENGINE && !game.gameOver) {
                        mainHandler.postDelayed({ maybeEngineMove() }, 400)
                    }
                }
            }
        }
    }

    private fun engineParams(level: Int): Triple<Int, Int?, Long?> = when {
        level <= 1 -> Triple(1, 1, 80L)
        level >= 20 -> Triple(20, null, 3000L)
        else -> Triple(level, level + 1, 60L + level * 140L)
    }

    override fun onInfo(info: StockfishEngine.Info) {
        mainHandler.post { updateEngineInfoUi(info) }
    }

    override fun onBestMove(bestMove: String?, ponder: String?) {
        mainHandler.post {
            if (analysisPhase == 2) {
                // 思考暂停收尾：显示结论
                stopAnalysisUi()
                val info = engine?.lastInfo
                val depth = info?.depth?.let { "深度$it" } ?: ""
                val pv = info?.pv?.take(5)?.joinToString(" ") ?: ""
                tvEngineInfo.text = "分析结论 $depth · ${bestMove ?: "—"} $pv"
                // 对弈中断恢复：若轮到引擎走棋则继续
                maybeEngineMove()
            } else if (analysisPhase == 1) {
                // 思考中收到 bestmove = 旧对弈请求尾流，忽略
            }
        }
    }
    override fun onReady() {}

    override fun onEngineDead() {
        mainHandler.post {
            // 思考状态复位（引擎已死，bestmove 不会再来）
            if (analysisPhase != 0) {
                stopAnalysisUi()
                tvEngineInfo.text = "引擎重启中…"
            }
            // 重启风暴保护：最多自动重启 3 次，避免失败死循环
            if (engineRestarts >= 3) {
                Toast.makeText(this, "引擎多次异常退出，已停止自动重启", Toast.LENGTH_LONG).show()
                return@post
            }
            engineRestarts++
            Toast.makeText(this, "引擎异常退出，正在重启…", Toast.LENGTH_SHORT).show()
            engine = null
            thread(name = "engine-restart") { ensureEngine() }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateEngineInfoUi(info: StockfishEngine.Info?) {
        if (info == null) {
            tvEngineInfo.text = if (engineThinking) "引擎思考中…" else "引擎就绪"
            return
        }
        val scoreStr = when {
            info.scoreMate != null -> "杀 ${info.scoreMate}步"
            info.scoreCp != null -> String.format("%+.2f", info.scoreCp / 100.0)
            else -> ""
        }
        val pvSan = pvToSan(info.pv).take(6).joinToString(" ")
        tvEngineInfo.text = "深度 ${info.depth}  $scoreStr  ${info.nps / 1000}kN/s  $pvSan"
        // 评估条（分析中用被分析局面的行棋方视角，避免翻看历史时视角错乱）
        val cp = info.scoreCp ?: run {
            info.scoreMate?.let { if (it > 0) 1500 else -1500 } ?: 0
        }
        val fenForPov = if (analysisPhase == 1) analysedFen else null
        updateEvalBar(cpWhitePov(cp, fenForPov))
    }

    private fun cpWhitePov(cp: Int, fen: String? = null): Int {
        // 引擎分数是行棋方视角，转白方视角
        val whiteToMove = if (fen != null) fen.contains(" w ")
        else game.board.sideToMove == Side.WHITE
        return if (whiteToMove) cp else -cp
    }

    private fun updateEvalBar(cpWhite: Int) {
        val pct = (0.5 + 0.5 * Math.tanh(cpWhite / 400.0)).coerceIn(0.02, 0.98)
        evalBar.post {
            val lp = evalWhite.layoutParams as FrameLayout.LayoutParams
            lp.width = (evalBar.width * pct).toInt()
            evalWhite.layoutParams = lp
        }
    }

    private fun pvToSan(pv: List<String>): List<String> {
        if (pv.isEmpty()) return emptyList()
        return try {
            val b = Board()
            b.loadFromFen(game.board.fen)
            val out = mutableListOf<String>()
            for (u in pv.take(10)) {
                if (u.length < 4) continue
                val from = Square.valueOf(u.substring(0, 2).uppercase())
                val to = Square.valueOf(u.substring(2, 4).uppercase())
                val promo = if (u.length >= 5) {
                    val w = b.sideToMove == Side.WHITE
                    when (u[4]) {
                        'q' -> if (w) Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
                        'r' -> if (w) Piece.WHITE_ROOK else Piece.BLACK_ROOK
                        'b' -> if (w) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
                        else -> if (w) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
                    }
                } else null
                val mv = b.legalMoves().firstOrNull { it.from == from && it.to == to && (promo == null || it.promotion == promo) }
                    ?: break
                out.add(San.ofMoveAndApply(b, mv))
            }
            out
        } catch (_: Exception) {
            pv.take(6)
        }
    }

    // ─────────────── 界面刷新 ───────────────

    override fun onPositionChanged() {
        refreshBoard(true)
    }

    override fun onGameOver(text: String) {
        Snackbar.make(boardView, text, Snackbar.LENGTH_LONG).show()
    }

    private fun refreshBoard(animate: Boolean) {
        val fen = game.displayFen()
        boardView.whiteAtBottom = game.whiteAtBottom
        // 思考(分析)模式 = 复盘：任意一侧棋子都可动（不管人机对弈轮次）
        val analyzing = analysisPhase == 1 && !game.gameOver && !game.isViewingHistory
        boardView.interactive = analyzing || game.humanCanMoveNow()
        boardView.movableSide = when {
            analyzing -> if (game.board.sideToMove == Side.WHITE) "w" else "b"
            !game.humanCanMoveNow() -> "none"
            game.board.sideToMove == Side.WHITE -> "w"
            else -> "b"
        }
        boardView.setPosition(fen, game.displayLastMoveUci(), animate)
        boardView.checkSquareHint = computeCheckHint(fen)
        // 思考跟随：局面变化（走子/翻看历史）时无缝切换到新局面继续分析
        followAnalysisIfNeeded()
        tvStatus.text = when {
            game.gameOver -> game.resultText
            game.isViewingHistory ->
                getString(R.string.viewing_history, game.viewIndex, game.history.size)
            game.mode == Game.Mode.ENGINE_ENGINE -> "引擎对弈中 · ${if (game.board.sideToMove == Side.WHITE) "白" else "黑"}方思考"
            game.board.sideToMove == game.humanSide -> "轮到你了"
            else -> "引擎思考中…"
        }
        rebuildMoveList()
    }

    private fun computeCheckHint(fen: String): Square? = try {
        val b = Board()
        b.loadFromFen(fen)
        if (b.isKingAttacked()) b.getKingSquare(b.sideToMove) else null
    } catch (_: Exception) {
        null
    }

    private fun rebuildMoveList() {
        moveListContainer.removeAllViews()
        var row: LinearLayout? = null
        val hist = game.history
        var i = 0
        while (i < hist.size) {
            row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val number = TextView(this).apply {
                text = "${hist[i].moveNumber}."
                setTextColor(Color.parseColor("#7A8499"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.14f)
            }
            row.addView(number)
            val whiteMove = hist.getOrNull(i)
            val blackMove = hist.getOrNull(i + 1)
            row.addView(makeMoveCell(whiteMove, i))
            row.addView(makeMoveCell(blackMove, i + 1))
            moveListContainer.addView(row)
            i += 2
        }
        moveListScroll.post { moveListScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun makeMoveCell(rec: Game.MoveRecord?, index: Int): View {
        return TextView(this).apply {
            text = rec?.san ?: ""
            textSize = 14f
            setPadding(12, 6, 12, 6)
            setTextColor(
                if (index == game.viewIndex - 1) Color.parseColor("#4F8CFF")
                else Color.parseColor("#ECEFF6")
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.43f)
            setOnClickListener {
                if (rec != null) {
                    game.jumpToViewIndex(index + 1)
                }
            }
        }
    }

    private fun undoMove() {
        engine?.stop()
        requestGeneration++
        engineThinking = false
        game.undo()
        // 悔棋后如果轮到引擎（如人机模式引擎执白），重新思考
        maybeEngineMove()
    }

    // ─────────────── 对话框 ───────────────

    private fun showNewGameDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_game, null)
        val rgMode = view.findViewById<android.widget.RadioGroup>(R.id.rgMode)
        val rgSide = view.findViewById<android.widget.RadioGroup>(R.id.rgSide)
        val sideRow = view.findViewById<LinearLayout>(R.id.sideRow)
        val seek = view.findViewById<SeekBar>(R.id.seekLevel)
        val tvLevel = view.findViewById<TextView>(R.id.tvLevelLabel)
        var level = game.engineLevel
        tvLevel.text = "引擎等级：$level（${levelName(level)}）"
        seek.progress = level
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                level = p.coerceAtLeast(1)
                tvLevel.text = "引擎等级：$level（${levelName(level)}）"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            sideRow.visibility = if (checkedId == R.id.rbModeHumanEngine) View.VISIBLE else View.GONE
        }
        // 预选当前
        when (game.mode) {
            Game.Mode.HUMAN_ENGINE -> rgMode.check(R.id.rbModeHumanEngine)
            Game.Mode.ENGINE_ENGINE -> rgMode.check(R.id.rbModeEngineEngine)
            Game.Mode.HUMAN_HUMAN -> rgMode.check(R.id.rbModeHumanHuman)
        }
        rgSide.check(if (game.humanSide == Side.WHITE) R.id.rbSideWhite else R.id.rbSideBlack)

        MaterialAlertDialogBuilder(this)
            .setTitle("新对局")
            .setView(view)
            .setPositiveButton("开始") { _, _ ->
                requestGeneration++
                val mode = when (rgMode.checkedRadioButtonId) {
                    R.id.rbModeEngineEngine -> Game.Mode.ENGINE_ENGINE
                    R.id.rbModeHumanHuman -> Game.Mode.HUMAN_HUMAN
                    else -> Game.Mode.HUMAN_ENGINE
                }
                val side = when (rgSide.checkedRadioButtonId) {
                    R.id.rbSideBlack -> Side.BLACK
                    R.id.rbSideRandom -> if (Math.random() < 0.5) Side.WHITE else Side.BLACK
                    else -> Side.WHITE
                }
                engine?.newGame()
                exitMirrorMode()
                game.reset(mode, side, level)
                updateEvalBar(0)
                refreshBoard(false)
                maybeEngineMove()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun levelName(level: Int): String = when {
        level <= 3 -> "入门"
        level <= 7 -> "业余"
        level <= 12 -> "俱乐部"
        level <= 16 -> "专家"
        else -> "大师"
    }

    private fun promoLetter(p: Piece): String = when (p) {
        Piece.WHITE_QUEEN, Piece.BLACK_QUEEN -> "q"
        Piece.WHITE_ROOK, Piece.BLACK_ROOK -> "r"
        Piece.WHITE_BISHOP, Piece.BLACK_BISHOP -> "b"
        else -> "n"
    }

    private fun showPromotionDialog(from: Square, to: Square) {
        val view = layoutInflater.inflate(R.layout.dialog_promotion, null)
        val white = game.board.sideToMove == Side.WHITE
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("兵的升变")
            .setView(view)
            .create()
        fun pick(piece: Piece) {
            val uci = from.name.lowercase() + to.name.lowercase() + promoLetter(piece)
            dialog.dismiss()
            game.playMove(uci)
            maybeEngineMove()
        }
        val keys = if (white) listOf("wQ", "wR", "wB", "wN") else listOf("bQ", "bR", "bB", "bN")
        val pieces = if (white) listOf(Piece.WHITE_QUEEN, Piece.WHITE_ROOK, Piece.WHITE_BISHOP, Piece.WHITE_KNIGHT)
                     else listOf(Piece.BLACK_QUEEN, Piece.BLACK_ROOK, Piece.BLACK_BISHOP, Piece.BLACK_KNIGHT)
        val ids = listOf(R.id.promoQueen, R.id.promoRook, R.id.promoBishop, R.id.promoKnight)
        for (i in 0..3) {
            view.findViewById<ImageView>(ids[i]).apply {
                PieceAssets.get(keys[i])?.let { setImageBitmap(it) }
                setOnClickListener { pick(pieces[i]) }
            }
        }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rgSet = view.findViewById<android.widget.RadioGroup>(R.id.rgPieceSet)
        val swCoords = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swCoords)
        when (PieceAssets.currentSet) {
            "merida" -> rgSet.check(R.id.rbSetMerida)
            "alpha" -> rgSet.check(R.id.rbSetAlpha)
            else -> rgSet.check(R.id.rbSetCb)
        }
        swCoords.isChecked = boardView.showCoordinates

        // ── 引擎设置（连线自动下棋用）：步进器 + 点数字手输 ──
        val prefs = getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
        val btnDepthMinus = view.findViewById<Button>(R.id.btnDepthMinus)
        val btnDepthPlus = view.findViewById<Button>(R.id.btnDepthPlus)
        val tvDepthValue = view.findViewById<TextView>(R.id.tvDepthValue)
        val btnTimeMinus = view.findViewById<Button>(R.id.btnTimeMinus)
        val btnTimePlus = view.findViewById<Button>(R.id.btnTimePlus)
        val tvTimeValue = view.findViewById<TextView>(R.id.tvTimeValue)
        val rgFloatStyle = view.findViewById<android.widget.RadioGroup>(R.id.rgFloatStyle)
        val rgTheme = view.findViewById<android.widget.RadioGroup>(R.id.rgTheme)
        // 识别模型回显（v1.10.0：极速小模型/精准大模型）
        val rgModel = view.findViewById<android.widget.RadioGroup>(R.id.rgModel)
        rgModel.check(if (prefs.getBoolean("tiny_model", true)) R.id.rbModelTiny else R.id.rbModelYolo)

        // 主题单选回显
        rgTheme.check(when (prefs.getString("app_theme", "default")) {
            "white" -> R.id.rbThemeWhite
            "bluewhite" -> R.id.rbThemeBlueWhite
            "blue" -> R.id.rbThemeBlue
            "pink" -> R.id.rbThemePink
            else -> R.id.rbThemeDefault
        })
        val themeBefore = prefs.getString("app_theme", "default")
        val styleBefore = prefs.getString("float_style", "classic")

        var depthVal = prefs.getInt("depth", 12).coerceIn(1, 30)
        // 时间秒值（Double，支持 0.1 精度；0 = 不限）
        var timeVal = (prefs.getLong("movetime_ms", 0L) / 1000.0).coerceIn(0.0, 120.0)
        fun fmtTime(v: Double): String =
            if (v <= 0.0) "不限"
            else if (v == kotlin.math.floor(v)) v.toInt().toString()
            else String.format("%.1f", v)
        fun renderSteppers() {
            tvDepthValue.text = depthVal.toString()
            tvTimeValue.text = fmtTime(timeVal)
        }
        renderSteppers()
        rgFloatStyle.check(if (prefs.getString("float_style", "classic") == "strip")
            R.id.rbStylePengfei else R.id.rbStyleClassic)

        fun stepDepth(d: Int) { depthVal = (depthVal + d).coerceIn(1, 30); renderSteppers() }
        fun stepTime(d: Double) {
            // 不限(0)加号起步=1秒；1秒内步进0.1；1秒以上步进1
            val step = if (d > 0 && timeVal <= 0.0) 1.0
                       else if (timeVal < 1.0) 0.1 else 1.0
            timeVal = ((if (d > 0 && timeVal <= 0.0) 0.0 else timeVal) + d * step)
                .coerceIn(0.0, 120.0)
            renderSteppers()
        }
        btnDepthMinus.setOnClickListener { stepDepth(-1) }
        btnDepthPlus.setOnClickListener { stepDepth(+1) }
        btnTimeMinus.setOnClickListener { stepTime(-1.0) }
        btnTimePlus.setOnClickListener { stepTime(+1.0) }

        // 点数字 → 直接输入（时间支持小数如 0.5）
        tvDepthValue.setOnClickListener {
            showNumberInputDialog("搜索深度", depthVal.toString(), false) { s ->
                s.toIntOrNull()?.let { depthVal = it.coerceIn(1, 30); renderSteppers() }
            }
        }
        tvTimeValue.setOnClickListener {
            showNumberInputDialog("思考时限（秒，0=不限，可输小数）",
                if (timeVal <= 0) "" else fmtTime(timeVal), true) { s ->
                s.toDoubleOrNull()?.let { timeVal = it.coerceIn(0.0, 120.0); renderSteppers() }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("设置")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val set = when (rgSet.checkedRadioButtonId) {
                    R.id.rbSetMerida -> "merida"
                    R.id.rbSetAlpha -> "alpha"
                    else -> "cburnett"
                }
                if (set != PieceAssets.currentSet) {
                    PieceAssets.currentSet = set
                    PieceAssets.ensureLoaded(this, set)
                    boardView.invalidate()
                }
                boardView.showCoordinates = swCoords.isChecked
                boardView.invalidate()
                // 保存引擎设置（连线服务每步实时读取）
                val themeAfter = when (rgTheme.checkedRadioButtonId) {
                    R.id.rbThemeWhite -> "white"
                    R.id.rbThemeBlueWhite -> "bluewhite"
                    R.id.rbThemeBlue -> "blue"
                    R.id.rbThemePink -> "pink"
                    else -> "default"
                }
                val styleAfter = if (rgFloatStyle.checkedRadioButtonId == R.id.rbStylePengfei) "strip" else "classic"
                val tinyAfter = rgModel.checkedRadioButtonId != R.id.rbModelYolo
                prefs.edit()
                    .putInt("depth", depthVal)
                    .putLong("movetime_ms", (timeVal * 1000).toLong())
                    .putString("float_style", styleAfter)
                    .putString("app_theme", themeAfter)
                    .putBoolean("tiny_model", tinyAfter)
                    .apply()
                // 悬浮窗样式变了 → 通知服务即时重建面板（无需断开重连）
                if (styleAfter != styleBefore) {
                    try {
                        startService(Intent(this, AutoPlayService::class.java)
                            .setAction("com.rogic.autochess.REFRESH_PANEL"))
                    } catch (_: Throwable) {}
                }
                // 主题变了 → 重建 Activity 生效
                if (themeAfter != themeBefore) {
                    recreate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
        // 弹窗保持深色系统样式（统一且文字清晰，不做主题化——v1.6.0 文字/背景混搭反而看不清的教训）
    }

    /** 数字输入对话框（isDecimal=true 允许小数） */
    private fun showNumberInputDialog(title: String, init: String, isDecimal: Boolean, onApply: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(init)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    (if (isDecimal) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            hint = if (isDecimal) "如 0.5 或 3" else "1 - 30"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onApply(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─────────────── 自动连线入口 ───────────────

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val code = result.resultCode
                val data = result.data!!
                // HyperOS/MIUI 竞态防护：系统授权框关闭瞬间应用可能被判定为后台，
                // startForegroundService 会抛 ForegroundServiceStartNotAllowedException → 延迟重试
                if (!tryStartProjection(code, data)) {
                    mainHandler.postDelayed({
                        if (!tryStartProjection(code, data)) {
                            Toast.makeText(this, "录屏服务启动失败，请重新点击「连线」再试", Toast.LENGTH_LONG).show()
                        }
                    }, 900)
                }
            } else {
                Toast.makeText(this, "未授权屏幕录制，无法连线", Toast.LENGTH_SHORT).show()
            }
        }

    private fun tryStartProjection(code: Int, data: Intent): Boolean = try {
        AutoPlayService.start(this, code, data)
        true
    } catch (t: Throwable) {
        android.util.Log.w("AutoChess", "startProjection retry: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun startAutoPlayFlow() {
        // 1. 无障碍
        if (ChessAccessibilityService.instance == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要无障碍权限")
                .setMessage("自动走棋需要无障碍服务来模拟点击落子。\n\n请在接下来的页面中找到「AutoChess 智能棋手」并开启服务。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // 2. 悬浮窗
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("连线时需要悬浮窗来显示控制面板与引擎走法。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // 3. 屏幕录制授权
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
