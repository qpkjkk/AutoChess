package com.rogic.autochess.autoplay

import com.github.bhlangonijr.chesslib.Board
import org.junit.Assert.*
import org.junit.Test

/** v3.4.10 送象猎手仿真（三遍检查·第二遍）——真机三要素注入：kq 互换常态+滞后帧 */
class Ba6HuntTest {

    private fun Board.mv(uci: String) { doMove(ChessSyncRules.moveFromUci(this, uci)!!) }
    private fun plac(b: Board): String = ChessSyncRules.placementOf(b.fen)
    private fun bFrom(plac: String): Board {
        val b = Board(); b.loadFromFen(plac + " w KQkq - 0 1"); return b
    }

    /** 识别模拟器：真局面 → 带噪声的观察串 */
    private fun noisy(real: Board, kq: Boolean = true): String {
        val p = ChessSyncRules.placementOf(real.fen)
        if (!kq) return p
        val ranks = p.split("/").toMutableList()
        for (idx in listOf(0, 7)) {
            val r = ranks[idx]
            if (r.length == 8) {
                val c = r.toCharArray()
                val d = c[3]; val e = c[4]
                val isKq = (d == 'q' && e == 'k') || (d == 'k' && e == 'q') ||
                        (d == 'Q' && e == 'K') || (d == 'K' && e == 'Q')
                if (isKq) { c[3] = e; c[4] = d; ranks[idx] = String(c) }
            }
        }
        return ranks.joinToString("/")
    }

    @Test fun t1_kq_birth_anchor_chain() {
        // 场景：NGS 出生锚点=互换串（h1 铁证）+识别常态互换——完整消化链
        val real = Board()
        var anchor = noisy(real)   // 互换出生锚点
        assertEquals("rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKQBNR", anchor)

        real.mv("e2e4")
        // 黑 e5
        real.mv("e7e5")
        val obs = noisy(real)   // 互换版 1.e4 e5
        // 净化（锚点互换 + obs 互换 → 不触发反向修复——kq 对但 ob==la 不满足镜像条件）
        val san = ChessSyncRules.sanitizeKqSwap(anchor, obs)
        // 追平匹配（diff 路径——真代码 ChessSyncRules）
        val m = ChessSyncRules.matchOneStepByDiff(bFrom(anchor), anchor, san)
        // 消化结果必须是 e7e5 或 e2e4 之类真实一步——绝不可能是 f1a6
        if (m != null) {
            val u = m.first.toString()
            assertTrue("消化步必须是真实发生过的步，实际=$u",
                u == "e2e4" || u == "e7e5")
        }
    }

    @Test fun t2_no_real_move_yields_ba6() {
        // 枚举验证：从"互换初始锚点"出发的追平匹配，永远推不出 f1a6
        val anchor = "rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKQBNR"
        // 真盘各种开局序列
        for (seq in listOf(
            listOf("e2e4", "e7e5"), listOf("e2e4", "c7c5"), listOf("d2d4", "d7d5"),
            listOf("e2e4", "e7e6"), listOf("g1f3", "g8f6"), listOf("e2e4", "e7e5", "g1f3", "b8c6")
        )) {
            val real = Board()
            for (u in seq) real.mv(u)
            val obs = noisy(real)
            val san = ChessSyncRules.sanitizeKqSwap(anchor, obs)
            val m = ChessSyncRules.matchOneStepByDiff(bFrom(anchor), anchor, san)
            if (m != null) {
                val u = m.first.toString()
                assertTrue("序列 ${seq} 推出的步 $u 必须属于真实序列", seq.contains(u))
            }
        }
    }

    @Test fun t3_purify_never_invents_moves() {
        // 净化是恒等变换（只修 kq 两格）——不改变步集合。审计 1000 个随机局面
        val rnd = kotlin.random.Random(42)
        val b = Board()
        repeat(200) {
            val legal = b.legalMoves()
            if (legal.isEmpty()) return
            val mv = legal[rnd.nextInt(legal.size)]
            b.doMove(mv)
            val p = plac(b)
            val san = ChessSyncRules.sanitizeKqSwap(p, p)
            // 自净化必须恒等（同串输入）
            assertEquals("自净化恒等", p, san)
        }
    }

    // ═══ v3.4.11 终案：重建链净化语义 ═══
    @Test fun t4_purge_kq_rebuild() {
        // 三图铁证的互换串 → 重建前净化 → 标准串
        val bad = "rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKQBNR"
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
            ChessSyncRules.purgeKqOf(bad))
        // 1.e4 后的互换串（中兵行进+互换）
        val bad2 = "rnbkqbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR",
            ChessSyncRules.purgeKqOf(bad2))
        // 正常串恒等
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
            ChessSyncRules.purgeKqOf("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
        // 中盘串（无 kq 形态）恒等
        val mid = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R"
        assertEquals(mid, ChessSyncRules.purgeKqOf(mid))
    }

    @Test fun t5_rebuild_never_yields_swapped_board() {
        // bigJump 重建仿真：互换识别串 → buildBoard(purge) → extBoard 永不互换
        val obs = "rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKQBNR"
        val b = ChessSyncRules.buildBoard(ChessSyncRules.purgeKqOf(obs), true)
        assertNotNull(b)
        // 重建盘必须是标准王后排布
        val p = ChessSyncRules.placementOf(b!!.fen)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", p)
        // 重建盘 FEN 完整可加载（轮白+castling 合法）
        assertTrue(b.fen.startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w"))
    }

    // ═══ v3.4.12 终验：buildBoard 公共终点全堵 ═══
    @Test fun t6_buildboard_gates_all_entries() {
        // v3.4.11 漏堵 2053 情况D 的回归测试：互换串从"任何调用者"进 buildBoard
        for (bad in listOf(
            "rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",           // 互换初始（三图S=铁证）
            "rnbkqbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR",          // 互换+e4
            "rnbkqbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR",        // 互换+e4c6（j1记谱）
        )) {
            val b = ChessSyncRules.buildBoard(bad, true)
            assertNotNull("buildBoard 必须可构建", b)
            val p = ChessSyncRules.placementOf(b!!.fen)
            val r8 = p.split("/")[0]; val r1 = p.split("/")[7]
            // 互换=王在后位（d8='k' e8='q' / d1='K' e1='Q'）。标准=后d王e。
            assertFalse("rank8 不得呈互换形态: $r8", r8.length==8 && r8[3]=='k' && r8[4]=='q')
            assertFalse("rank1 不得呈互换形态: $r1", r1.length==8 && r1[3]=='K' && r1[4]=='Q')
            assertEquals("净化后 rank8=标准", "rnbqk", r8.take(5))
            assertEquals("净化后 rank1=标准", "RNBQK", r1.take(5))
        }
        // 互换怪盘在 1.e4 c6 真实形态上的对照（j1 记谱）——净化后书 probe 正常命中
        val b2 = ChessSyncRules.buildBoard("rnbkqbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR", true)
        assertEquals("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR", ChessSyncRules.placementOf(b2!!.fen))
    }

    @Test fun t7_swapped_board_sentinel_semantics() {
        // 终极守卫的判据语义：互换签名的检测（与 Service 内联逻辑同款）
        fun swapped(plac: String): Boolean {
            // 互换=王在后位+后在王位（成对反转）
            val r8 = plac.split("/")[0]; val r1 = plac.split("/")[7]
            return (r8.length==8 && r8[3]=='k' && r8[4]=='q') ||
                    (r1.length==8 && r1[3]=='K' && r1[4]=='Q')
        }
        assertTrue("互换串必须命中", swapped("rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
        assertFalse("标准串不得误判", swapped("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
        assertFalse("中盘正常串不得误判", swapped("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R"))
        // 中盘正常后到位形态（d8='q' e8='k'）不算互换
        assertFalse("后d8王e8=标准", swapped("3qkb1r/pppp1ppp/8/8/8/8/PPPP1PPP/RNBQK2R"))
        // 白方互换（d1='K' e1='Q'）必须命中
        assertTrue("白方互换命中", swapped("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKQBNR"))
    }

    // ═══ v3.4.13 识别质量门语义 ═══
    @Test fun t8_frame_quality_gate() {
        // 崩盘帧（云端复刻实测输出形态）必须负分
        val junk = listOf(
            "n......./......../....P.../......../PPPP.PPP/RNPQP.NR/.rr...../........",  // 实测乱码
            "pqqqp.../......../....P.../......../PPPP.PPP/RNPQP.NR/..r...../.bnnnp..",  // pqqqp/bnnn
        )
        for (j in junk) {
            assertTrue("崩盘帧必须拦: $j", ChessSyncRules.frameQuality(j) < 0)
        }
        // 真实局面（正常对局各阶段）必须 0 分放行
        val good = listOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",           // 开局
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR",       // 1.e4 e5
            "r1bqk2r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R",  // 意大利正常发展
            "rnbkqbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",           // kq 互换帧（occupancy 正常——质量门不拦，由 buildBoard 净化兜）
        )
        for (g in good) {
            assertEquals("正常帧必须放行: $g", 0, ChessSyncRules.frameQuality(g))
        }
    }
}
