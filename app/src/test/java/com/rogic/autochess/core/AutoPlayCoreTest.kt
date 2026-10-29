package com.rogic.autochess.core

import com.github.bhlangonijr.chesslib.Board
import org.junit.Test
import org.junit.Assert.*

/**
 * core 状态机核 TDD（基线判据——秒回双步闭环为第一测试!!）
 * 规格: docs/plans/2026-09-28-基线-状态流转表.md
 */
class AutoPlayCoreTest {

    // 虚拟平台（沙箱宿主的雏形!!）
    class VirtualClock(var t: Long = 0L) : Clock { override fun now(): Long = t }
    class VirtualScheduler : Scheduler {
        val actions = mutableListOf<Pair<Long, () -> Unit>>()
        override fun post(action: () -> Unit) { actions.add(0L to action); action() }
        override fun postDelayed(ms: Long, action: () -> Unit) { actions.add(ms to action) }
    }
    class Out : AutoPlayCore.CoreOut {
        val clicks = mutableListOf<IntArray>()
        val statuses = mutableListOf<String>()
        override fun onClick(fx: Int, fy: Int, tx: Int, ty: Int) { clicks.add(intArrayOf(fx, fy, tx, ty)) }
        override fun onStatus(text: String) { statuses.add(text) }
    }

    private fun freshCore(): Triple<AutoPlayCore, VirtualClock, Out> {
        val clock = VirtualClock()
        val out = Out()
        val core = AutoPlayCore(Platform(clock, VirtualScheduler()), out)
        return Triple(core, clock, out)
    }

    private fun placementAfter(b: Board, uci: String): String {
        val b2 = Board(); b2.loadFromFen(b.fen)
        return try {
            val from = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(0, 2).uppercase())
            val to = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(2, 4).uppercase())
            b2.doMove(Move2(from, to, null)); corePlac(b2)
        } catch (_: Throwable) { corePlac(b2) }
    }
    private fun corePlac(b: Board): String = b.fen.split(" ")[0]
    private fun Move2(f: com.github.bhlangonijr.chesslib.Square, t: com.github.bhlangonijr.chesslib.Square, p: Any?): com.github.bhlangonijr.chesslib.move.Move = com.github.bhlangonijr.chesslib.move.Move(f, t, null)

    /** 基线 bug#1（v3.4.15 秒回死锁!!）: e4 点击→秒回 e5 帧→双步闭环一次消化 */
    @Test fun doubleStepClosure() {
        val (core, _, _) = freshCore()
        core.start()
        // 我方点击 e2e4（模拟 performMove 后的 verify 期状态）
        core.extBoard = Board()
        core.verifyUci = "e2e4"
        core.clickAfterUci = "e2e4"
        core.clickBeforeSig = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        core.clickAfterSig = placementAfter(Board(), "e2e4")
        core.verifyDeadline = Long.MAX_VALUE
        // 秒回帧: e4+e5（对方秒回!!）
        val afterBoth = placementAfter(Board().also { }
            , "e2e4").let { plac ->
            // e4 后再走 e7e5 的 placement
            val b = Board(); b.loadFromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2")
            corePlac(b)
        }
        core.onFrame(AutoPlayCore.Frame(afterBoth, whiteAtBottom = true, pieceCount = 32))
        // 验收: 双步闭环——extBoard 已推进两步（e4+e5!!）verify 已清!!
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR", core.placementOf(core.extBoard))
        assertNull(core.verifyUci)
    }

    /** 基线 bug#4（v3.4.18 重发守卫!!）: 点击已生效（occ 差≤2）超时绝不重发 */
    @Test fun resendGuard() {
        val (core, clock, out) = freshCore()
        core.start()
        core.extBoard = Board()
        core.verifyUci = "e2e4"
        core.clickAfterUci = "e2e4"
        core.clickBeforeSig = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        core.clickAfterSig = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"
        core.verifyDeadline = 1L   // 已超时!!
        clock.t = 100L
        // 屏幕静止但==clickAfterSig（点击已生效——verify ①应闭环!!）——守卫用「obs 已含我步」判!!
        core.onFrame(AutoPlayCore.Frame("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", true, 32))
        println("DEBUG statuses=" + out.statuses.joinToString("|"))
        println("DEBUG extBoard=" + core.placementOf(core.extBoard) + " verifyUci=" + core.verifyUci)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", core.placementOf(core.extBoard))
        assertNull(core.verifyUci)
        assertTrue(out.clicks.isEmpty())   // 没有重发点击!!
    }
}
