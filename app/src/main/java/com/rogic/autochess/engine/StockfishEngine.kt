package com.rogic.autochess.engine

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Stockfish UCI 引擎封装：以独立进程方式运行打包的 libstockfish.so，
 * 通过 stdin/stdout 交互 UCI 协议。
 */
class StockfishEngine(
    val id: String = "sf",
    private val threads: Int = 2,
    private val hashMb: Int = 64,
    private val listener: Listener? = null
) {
    interface Listener {
        fun onInfo(info: Info) {}
        fun onBestMove(bestMove: String?, ponder: String?) {}
        fun onReady() {}
        fun onEngineDead() {}
    }

    data class Info(
        val depth: Int = 0,
        val selDepth: Int = 0,
        val scoreCp: Int? = null,
        val scoreMate: Int? = null,
        val lowerBound: Boolean = false,
        val upperBound: Boolean = false,
        val nodes: Long = 0,
        val nps: Long = 0,
        val timeMs: Long = 0,
        val pv: List<String> = emptyList()
    )

    @Volatile private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val running = AtomicBoolean(false)
    @Volatile var lastInfo: Info? = null
        private set
    @Volatile var lastBestMove: String? = null
        private set
    @Volatile var lastPonder: String? = null
        private set

    /** 等待 bestmove 的回调注册（一次性） */
    @Volatile private var bestMoveCallback: ((String?, String?) -> Unit)? = null

    /** NNUE 权重文件名（SF18 默认双网络） */
    private val bigNet = "nn-c288c895ea92.nnue"
    private val smallNet = "nn-37f18f62d772.nnue"
    @Volatile private var evalFileDir: File? = null

    /** 从 assets 释放 NNUE 权重到 filesDir/nnue/（字节数硬校验——v1.10.0 根因修复：
     *  旧版只查 length()>0，拷贝被中断留下的截断文件永不修复 → 中盘连线首次评估
     *  读坏 NNUE → 引擎崩溃"引擎异常退出"（重启App也复现）。现在：字节数不符即删重拷。 */
    private fun ensureNets(context: Context): Boolean {
        val dir = File(context.filesDir, "nnue")
        if (!dir.exists()) dir.mkdirs()
        evalFileDir = dir
        return try {
            // 期望字节数（构建时实测）：assets 源文件大小即真值
            val expect = mapOf(bigNet to -1L, smallNet to -1L).toMutableMap()
            for (name in listOf(bigNet, smallNet)) {
                try {
                    context.assets.open("nnue/$name").use { ins -> expect[name] = ins.available().toLong() }
                } catch (_: Throwable) { expect[name] = -1L }
            }
            for (name in listOf(bigNet, smallNet)) {
                val out = File(dir, name)
                val want = expect[name] ?: -1L
                // 存在且字节数与 assets 完全一致 → 健康；否则（含截断/多余）删掉重拷
                val healthy = out.exists() && (want <= 0L || out.length() == want)
                if (healthy) continue
                if (out.exists()) out.delete()
                val tmp = File(dir, "$name.tmp")
                if (tmp.exists()) tmp.delete()
                context.assets.open("nnue/$name").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                // 拷贝后复核：字节数不符（存储异常）→ 失败
                if (want > 0 && tmp.length() != want) { tmp.delete(); return false }
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                    if (want > 0 && out.length() != want) return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun start(context: Context): Boolean {
        if (running.get()) {
            // v1.10.0：防假活——引用活着但进程已死（MIUI 冻结杀子进程）时强制重建
            val p = process
            val alive = try { p != null && p.isAlive } catch (_: Throwable) { false }
            if (alive) return true
            running.set(false)
            try { p?.destroy() } catch (_: Throwable) {}
            process = null; writer = null
        }
        return try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val exe = File(nativeDir, "libstockfish.so")
            if (!exe.exists()) {
                listener?.onEngineDead()
                return false
            }
            exe.setExecutable(true, false)
            val netsReady = ensureNets(context)
            val p = ProcessBuilder(exe.absolutePath)
                .redirectErrorStream(false)
                .start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            running.set(true)
            ready = false
            pendingGo = null
            thread(name = "uci-reader-$id") { readLoop(p) }
            send("uci")
            // NNUE 路径在 uciok 后设置（见 handleLine）
            netsPending = netsReady
            true
        } catch (e: Exception) {
            running.set(false)
            listener?.onEngineDead()
            false
        }
    }

    @Volatile private var netsPending = false
    /** NNUE 就绪（uciok→EvalFile→readyok 握手完成）前挂起的 go 命令（v1.10.6：
     *  旧版在引擎加载权重前就发 go → 引擎吞命令/崩——"显示思考但无深度"根因之一） */
    @Volatile private var pendingGo: String? = null
    @Volatile private var ready = false

    private fun readLoop(p: Process) {
        try {
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var sb = StringBuilder()
            while (running.get()) {
                val line = reader.readLine() ?: break
                handleLine(line.trim())
            }
        } catch (_: Exception) {
        }
        running.set(false)
        // v1.10.6：只有"本进程仍是被信任的实例"才报死亡——quit() 主动杀的不算
        // （旧版：自愈时 quit 旧引擎 → 旧 readLoop 退出也回调 onEngineDead → 把刚建好的
        //  新引擎又 quit 掉 → 自愈变自杀循环，用户实测"引擎待机/思考无输出"）
        if (p === process) {
            listener?.onEngineDead()
        }
    }

    private fun handleLine(line: String) {
        when {
            line == "uciok" -> {
                // 设置 NNUE 权重路径（SF18 双网络）
                evalFileDir?.let { dir ->
                    send("setoption name EvalFile value ${File(dir, bigNet).absolutePath}")
                    send("setoption name EvalFileSmall value ${File(dir, smallNet).absolutePath}")
                }
                send("isready")
            }
            line == "readyok" -> {
                ready = true
                // v1.10.6：NNUE 加载完成，补发此前挂起的 go（思考/分析命令不再被吞）
                pendingGo?.let { cmd -> send(cmd); pendingGo = null }
                listener?.onReady()
            }
            line.startsWith("info ") -> parseInfo(line)
            line.startsWith("bestmove") -> {
                val parts = line.split(" ")
                val best = parts.getOrNull(1)
                val ponder = if (parts.size >= 4 && parts[2] == "ponder") parts[3] else null
                lastBestMove = best
                lastPonder = ponder
                bestMoveCallback?.invoke(best, ponder)
                bestMoveCallback = null
                listener?.onBestMove(best, ponder)
            }
        }
    }

    private fun parseInfo(line: String) {
        val t = line.split(" ")
        var depth = 0; var sel = 0
        var cp: Int? = null; var mate: Int? = null
        var lower = false; var upper = false
        var nodes = 0L; var nps = 0L; var time = 0L
        var pv: List<String> = emptyList()
        var i = 1
        while (i < t.size) {
            when (t[i]) {
                "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                "seldepth" -> { sel = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                "nodes" -> { nodes = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i += 2 }
                "nps" -> { nps = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i += 2 }
                "time" -> { time = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i += 2 }
                "score" -> {
                    when (t.getOrNull(i + 1)) {
                        "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                        "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                    }
                    i += 3
                }
                "lowerbound" -> { lower = true; i += 1 }
                "upperbound" -> { upper = true; i += 1 }
                "pv" -> { pv = t.drop(i + 1); i = t.size }
                "string" -> return // info string 忽略
                else -> i += 1
            }
        }
        if (depth > 0 && pv.isNotEmpty()) {
            val info = Info(depth, sel, cp, mate, lower, upper, nodes, nps, time, pv)
            lastInfo = info
            listener?.onInfo(info)
        }
    }

    fun send(cmd: String) {
        try {
            writer?.apply {
                // 支持多行命令串（按 \n 拆行逐条写，UCI 按行解析）
                for (line in cmd.split('\n')) {
                    if (line.isNotBlank()) { write(line); write("\n") }
                }
                flush()
            }
        } catch (_: Exception) {
        }
    }

    fun setSkillLevel(level: Int) {
        send("setoption name Skill Level value ${level.coerceIn(0, 20)}")
    }

    fun newGame() {
        send("stop")
        send("ucinewgame")
        send("isready")
    }

    /** position + go depth */
    private fun positionCmd(fen: String, moves: List<String>): String = buildString {
        if (fen == START_FEN) append("position startpos")
        else append("position fen ").append(fen)
        if (moves.isNotEmpty()) append(" moves ").append(moves.joinToString(" "))
    }

    fun go(fen: String, moves: List<String> = emptyList(), depth: Int? = null, moveTimeMs: Long? = null) {
        send(positionCmd(fen, moves))
        lastBestMove = null
        lastInfo = null
        // 深度和时间同时给 → 两个都是上限，先到即停（用户设"最长思考3秒"必须被尊重）
        val go = when {
            depth != null && moveTimeMs != null -> "go depth $depth movetime $moveTimeMs"
            depth != null -> "go depth $depth"
            moveTimeMs != null -> "go movetime $moveTimeMs"
            else -> "go depth 12"
        }
        send(go)
    }

    /**
     * 无限分析模式：持续输出 info（深度/评分/线路）直到 stop()。
     * stop 后引擎返回 bestmove，经 listener.onBestMove 通知（不走 bestMoveCallback 通道）。
     */
    fun goInfinite(fen: String, moves: List<String> = emptyList()) {
        bestMoveCallback = null   // 作废旧请求的一次性回调，防尾流误触发
        val cmd = positionCmd(fen, moves) + "\ngo infinite"
        lastBestMove = null
        lastInfo = null
        if (ready) send(cmd) else pendingGo = cmd   // v1.10.6：NNUE 就绪前挂起
    }

    fun requestBestMove(fen: String, moves: List<String> = emptyList(), depth: Int? = null, moveTimeMs: Long? = null,
                        onDone: (String?, String?) -> Unit) {
        bestMoveCallback = onDone
        go(fen, moves, depth, moveTimeMs)
    }

    fun stop() = send("stop")

    fun quit() {
        running.set(false)
        // v1.10.7 修复：quit 的 send 可能因管道缓冲满而 write 阻塞（引擎死透时）→
        // waitFor 永远执行不到 → 调用方（自愈线程）被永久卡死 → "重启中…"挂死。
        // 顺序改为：先标记退出（readLoop 自然收尾），关写端（工程师侧 EOF），
        // 短超时等待，强杀兜底。
        try { writer?.let { runCatching { it.flush() } } } catch (_: Throwable) {}
        val p = process
        if (p != null) {
            // 后台排空输出管道（防 waitFor 死锁——子进程若还活着会收到 EOF 退出）
            thread(name = "quit-drain-$id") {
                try {
                    val r = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    while (r.readLine() != null) { /* 排空 */ }
                } catch (_: Throwable) {}
            }
            try { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            try { p.destroy() } catch (_: Throwable) {}
            try { p.destroyForcibly() } catch (_: Throwable) {}
        }
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
        process = null
    }

    fun isRunning() = running.get()

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
