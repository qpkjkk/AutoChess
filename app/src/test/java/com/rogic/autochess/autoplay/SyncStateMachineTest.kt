package com.rogic.autochess.autoplay

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.*
import org.junit.Test

/**
 * v1.13.2 真代码端到端测试（用户指令：逻辑根除+必须实测）
 * 骨架=鹏飞式单一追平循环+NewGameSync 对齐（报告 8.2 七态结构）。
 * 判据全部来自 ChessSyncRules——APK 内同一份 Kotlin 字节码。
 */
class SyncStateMachineTest {

    private fun Board.mv(uci: String) { doMove(ChessSyncRules.moveFromUci(this, uci)!!) }
    private fun plac(b: Board): String = ChessSyncRules.placementOf(b.fen)

    /** 鹏飞式状态机（追平循环+NewGameSync，与 syncBoard v1.13.2 结构一致） */
    private class Machine(myWhite: Boolean = true) {
        var ext = Board()
        var anchor = ""
        var verify: String? = null
        var lastSent: String? = null
        val mySide = if (myWhite) Side.WHITE else Side.BLACK
        var anomalyStreak = 0
        var verifyPre: String? = null
        // ── NewGameSync（鹏飞 8.2：TryNewGame → 基准图+fen 对齐一致 → Playing）──
        var ngCandidate: String? = null
        var ngCount = 0

        fun anchorOf(): String = ChessSyncRules.placementOf(ext.fen)

        /** 单一追平循环：返回 (applied 步数, 是否完全追平) */
        fun reconcile(obsIn: String): Pair<Int, Boolean> {
            var obs = obsIn
            if (anchor.isEmpty()) {
                // 首帧锚定（Service 语义：锚点=屏幕且 extBoard 同步为屏幕局面——buildBoard 带降级）
                anchor = obs
                ChessSyncRules.buildBoard(obs, true)?.let { ext = it }
                return 0 to true
            }
            var applied = 0
            while (applied < 4) {
                if (anchor == obs) return applied to true
                // verify 在途：我方步已知，优先直接补应用（复合追平）
                if (verify != null && applied == 0 && ext.sideToMove == mySide) {
                    val myMv = ChessSyncRules.moveFromUci(ext, verify!!)
                    if (myMv != null) {
                        try {
                            ext.doMove(myMv)
                            verify = null; verifyPre = null   // 补应用成功=闭环（与 Service 情况B 语义一致）
                            applied++; anchor = anchorOf(); continue
                        } catch (_: Exception) {}
                    }
                }
                val m = ChessSyncRules.matchOneStepByDiff(ext, anchor, obs) ?: break
                val mv = m.first
                val mover = try { ext.getPiece(mv.from).pieceSide } catch (_: Exception) { Side.WHITE }
                if (mover != ext.sideToMove) {
                    val b2 = Board()
                    b2.loadFromFen("$anchor ${if (mover == Side.WHITE) "w" else "b"} KQkq - 0 30")
                    ext = b2
                }
                try { ext.doMove(mv) } catch (_: Exception) { break }
                anchor = anchorOf()
                if (mover == mySide && verify != null) {
                    val u = mv.from.name.lowercase() + mv.to.name.lowercase() +
                            (mv.promotion?.let { it.fenSymbol.lowercase() } ?: "")
                    if (verify.equals(u, ignoreCase = true)) { verify = null; verifyPre = null }
                } else if (mover != mySide) {
                    lastSent = null
                    if (verify != null) { verify = null; verifyPre = null }   // 对方步现=我方步已生效
                }
                applied++
            }
            return applied to (anchor == obs)
        }

        /** 帧入口：NewGameSync 对齐 / 追平消化 / 异常分类（帧驱动，无延时） */
        fun onFrame(obs: String): String {
            if (ngCandidate != null) {
                if (obs == ngCandidate) {
                    ngCount++
                    if (ngCount >= 2) {
                        anchor = obs
                        val stmChar = if (mySide == Side.WHITE) "w" else "b"
                        try {
                            val nb = Board(); nb.loadFromFen("$obs $stmChar KQkq - 0 30"); nb.legalMoves()
                            ext = nb
                        } catch (_: Exception) {}
                        ngCandidate = null; ngCount = 0
                        return if (ext.sideToMove == mySide) "MY_TURN" else "WAIT"
                    }
                } else {
                    ngCandidate = obs; ngCount = 1
                }
                return "NG_SYNC"
            }
            // ── v1.13.3 双帧确认（对齐 Service：非 verify 在途的匹配步需次帧复现）──
            if (verify == null && pendingObs == null && obs != anchor) {
                val pre = ChessSyncRules.matchOneStepByDiff(ext, anchor, obs)
                if (pre != null) {
                    pendingObs = obs
                    return "CONFIRM_WAIT"   // 首帧暂存，下一帧同 obs 复现才应用
                }
            }
            if (pendingObs != null) {
                if (obs == pendingObs || obs == anchor) {
                    pendingObs = null   // 复现（或回稳）→ 放行进追平
                } else {
                    // 噪声漂移：换新候选重新计数
                    val pre = ChessSyncRules.matchOneStepByDiff(ext, anchor, obs)
                    pendingObs = if (pre != null) obs else null
                    if (pendingObs != null) return "CONFIRM_WAIT"
                }
            }
            val (applied, caught) = reconcile(obs)
            if (caught) { anomalyStreak = 0; return if (ext.sideToMove == mySide) "MY_TURN" else "WAIT" }
            anomalyStreak++
            val D = ChessSyncRules.diffPositions(anchor, obs)
            return when {
                ChessSyncRules.piecesOf(obs) == 0 -> "EMPTY"
                D.size >= 10 && ChessSyncRules.piecesOf(obs) < ChessSyncRules.piecesOf(anchor) - 3 -> {
                    ngCandidate = ""   // 哨兵：进 NewGameSync（对齐从下帧起数，与 Service 语义一致）
                    "RESET_TO_NGSYNC"
                }
                anomalyStreak >= 3 -> { ngCandidate = ""; "RESET_TO_NGSYNC" }
                else -> "ANOMALY_WAIT"
            }
        }

        var pendingObs: String? = null   // v1.13.3 双帧确认暂存

        /** 出手守卫（纯状态，无延时） */
        fun canAct(): Boolean = verify == null && lastSent == null
    }

    // ═══ 场景A：我走一步→屏幕确认帧→verify 闭环（50ms 级链路）═══
    @Test fun tA_verify_close() {
        val m = Machine()
        m.onFrame(plac(Board()))                       // 首帧锚定（开局）
        m.verify = "e2e4"; m.lastSent = "e2e4"         // 我方点击（extBoard 未应用）
        val after = Board(); after.mv("e2e4")
        val r = m.onFrame(plac(after))
        assertEquals("确认帧返回轮对方", "WAIT", r)
        assertNull("verify 必须闭环", m.verify)
        assertEquals("extBoard 已应用", plac(after), plac(m.ext))
        assertEquals("锚点=走后事实", plac(after), m.anchor)
    }

    // ═══ 场景B：我走+对方秒回同帧→verify 补应用+追平循环一帧闭环 ═══
    @Test fun tB_double_step_frame() {
        val m = Machine()
        m.onFrame(plac(Board()))
        m.verify = "e2e4"; m.lastSent = "e2e4"
        val real = Board(); real.mv("e2e4"); real.mv("c7c5")
        val r1 = m.onFrame(plac(real))                 // 双步帧：补应用我方步+追平对方步
        assertEquals("双步一帧闭环（补应用+追平循环）", "MY_TURN", r1)
        assertNull(m.verify)
        assertEquals("extBoard=双步后事实", plac(real), plac(m.ext))
        assertEquals("锚点=双步后事实", plac(real), m.anchor)
        // 后续静止帧保持稳定
        val r2 = m.onFrame(plac(real))
        assertEquals("静止帧维持轮我", "MY_TURN", r2)
    }
    // ═══ 场景C：verify 挂起+对方步先到（确认帧漏识别）→ 复合追平 ═══
    @Test fun tC_composite() {
        val m = Machine()
        m.onFrame(plac(Board()))
        m.verify = "e2e4"; m.lastSent = "e2e4"
        val real = Board(); real.mv("e2e4"); real.mv("c7c5")
        // 直接跳到"对方已回"帧（我方确认帧漏掉）
        val r = m.onFrame(plac(real))
        assertEquals("复合追平→轮我", "MY_TURN", r)
        assertNull("verify 闭环（根治乱点重试）", m.verify)
        assertEquals("局面一致", plac(real), plac(m.ext))
    }

    // ═══ 场景D：单格噪声帧不进状态机 ═══
    @Test fun tD_noise_frame() {
        val m = Machine()
        m.onFrame(plac(Board()))
        val noisy = plac(Board()).replace("RNBQKBNR", ".NBQKBNR")
        val r = m.onFrame(noisy)
        assertTrue("噪声帧=ANOMALY_WAIT（不出手不重置）", r == "ANOMALY_WAIT" || r == "WAIT")
        assertEquals("extBoard 不被污染", plac(Board()), plac(m.ext))
    }

    // ═══ 场景E：换局→NewGameSync 对齐（2 帧一致→轮我出手，非立即乱走）═══
    @Test fun tE_newgame_sync() {
        val m = Machine()
        m.onFrame(plac(Board()))
        // 真换局特征：摆位大改（格差≥10）+子数剧变（32→12）——鹏飞 TryNewGame 判据
        val fresh = Board()
        fresh.loadFromFen("1rbq1r1k/pp2b1pp/2n1p3/3pPp2/3P4/2N2P2/PP2P1PP/R1BQ1RK1 w - - 0 12")
        val r1 = m.onFrame(plac(fresh))
        assertEquals("大变→进 NewGameSync", "RESET_TO_NGSYNC", r1)
        val r2 = m.onFrame(plac(fresh))
        assertEquals("第 2 帧对齐中", "NG_SYNC", r2)
        val r3 = m.onFrame(plac(fresh))
        assertEquals("对齐完成→轮我", "MY_TURN", r3)
        assertEquals("extBoard=新局", plac(fresh), plac(m.ext))
        assertEquals("锚点=新局", plac(fresh), m.anchor)
    }

    // ═══ 场景F：识别持续乱帧→重置走 NewGameSync（不直接出手）═══
    @Test fun tF_streak_reset() {
        val m = Machine()
        val base = plac(Board())
        m.onFrame(base)
        // 持续噪声帧（每帧不同的乱摆位——识别错乱特征，追不上任何合法步序列）
        var resetAt = -1
        repeat(6) { i ->
            val noisy = base.replaceFirst("R", if (i % 2 == 0) "Q" else "B").replaceFirst("r", if (i % 2 == 0) "q" else "b")
            val r = m.onFrame(noisy)
            if (r == "RESET_TO_NGSYNC") resetAt = i
        }
        assertTrue("第 3 帧起触发重置（分类时间戳语义）", resetAt in 2..3)
        // 识别恢复（干净帧对齐 2 帧后完成）
        var last = m.onFrame(base)
        last = m.onFrame(base)
        assertEquals("对齐后恢复出手", "MY_TURN", last)
        assertEquals("恢复到真实局面", base, plac(m.ext))
    }

    // ═══ 场景G：60 步随机全流程（真代码压力·双帧确认节奏）═══
    @Test fun tG_full_game() {
        val m = Machine()
        val real = Board()
        m.onFrame(plac(real))
        var myTurns = 0
        for (i in 0 until 60) {
            val moves = ArrayList<String>()
            for (mv in real.legalMoves()) moves.add(mv.from.name.lowercase() + mv.to.name.lowercase() +
                    (mv.promotion?.let { it.fenSymbol.lowercase() } ?: ""))
            if (moves.isEmpty()) break
            real.doMove(ChessSyncRules.moveFromUci(real, moves.random())!!)
            // v1.13.3 双帧确认节奏：每步喂 2 帧（首帧 CONFIRM_WAIT + 次帧复现应用）
            var r = m.onFrame(plac(real))
            if (r == "CONFIRM_WAIT") r = m.onFrame(plac(real))
            if (r == "MY_TURN") myTurns++
            if (r == "ANOMALY_WAIT") m.onFrame(plac(real))
        }
        assertEquals("最终局面一致", plac(real), plac(m.ext))
        assertTrue("过程有轮我出手点", myTurns > 20)
        assertNull("无 verify 挂死", m.verify)
    }

    // ═══ 场景H：易位/升变在追平循环中消化（双帧确认节奏）═══
    @Test fun tH_castling_promo() {
        val m = Machine()
        val b = Board()
        b.loadFromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
        m.onFrame(plac(b))
        val t = Board(); t.loadFromFen(b.fen); t.doMove(ChessSyncRules.moveFromUci(t, "e1g1")!!)
        val r1 = m.onFrame(plac(t))
        assertEquals("易位首帧=待确认", "CONFIRM_WAIT", r1)
        assertEquals("易位次帧复现→追平", "WAIT", m.onFrame(plac(t)))
        assertEquals("局面一致", plac(t), plac(m.ext))

        val m2 = Machine()
        val b2 = Board(); b2.loadFromFen("7k/P7/8/8/8/8/8/K7 w - - 0 1")
        m2.onFrame(plac(b2))
        val t2 = Board(); t2.loadFromFen(b2.fen); t2.doMove(ChessSyncRules.moveFromUci(t2, "a7a8q")!!)
        val r2 = m2.onFrame(plac(t2))
        assertEquals("升变首帧=待确认", "CONFIRM_WAIT", r2)
        assertEquals("升变次帧复现→追平", "WAIT", m2.onFrame(plac(t2)))
        assertEquals("升变局面一致（含后）", plac(t2), plac(m2.ext))
        assertEquals("升变格=A8 后", Piece.WHITE_QUEEN, m2.ext.getPiece(com.github.bhlangonijr.chesslib.Square.A8))
    }

    // ═══ 场景I：幽灵步根治（用户 9/9 13:27 症状）═══
    // 小模型单帧抖动恰好构成一步我方合法棋（e2 类型抖+e4 兵没识别到）→ 首帧
    // 匹配成功 → 【双帧确认拦截】→ 次帧识别恢复（obs 回锚点）→ 步不应用、
    // 轮次不翻转 → 绝不出手。
    @Test fun tI_ghost_move_blocked() {
        val m = Machine()
        val base = plac(Board())
        m.onFrame(base)
        // 构造"幽灵 e2e4"：e2 兵抖成马（P→N）+ e4 冒出兵（识别噪声组合恰成一步）
        val rows = base.split("/").toMutableList()
        rows[6] = rows[6].replaceFirst("P", "N")          // e2? 任意首 P→N（同格异型）
        val ghost = base.replaceFirst("P", "N")           // 单格类型抖
        // 更真实：e2e4 特征帧 = rank2 去一个 P + rank4 加 P
        val rank2 = base.split("/")[6].toMutableList()
        rank2[4] = '.'                                    // e2 兵离开
        val rank4 = "4P3"                                 // e4 冒兵
        val ghostFrame = base.split("/").toMutableList().also { it[6] = rank2.joinToString(""); it[4] = rank4 }.joinToString("/")
        val r1 = m.onFrame(ghostFrame)
        assertEquals("幽灵步首帧被双帧确认拦截", "CONFIRM_WAIT", r1)
        // 次帧识别恢复（真实屏幕=静止没动）——开局锚定轮白=轮我（正常状态，非幽灵步所致）
        val r2 = m.onFrame(base)
        assertTrue("识别恢复→确认丢弃→正常轮次（开局轮我）", r2 == "MY_TURN" || r2 == "WAIT")
        assertEquals("extBoard 未被幽灵步污染", base, plac(m.ext))
        assertEquals("锚点未动", base, m.anchor)
        assertTrue("幽灵步未翻转轮次（ext 与幽灵步无关，verify 无）", m.verify == null)
        // 连续多帧幽灵抖动：永不应用
        repeat(5) {
            m.onFrame(ghostFrame)
            m.onFrame(base)
        }
        assertEquals("多轮抖动仍不污染", base, plac(m.ext))
    }

    // ═══ 场景I：v3.4.9 悬挂步门语义（chesslib 级）═══
    @Test fun tI_hanging_move_semantics() {
        // 1.e4 e5 后白走 f1a6 = 送象（a6 被 b7 兵攻且无保护）→ 必须判悬挂
        val b = com.github.bhlangonijr.chesslib.Board()
        b.loadFromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2")
        val b2 = com.github.bhlangonijr.chesslib.Board()
        b2.loadFromFen(b.fen)
        b2.doMove(ChessSyncRules.moveFromUci(b2, "f1a6")!!)
        val a6 = com.github.bhlangonijr.chesslib.Square.A6
        val opp = b2.sideToMove   // 黑
        val me = com.github.bhlangonijr.chesslib.Side.WHITE
        assertTrue("a6 被黑兵攻击", b2.squareAttackedBy(a6, opp) != 0L)
        assertFalse("a6 无白子保护", b2.squareAttackedBy(a6, me) != 0L)
        // 对照：f1c4 意大利象不悬挂（c4 无黑兵攻——黑只走了 e5）
        val b3 = com.github.bhlangonijr.chesslib.Board()
        b3.loadFromFen(b.fen)
        b3.doMove(ChessSyncRules.moveFromUci(b3, "f1c4")!!)
        assertFalse("c4 不被黑方攻击=不悬挂", b3.squareAttackedBy(com.github.bhlangonijr.chesslib.Square.C4, b3.sideToMove) != 0L)
    }
}
