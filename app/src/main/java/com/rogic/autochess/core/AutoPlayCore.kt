package com.rogic.autochess.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move

/**
 * 状态机核（抽核手术·刀 2）——宿主无关。
 *
 * 规格 = docs/plans/2026-09-28-基线-状态流转表.md（行为基线零偏移对照）
 * 输入: onFrame(rec) —— 识别帧（occupancy 闭环层!! types 只作平票裁决）
 * 输出: CoreOut 接口（点击坐标/状态上屏）——宿主适配
 *
 * v3.4.x 全部根治修复内置:
 *  - 门基准归位（秒回死锁 9/26!!）
 *  - 判边门后置（执边污染!!）
 *  - 门 5 帧放行重置链（重定位死循环!!）
 *  - 重发守卫（重复点击!!）
 */
class AutoPlayCore(
    private val platform: Platform,
    private val out: CoreOut,
) {
    enum class State { IDLE, CONNECTING, WATCH, THINK, PLAY, NEW_GAME_SYNC }

    // ── 三盘副本（基线!!）──
    var extBoard = Board()
    private var anchorSig = ""          // v1.13.0 锚点（屏幕事实）
    // verify 闭环状态
    var verifyUci: String? = null
    var clickAfterSig = ""
    var clickAfterUci = ""
    var clickBeforeSig = ""
    var verifyDeadline = 0L
    private var tapAttempts = 0

    // 门状态
    private var badQualityCount = 0

    // 执边
    var mySideWhite = true
    var orientLock: Boolean? = null

    /** 输出契约：宿主实现（APK=无障碍点击/沙箱=agent-browser 点击） */
    interface CoreOut {
        fun onClick(fx: Int, fy: Int, tx: Int, ty: Int)   // 格坐标（0-7!!）宿主换算屏幕
        fun onStatus(text: String)
    }

    /** 识别帧输入（宿主喂——APK=YOLO/沙箱=Python ONNX） */
    data class Frame(
        val placement: String,        // 8 行 FEN 摆位段
        val whiteAtBottom: Boolean,
        val pieceCount: Int,
    )

    fun start() {
        platform.scheduler.post { out.onStatus("core ready") }
    }

    // ═══ 烂帧门（v3.4.15 门基准归位 + v3.4.16 第5帧放行!!）═══
    private fun gateBase(): String =
        if (clickAfterSig.isNotEmpty()) clickAfterSig else placementOf(extBoard)

    fun onFrame(frame: Frame) {
        val gate = gateBase()
        val gatePieces = gate.count { it != '/' && !it.isDigit() }
        val diff = occDiff(gate, frame.placement)
        if (gatePieces > 0 && (kotlin.math.abs(frame.pieceCount - gatePieces) > 3 || diff > 3)) {
            badQualityCount++
            if (badQualityCount >= 5) {
                badQualityCount = 4   // 保持热（v3.4.16!!）
                // 放行进重置链（不盲清!!）——屏幕=真理
                platform.scheduler.post { out.onStatus("门拦5帧·放行重置链(occ=$diff 盘$gatePieces)") }
                // 落到 syncBoard 评估（本帧不 return!!）
            } else {
                return
            }
        } else {
            badQualityCount = 0
        }
        syncBoard(frame)
    }

    // ═══ syncBoard 核心（verify 4 分支+双帧确认+追平——v3.4.18 重发守卫!!）═══
    private var pendingConfirmPlacement: String? = null   // 双帧确认暂存（对方步!!）

    private fun syncBoard(frame: Frame) {
        val obs = frame.placement
        val local = placementOf(extBoard)
        if (obs == local) {
            // 静止接受 → 锚点=棋盘事实
            anchorSig = local
            if (verifyUci != null) {
                if (platform.clock.now() > verifyDeadline) {
                    // ── v3.4.18 重发守卫（重复点击根治!!）──
                    val recentOcc = occOf(obs)
                    val afterOcc = if (clickAfterSig.isNotEmpty()) occOf(clickAfterSig) else ""
                    if (recentOcc.length == 64 && afterOcc.length == 64) {
                        var d = 0
                        for (i in 0 until 64) if (recentOcc[i] != afterOcc[i]) d++
                        if (d <= 2) {
                            verifyDeadline = platform.clock.now() + 3000
                            platform.scheduler.post { out.onStatus("我步已在屏·等消化(差$d 格)") }
                            return
                        }
                    }
                    handleTapFailed()
                }
                return
            }
            if (extBoard.sideToMove.name == (if (mySideWhite) "WHITE" else "BLACK")) {
                maybeAutoPlay()
            }
            return
        }
        // ── 变化帧: verify 闭环 4 分支 ──
        if (verifyUci != null && clickAfterSig.isNotEmpty()) {
            val obsOcc = occOf(obs)
            val afterOcc = occOf(clickAfterSig)
            val beforeOcc = occOf(clickBeforeSig)
            // ① 闭环
            if (occEqTolerant(obsOcc, afterOcc)) { verifyClosed(obs); return }
            // ② 幂等重发
            if (occEqTolerant(obsOcc, beforeOcc)) { handleTapFailed(); return }
            // ③ 双步闭环（枚举对方合法步!!）
            if (tryDoubleStep(obs, obsOcc)) return
            // ④ 丢帧（下帧再判——平票等!!)
            return
        }
        // ── 无 verify: 对方步双帧确认 ──
        val matched = findMoveToPlacement(extBoard, obs)
        if (matched != null) {
            if (pendingConfirmPlacement == obs) {
                pendingConfirmPlacement = null
                applyMove(matched)
            } else {
                pendingConfirmPlacement = obs   // 首帧暂存
            }
        } else {
            pendingConfirmPlacement = null
        }
    }

    // ═══ verify ③: 枚举对方步找双步闭环（平票→类型 diff——本版先 occ 独占!!）═══
    private fun tryDoubleStep(obs: String, obsOcc: String): Boolean {
        val b2 = Board()
        b2.loadFromFen(clickAfterFen())
        val moves = try { b2.legalMoves() } catch (_: Throwable) { return false }
        var hits = 0
        for (m in moves) {
            try {
                val b3 = Board(); b3.loadFromFen(b2.fen); b3.doMove(m)
                if (occOf(placementOf(b3)) == obsOcc) { hits++; if (hits == 1) applyTwoSteps(m, obs) }
                if (hits > 1) break   // 平票——暂等（本帧不消化!!）
            } catch (_: Throwable) {}
        }
        return hits == 1
    }

    // ─── 工具 ───
    fun placementOf(b: Board): String {
        val fen = b.fen
        return fen.split(" ")[0]
    }
    private fun clickAfterFen(): String {
        // clickAfterSig 是 placement——重建 fen（轮对方!!）: clickAfterUci 已是对方回合
        val b = Board()
        b.loadFromFen(extBoard.fen)
        try { moveFromUci(b, clickAfterUci)?.let { b.doMove(it) } } catch (_: Throwable) {}
        return b.fen
    }
    // v1.13.0 教训: 直接构造 Move 会 movePiece NPE 且半改棋盘——必须 legalMoves 匹配!!
    private fun moveFromUci(b: Board, uci: String): Move? {
        if (uci.length < 4) return null
        return try {
            val from = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(0, 2).uppercase())
            val to = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(2, 4).uppercase())
            val promoC = if (uci.length >= 5) java.lang.Character.toLowerCase(uci[4]) else ' '
            b.legalMoves().firstOrNull { m ->
                m.from == from && m.to == to &&
                        (promoC == ' ' || (m.promotion?.fenSymbol?.lowercase()?.firstOrNull() ?: ' ') == promoC)
            }
        } catch (_: Exception) { null }
    }
    fun occOf(plac: String): String {
        val sb = StringBuilder()
        for (ch in plac) {
            when {
                ch == '/' -> {}
                ch.isDigit() -> repeat(ch.digitToInt()) { sb.append('0') }
                else -> sb.append('1')
            }
        }
        return if (sb.length == 64) sb.toString() else ""
    }
    private fun occDiff(a: String, b: String): Int {
        val x = occOf(a); val y = occOf(b)
        if (x.length != 64 || y.length != 64) return 99
        var d = 0
        for (i in 0 until 64) if (x[i] != y[i]) d++
        return d
    }
    private fun occEqTolerant(a: String, b: String): Boolean {
        if (a.length != 64 || b.length != 64) return a == b
        var d = 0
        for (i in 0 until 64) {
            if (a[i] != b[i]) {
                d++
                if (a[i] != '1' || b[i] != '0') return false   // 反向=幽灵子绝不容忍
            }
        }
        return d <= 1
    }
    private fun findMoveToPlacement(b: Board, target: String): Move? {
        val targetOcc = occOf(target)
        if (targetOcc.length != 64) return null
        val moves = try { b.legalMoves() } catch (_: Throwable) { return null }
        for (m in moves) {
            try {
                val b2 = Board(); b2.loadFromFen(b.fen); b2.doMove(m)
                if (occOf(placementOf(b2)) == targetOcc) return m
            } catch (_: Throwable) {}
        }
        return null
    }
    private fun applyMove(m: Move) {
        try { extBoard.doMove(m) } catch (_: Throwable) {}
        anchorSig = placementOf(extBoard)
    }
    private fun applyTwoSteps(m: Move, obs: String) {
        try {
            extBoard.doMove(moveFromUci(extBoard, clickAfterUci)!!)
            extBoard.doMove(m)
        } catch (_: Throwable) {}
        anchorSig = obs
        clearVerify()
    }
    private fun verifyClosed(obs: String) {
        try { moveFromUci(extBoard, clickAfterUci)?.let { extBoard.doMove(it) } } catch (_: Throwable) {}
        anchorSig = obs
        clearVerify()
    }
    private fun clearVerify() {
        verifyUci = null; clickAfterSig = ""; clickAfterUci = ""; clickBeforeSig = ""; tapAttempts = 0
    }
    private fun handleTapFailed() {
        tapAttempts++
        if (tapAttempts > 3) { clearVerify(); return }
        platform.scheduler.post { out.onStatus("落子未生效·重试($tapAttempts/3)") }
        // 宿主重发（performMove 语义——core 层发 onClick!!）
        requestClick(clickAfterUci)
    }
    fun requestClick(uci: String) {
        // 解析 uci → 格坐标（fx fy tx ty——0-7!!）宿主换算
        val f = squareToRC(uci.substring(0, 2))
        val t = squareToRC(uci.substring(2, 4))
        if (f != null && t != null) out.onClick(f.first, f.second, t.first, t.second)
    }

    /** 沙箱宿主报「引擎出步 uci」——建 verify 上下文+发点击（performMove 语义!!） */
    fun setupVerifyAndClick(uci: String) {
        if (uci.length < 4) return
        val before = placementOf(extBoard)
        val b2 = Board(); b2.loadFromFen(extBoard.fen)
        try { moveFromUci(b2, uci)?.let { b2.doMove(it) } } catch (_: Throwable) {}
        clickBeforeSig = before
        clickAfterSig = placementOf(b2)
        clickAfterUci = uci
        verifyUci = uci
        tapAttempts = 0
        verifyDeadline = platform.clock.now() + 3500
        requestClick(uci)
        platform.scheduler.post { out.onStatus("点击 $uci · 验证窗口开启") }
    }
    private fun squareToRC(sq: String): Pair<Int, Int>? {
        if (sq.length < 2) return null
        val file = sq[0] - 'a'
        val rank = sq[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        // 白在底视角: 行=7-rank 列=file
        return (7 - rank) to file
    }
    private fun maybeAutoPlay() {
        platform.scheduler.post { out.onStatus("我方回合·引擎思考…") }
        // 引擎与出手策略由宿主注入（EnginePort——刀 3!!）
    }
}
