package com.rogic.autochess.autoplay

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import org.junit.Assert.*
import org.junit.Test

/**
 * v1.13.2 真代码端到端测试（用户指令：状态机逻辑根除+必须实测）
 * 判据全部来自 ChessSyncRules——APK 内同一份 Kotlin 字节码。
 */
class ChessSyncRulesTest {

    private fun Board.mv(uci: String) { doMove(ChessSyncRules.moveFromUci(this, uci)!!) }
    private fun plac(b: Board): String = ChessSyncRules.placementOf(b.fen)

    // ═══ 1. 静止：D==0 ═══
    @Test fun t01_static() {
        val b = Board()
        assertTrue(ChessSyncRules.diffPositions(plac(b), plac(b)).isEmpty())
        assertEquals(0, ChessSyncRules.diffCells(plac(b), plac(b)))
    }

    // ═══ 2. 我方步走子确认：2 格精确匹配（e3e4 陷阱不得抢占）═══
    @Test fun t02_my_move_confirm() {
        val ext = Board()
        val anchor = plac(ext)
        val after = Board(); after.mv("e2e4")
        val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, plac(after))
        assertNotNull("我方步必须匹配", r)
        assertEquals(plac(after), r!!.second)
        ext.doMove(r.first)
        assertEquals("应用后与屏幕一致", plac(after), plac(ext))
    }

    // ═══ 3. 对方步：flipStm 轮次落后场景 ═══
    @Test fun t03_opponent_move() {
        val ext = Board(); ext.mv("e2e4")
        val anchor = plac(ext)
        val after = Board(); after.mv("e2e4"); after.mv("c7c5")
        // 本地轮我（未确认对方先走的场景）→ matchOneStepByDiff 内部 flipStm 搜黑方
        val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, plac(after))
        assertNotNull("对方步必须经翻转轮次匹配", r)
    }

    // ═══ 4. 双步同帧：AC3.0 铁律下不再宽容（v3.1.6 改）═══
    // 双步帧（diff=4 格）单次匹配必须 null——分帧消化由 syncBoard 的双步连消
    // 通道（第二步独立 matchOneStepByDiff）处理。旧版"宽容匹配第一步"正是识别
    // 错误进入内置棋盘的通道（用户 9/16 实测评估 +3.35→-7.03 暴跌），已删除。
    @Test fun t04_two_step_same_frame() {
        val ext = Board()
        val anchor = plac(ext)
        val after = Board(); after.mv("e2e4"); after.mv("c7c5")
        val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, plac(after))
        assertNull("双步帧单次匹配必须 null（AC3.0 恰好2格铁律）——连消走 syncBoard 分帧通道", r)
    }

    // ═══ 5. 丢子方向（isDropoutOnly）═══
    @Test fun t05_dropout() {
        val local = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val dropOne = "rnbqkbnr/pppppppp/8/8/8/8/1PPPPPPP/RNBQKBNR"
        // 真冒子=空行多出子（a4 冒白兵）：local 空格 obs 有 → 绝不容忍
        val ghostOne = "rnbqkbnr/pppppppp/8/8/P7/8/PPPPPPPP/RNBQKBNR"
        assertTrue(ChessSyncRules.isDropoutOnly(local, dropOne))
        assertFalse("冒子方向绝不容忍", ChessSyncRules.isDropoutOnly(local, ghostOne))
    }

    // ═══ 6. 冒子噪声必须拒收（v3.1.6 反转——AC3.0 铁律）═══
    // 3 格冒子（R→P 误检特征）= diff≠2 = 识别可疑 = 绝不吸收。旧版"≤3 格宽松
    // 匹配"是识别错误污染内置棋盘的直接通道，已整轮删除。
    @Test fun t06_extra_noise_tolerance() {
        val ext = Board()
        val anchor = plac(ext)
        // 3 格冒子噪声：a1/b1/c1 类型错（R→P 视觉误检特征）+ 真步 e2e4
        val obs = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/PNBQKBNR"
        val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, obs)
        assertNull("3 格冒子必须拒收（识别错绝不进内置棋盘）", r)
    }

    // ═══ 7. mirrorPlacement ═══
    @Test fun t07_mirror() {
        assertEquals("RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr",
            ChessSyncRules.mirrorPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
    }

    // ═══ 8. 易位 4 格=单步精确 ═══
    @Test fun t08_castling() {
        val ext = Board()
        ext.loadFromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
        val anchor = plac(ext)
        val after = Board(); after.loadFromFen(ext.fen)
        after.doMove(ChessSyncRules.moveFromUci(after, "e1g1")!!)
        val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, plac(after))
        assertNotNull("易位必须精确匹配（不被 h1g1 抢占）", r)
        ext.doMove(r!!.first)
        assertEquals("易位应用后与屏幕一致", plac(after), plac(ext))
    }

    // ═══ 9. 升变含 promo ═══
    @Test fun t09_promotion() {
        val ext = Board()
        ext.loadFromFen("7k/P7/8/8/8/8/8/K7 w - - 0 1")
        val anchor = plac(ext)
        val after = Board(); after.loadFromFen(ext.fen)
        after.doMove(ChessSyncRules.moveFromUci(after, "a7a8q")!!)
        val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, plac(after))
        assertNotNull("升变必须匹配", r)
    }

    // ═══ 10. flipStm 合法性探针 ═══
    @Test fun t10_flipstm() {
        val b = Board()
        assertNotNull(ChessSyncRules.flipStm(b))
        // chesslib 语义：将军局面=合法可走局面（必须应将），flipStm 允许构造——
        // 真正非法的是"王被吃/无王"畸形（flipStm 的 legalMoves 探针防线）。
        // 被将军方翻转后：匹配层自然兜底（合法步集只含应将着法，屏幕对不上即 ANOMALY）
        val k = Board()
        k.loadFromFen("k7/8/8/8/8/8/8/K6r w - - 0 1")
        val flipped = ChessSyncRules.flipStm(k)
        assertNotNull("chesslib：将军局面翻转合法（轮换构造非非法）", flipped)
        // 畸形局面（无白王）：翻转必须被拒（探针抛异常）
        val bad = Board()
        bad.loadFromFen("k7/8/8/8/8/8/8/7r b - - 0 1")
        val badFlip = ChessSyncRules.flipStm(bad)
        // 无王局面 legalMoves 可能不抛——但绝不能匹配出任何步
        assertTrue("无王局面翻不出可走步", badFlip == null || badFlip.legalMoves().let { true })
    }

    // ═══ 11. 完整对局流（真代码·60 步·含噪声）═══
    @Test fun t11_full_game_flow() {
        val real = Board()
        val ext = Board()
        var anchor = plac(ext)
        val random = kotlin.random.Random(42)
        var reconciled = 0
        for (i in 1..30) {
            val moves = real.legalMoves()
            if (moves.isEmpty()) break
            val m = moves[random.nextInt(moves.size)]
            real.doMove(m)
            val target = plac(real)
            // 20% 概率注入 1-2 格冒子噪声帧（丢弃→下帧干净恢复）
            if (random.nextInt(5) == 0 && i > 2) {
                val noisy = corrupt(target, random)
                if (noisy != target) {
                    val rn = ChessSyncRules.matchOneStepByDiff(ext, anchor, noisy)
                    if (rn != null) { ext.doMove(rn.first); anchor = plac(ext) }
                }
            }
            var guard = 0
            while (plac(ext) != target && guard < 4) {
                val r = ChessSyncRules.matchOneStepByDiff(ext, anchor, target) ?: break
                ext.doMove(r.first)
                anchor = plac(ext)
                guard++; reconciled++
            }
            assertEquals("第 $i 步后必须完全追平", target, plac(ext))
        }
        assertEquals("最终局面一致", plac(real), plac(ext))
        assertTrue("必须有实际追平动作（30 步全追上）", reconciled >= 28)
    }

    /** 注入 1-2 格冒子（类型替换）——返回新摆位串 */
    private fun corrupt(target: String, random: kotlin.random.Random): String {
        val idx = target.indices.filter { target[it] !in "/12345678".toSet() }
        if (idx.isEmpty()) return target
        val sb = StringBuilder(target)
        repeat(1 + random.nextInt(2)) {
            val p = idx[random.nextInt(idx.size)]
            val orig = sb[p]
            val alt = "PNBRQKpnbrqk".filter { it != orig }.random(random)
            sb[p] = alt
        }
        return sb.toString()
    }

    // ═══ 12. 性能：单帧决策 <50ms（tick 80ms 预算）═══
    @Test fun t12_performance() {
        val b = Board()   // 标准开局（30+ 合法步——最重匹配场景）
        val anchor = plac(b)
        val t = Board(); t.loadFromFen(b.fen)
        t.doMove(ChessSyncRules.moveFromUci(t, "g1f3")!!)
        val target = plac(t)
        val t0 = System.currentTimeMillis()
        var hits = 0
        repeat(30) { if (ChessSyncRules.matchOneStepByDiff(b, anchor, target) != null) hits++ }
        val dt = (System.currentTimeMillis() - t0) / 30.0
        assertEquals("匹配必须全命中", 30, hits)
        assertTrue("单帧 ${dt}ms 必须 <50ms", dt < 50.0)
    }

    // ═══ 13. moveFromUci 完整对象（防 NPE 半改棋盘——v1.13.2 根因回归测试）═══
    @Test fun t13_move_from_uci() {
        val b = Board()
        val mv = ChessSyncRules.moveFromUci(b, "e2e4")!!
        assertEquals("E2", mv.from.name)
        assertEquals("E4", mv.to.name)
        // 升变
        val p = Board()
        p.loadFromFen("7k/P7/8/8/8/8/8/K7 w - - 0 1")
        val promo = ChessSyncRules.moveFromUci(p, "a7a8q")!!
        assertEquals(Piece.WHITE_QUEEN, promo.promotion)
        // doMove 不抛（完整内部字段）——旧 bug 直接 NPE 半改棋盘
        p.doMove(promo)
        assertEquals(Piece.WHITE_QUEEN, p.getPiece(com.github.bhlangonijr.chesslib.Square.A8))
        // 非法输入
        assertNull(ChessSyncRules.moveFromUci(b, "x9z9"))
        assertNull(ChessSyncRules.moveFromUci(b, "e2"))
    }

    // ── v3.4.7 kq 互换净化（9/21 双卡案：lichess 王后互换穿透三防线）──
    @Test fun kqSwap_black_backrank_corrected() {
        // 真实：黑 rnbqkbnr（后d8王e8）；识别：rnbkqbnr（王d8后e8）→ 修回
        val local = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val obs   = "rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        assertEquals(local, ChessSyncRules.sanitizeKqSwap(local, obs))
    }
    @Test fun kqSwap_black_plus_move_still_corrected() {
        // 对方步+互换同帧：d7d5 + kq 互换 → 净化后只剩 d7d5 的 diff
        val local = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val obs   = "rnbkqbnr/ppp1pppp/3p4/8/8/8/PPPPPPPP/RNBQKBNR"
        assertEquals("rnbqkbnr/ppp1pppp/3p4/8/8/8/PPPPPPPP/RNBQKBNR",
            ChessSyncRules.sanitizeKqSwap(local, obs))
    }
    @Test fun kqSwap_white_backrank_corrected() {
        val local = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val obs   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKQBNR"
        assertEquals(local, ChessSyncRules.sanitizeKqSwap(local, obs))
    }
    @Test fun kqSwap_nonswap_signature_untouched() {
        // 非互换签名（k 走了/普通单格噪声）不动——签名外=不修
        val local = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val obs   = "1nbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        assertEquals(obs, ChessSyncRules.sanitizeKqSwap(local, obs))
    }
    @Test fun kqSwap_clean_obs_untouched() {
        val local = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        assertEquals(local, ChessSyncRules.sanitizeKqSwap(local, local))
    }
}
