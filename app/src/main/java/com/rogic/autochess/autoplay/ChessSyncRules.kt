package com.rogic.autochess.autoplay

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move

/**
 * v1.13.2 棋盘同步判据库（纯函数·无 Android 依赖）
 * 单一真源：AutoPlayService 与 JVM 单元测试共用同一份实现——
 * 模拟器曾用 Python 等价移植掩盖 Kotlin 实现 bug（用户 9/8 23:36 实测
 * "连线瘫痪"教训：验证必须跑真代码）。
 */
object ChessSyncRules {

    /** 摆位串 → 64 格字符数组（'.'=空）；非法返回 null */
    fun expandPlacement(p: String): CharArray? {
        val out = CharArray(64)
        var i = 0
        for (ch in p) {
            when {
                ch == '/' -> {}
                ch in '1'..'8' -> {
                    val n = ch - '0'
                    if (i + n > 64) return null
                    repeat(n) { out[i++] = '.' }
                }
                else -> {
                    if (i >= 64) return null
                    out[i++] = ch
                }
            }
        }
        return if (i == 64) out else null
    }

    /** v3.4.7 kq 互换净化（lichess 风格已知系统噪声签名修正——9/21 04:01 双卡案）：
     *  实测（c13/c14/w2 连续三局截图）lichess 风格 YOLO 把黑后排读成 rnbkqbnr
     *  （王 d8 后 e8，与真实 rnbqkbnr 恰好互换），occ 层无差但类型层 d8/e8 两格
     *  反转。该噪声穿透三处防线（追平 diff 匹配 / occ 消化的类型门 bestTd<=2 /
     *  verify 闭环），每处都死等"偶然读对的帧" → 用户实测"对方走了还显示对方
     *  行棋中/验证落子十几秒"。修法=识别层修正已知签名（非状态机容忍）：
     *  obs 的 d8/e8（或 d1/e1）两格与本地参照恰好互换（k↔q，双格成对）时，
     *  修回本地内容再进比对。签名为固定双格成对互换，不满足签名的不动。 */
    fun sanitizeKqSwap(localP: String, obsP: String): String {
        val la = expandPlacement(localP) ?: return obsP
        val ob = expandPlacement(obsP) ?: return obsP
        var changed = false
        for (base in intArrayOf(0, 56)) {          // rank8 行首 / rank1 行首
            val d = base + 3; val e = base + 4     // d 文件 + e 文件
            // 内容限定：必须恰好是 k/q 对互相反转（王后互换签名）。不加限定时
            // 对方真实步（子 e8→d8 一步，双格恰好镜像）也会被误修 → tG 分叉实锤。
            // k↔q 互换不可能由一步真实棋造成，误杀真步风险=零。
            val kq = (la[d]=='q'&&la[e]=='k')||(la[d]=='k'&&la[e]=='q')
                ||(la[d]=='Q'&&la[e]=='K')||(la[d]=='K'&&la[e]=='Q')
            val ok = kq && ob[d]==la[e] && ob[e]==la[d]
            if (ok) {
                ob[d] = la[d]; ob[e] = la[e]; changed = true
            }
        }
        if (!changed) return obsP
        // 重组摆位串（8 格一行，空格计数压缩）
        val sb = StringBuilder()
        for (r in 0 until 8) {
            if (r > 0) sb.append('/')
            var run = 0
            for (c in 0 until 8) {
                val ch = ob[r * 8 + c]
                if (ch == '.') run++ else {
                    if (run > 0) { sb.append(run); run = 0 }
                    sb.append(ch)
                }
            }
            if (run > 0) sb.append(run)
        }
        return sb.toString()
    }

    /** v3.4.11 重建链净化（9/25 05:22 送象终案根因修复）：
     *  bigJump/NGS/applyStep 重建 extBoard 时，识别帧的 d8/e8（d1/e1）若呈
     *  "k↔q 成对互换"形态（YOLO 对 lichess 的已知类型噪声——三图 S= 铁证
     *  rnbkqbnr），绝不能直接进 buildBoard——否则 extBoard=王后互换怪盘，
     *  书查不到（key 无条目）→引擎在怪盘选步→污染步在真实盘恰好合法→送象
     *  （审计行 ▶f1a6 ←engine @rnbkqbnr 一击实锤）。
     *  净化参照=标准王后排布（d8='q' e8='k'；d1='Q' e1='K'）。已知限制：
     *  真实中盘"王后互换位"（两步才能形成的极罕见盘）会被误修正——但 occ
     *  消化层类型免疫，风险远小于互换噪声（常态）直接进盘。9/21 一步真棋
     *  不可能造成成对互换的论证在此同样适用。
     */
    fun purgeKqOf(plac: String): String {
        val ranks = plac.split("/")
        if (ranks.size != 8) return plac
        val out = ranks.toMutableList()
        for (idx in intArrayOf(0, 7)) {
            val r = out[idx]
            if (r.length != 8) continue
            val c = r.toCharArray()
            val d = c[3]; val e = c[4]
            val swapped = (idx == 0 && ((d == 'k' && e == 'q') || (d == 'q' && e == 'k'))) ||
                    (idx == 7 && ((d == 'K' && e == 'Q') || (d == 'Q' && e == 'K')))
            if (swapped) { c[3] = if (idx == 0) 'q' else 'Q'; c[4] = if (idx == 0) 'k' else 'K'; out[idx] = String(c) }
        }
        return out.joinToString("/")
    }

    /** v3.4.13 识别帧质量分（9/25 05:46 用户指令"找到原因再修"根治）：
     *  云端复刻实测：tiny_piece48 在 lichess 风格上输出 pqqqp/bnnn 级乱码
     *  （行错位/类型崩）。这类帧绝不允许触发重置链（bigJump/情况D/NGS 重建
     *  的源头全是它们——识别崩→滞留→不一致→重置→坏盘→送象的因果链在
     *  这里斩断）。识别不可信时状态机保守等待（9/18 铁律：不确定就不动）。
     *  计分：王数≠2 / 某方兵>8 / 某方总子>16 / 首末行出现兵 / 每格类型合法
     *  全部通过=0（可信）；任一违反=负分（不可信）。 */
    fun frameQuality(plac: String): Int {
        try {
            val ranks = plac.split("/")
            if (ranks.size != 8) return -100
            var wk = 0; var bk = 0; var wp = 0; var bp = 0; var wc = 0; var bc = 0
            val legal = ".KQRBNPkqrbnp12345678"
            for ((ri, r) in ranks.withIndex()) {
                var i = 0
                while (i < r.length) {
                    val ch = r[i]
                    if (ch !in legal) return -100
                    if (ch.isDigit()) { i++; continue }
                    when (ch) {
                        'K' -> wk++
                        'k' -> bk++
                        'P' -> { wp++; if (ri == 0 || ri == 7) return -30 }  // 兵不可能在首末行
                        'p' -> { bp++; if (ri == 0 || ri == 7) return -30 }
                        in "QRBN" -> wc++
                        in "qrbn" -> bc++
                    }
                    i++
                }
            }
            if (wk != 1 || bk != 1) return -100
            if (wp > 8 || bp > 8) return -50
            if (wp + wc > 16 || bp + bc > 16) return -50
            // 类型极端混乱特征：单行同色≥4 个相同非兵子（识别崩的典型指纹——bnnn/pqqqp）
            for (r in ranks) {
                val compact = r.filter { it in "KQRBNPkqrbnp" }
                for (grp in listOf('Q', 'R', 'B', 'N', 'q', 'r', 'b', 'n')) {
                    if (compact.count { it == grp } >= 4) return -40
                }
            }
            return 0
        } catch (_: Throwable) { return -100 }
    }

    /** 两个摆位的差异格数（任一非法返回 99）——单格差 = 识别噪声特征 */
    fun diffCells(a: String, b: String): Int {
        val ga = expandPlacement(a) ?: return 99
        val gb = expandPlacement(b) ?: return 99
        var d = 0
        for (i in 0 until 64) if (ga[i] != gb[i]) d++
        return d
    }

    /** 方向性噪声判定：差异全部为"本地有子、屏幕为空"（识别丢子方向）且 ≤2 格 */
    fun isDropoutOnly(localP: String, obsP: String): Boolean {
        val ga = expandPlacement(localP) ?: return false
        val gb = expandPlacement(obsP) ?: return false
        var d = 0
        for (i in 0 until 64) {
            if (ga[i] != gb[i]) {
                val isDrop = ga[i] != '.' && gb[i] == '.'
                if (!isDrop) return false   // 出现方向/类型错乱 → 非纯丢子
                d++
                if (d > 2) return false
            }
        }
        return d in 1..2
    }

    /** 摆位镜像（上下颠倒的另一种视角解读）：rank 逐行倒序 */
    fun mirrorPlacement(p: String): String {
        val parts = p.split("/")
        if (parts.size != 8) return p
        return (7 downTo 0).joinToString("/") { parts[it] }
    }

    /** 摆位变化格下标集合 */
    fun diffPositions(a: String, b: String): Set<Int> {
        val ga = expandPlacement(a) ?: return setOf()
        val gb = expandPlacement(b) ?: return setOf()
        val out = HashSet<Int>()
        for (i in 0 until 64) if (ga[i] != gb[i]) out.add(i)
        return out
    }

    /** FEN → 摆位段（棋子布局，不含轮次/王车易位等） */
    fun placementOf(fen: String?): String = fen?.substringBefore(' ') ?: ""

    /** v3.4.0 occupancy 模式（PMC8321354 两阶段思想：occupancy 错误率 0.23% 远低于
     * 类型分类）。判据只含"有子/无子"——类型噪声（k↔K/p↔Q）数学免疫。 */
    fun occMaskOf(p: String): String {
        val g = expandPlacement(p) ?: return ""
        return String(CharArray(64) { i -> if (g[i] == '.') '0' else '1' })
    }

    /** 摆位串棋子总数 */
    fun piecesOf(p: String): Int = p.count { it != '/' && !it.isDigit() }

    /** UCI → 完整 Move：必须从 board.legalMoves() 匹配取现成对象——
     *  直接 new Move(from,to,promo) 缺内部字段（capturedPiece 等），doMove 时
     *  movePiece NPE **且半改棋盘**（v1.13.0-13.1"识别失败/验证挂死/乱走"总根因，
     *  JVM 真代码测试实锤）。Square.valueOf 必须【大写】。 */
    fun moveFromUci(board: Board, uci: String): Move? {
        if (uci.length < 4) return null
        return try {
            val from = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(0, 2).uppercase())
            val to = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(2, 4).uppercase())
            val promoC = if (uci.length >= 5) java.lang.Character.toLowerCase(uci[4]) else ' '
            board.legalMoves().firstOrNull { m ->
                m.from == from && m.to == to &&
                        (promoC == ' ' || (m.promotion?.fenSymbol?.lowercase()?.firstOrNull() ?: ' ') == promoC)
            }
        } catch (_: Exception) { null }
    }

    /** 构造可走局面：摆位+轮次 → Board。castling 先 KQkq，矛盾局面（王车不在
     *  初始位——中盘/残局）chesslib 拒载时降级 "-"（无易位权，保守可走）。
     *  单一构造出口：NewGameSync 重建/重置/reloadStm 全走这里，杜绝各处
     *  "KQkq 矛盾→加载失败→catch 吞→extBoard 没换成屏幕局面"的静默失败链。 */
    fun buildBoard(placement: String, whiteToMove: Boolean): Board? {
        // v3.4.12 净化下沉（用户指令"必须检查三遍"终版）：重建入口统一在
        // buildBoard 内部过 kq 净化——任何调用者（bigJump/NGS/applyStep/情况D
        // 重置/reloadStm……）无法再灌入互换串（v3.4.11 漏堵 2053 情况D 的教训：
        // 逐点堵=漏点游戏）。正常串恒等无害。
        val plac0 = purgeKqOf(placement)
        val stm = if (whiteToMove) "w" else "b"
        return try {
            val b = Board()
            b.loadFromFen("$plac0 $stm KQkq - 0 30")
            b.legalMoves()
            b
        } catch (_: Throwable) {
            try {
                val b = Board()
                b.loadFromFen("$plac0 $stm - - 0 30")
                b.legalMoves()
                b
            } catch (_: Throwable) { null }
        }
    }

    /** 翻转轮次（同摆位）；非法局面（不该走方被将军）返回 null */
    fun flipStm(board: Board): Board? {
        return try {
            val f = board.fen.split(" ")
            val stm = if (f.getOrNull(1) == "w") "b" else "w"
            val b = Board()
            b.loadFromFen(f[0] + " " + stm + " " + f.getOrElse(2) { "KQkq" } + " " + f.getOrElse(3) { "-" } + " " + f.getOrElse(4) { "0" } + " " + f.getOrElse(5) { "1" })
            b.legalMoves().size   // 合法性探针：非法局面在此抛异常
            b
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 单步 diff 匹配（v1.13.0 核心判据）：屏幕变化格 D 能否被【一步合法棋】解释。
     * 两轮判据：
     *  · 第一轮（精确）：该步影响格 A == D → 完全解释；
     *  · 第二轮（方向性宽松）：A 与 D 的对称差只允许【单侧】——
     *    仅 D-A（屏幕冒子：观察多出的格，锚点用棋盘事实覆盖）≤3，或
     *    仅 A-D（识别丢子：该步影响的格屏幕没认出来）≤2；
     *    两侧皆非空 = 混合噪声 → 拒绝（防 e3e4 误判成 e2e4）。
     *  · 两侧性：先按本地当前轮次搜，再按翻转轮次搜（对方步在屏幕、本地轮次落后）。
     *  返回 (着法, 走后摆位=棋盘事实版)。
     *  ⚠ doMove/undoMove 均在传入对象上执行并严格配对恢复——调用方持有该
     *  board 的 undo 栈不受影响（栈式平衡）。
     */
    fun matchOneStepByDiff(board: Board, lastSig: String, sig: String): Pair<Move, String>? {
        val sig = sanitizeKqSwap(lastSig, sig)   // v3.4.7：先修 kq 互换已知噪声
        val D = diffPositions(lastSig, sig)
        if (D.isEmpty() || D.size > 6) return null   // 变化格太多 → 非单步特征（易位4格上限内）
        val boards = ArrayList<Board>(2)
        boards.add(board)
        flipStm(board)?.let { boards.add(it) }
        // 第一轮：精确匹配（A == D 且 变化格上值一致——升变 q/r/b/n 同格集异值的坑）
        val sigCells = expandPlacement(sig)
        if (sigCells == null) return null
        for (b in boards) {
            val moves = try { b.legalMoves() } catch (_: Throwable) { emptyList<Move>() }
            for (m in moves) {
                try {
                    b.doMove(m)
                    val after = placementOf(b.fen)
                    b.undoMove()
                    if (after.isEmpty()) continue
                    if (diffPositions(lastSig, after) == D) {
                        val afterCells = expandPlacement(after) ?: continue
                        if (D.all { afterCells[it] == sigCells[it] }) return m to after
                    }
                } catch (_: Exception) {
                    try { b.undoMove() } catch (_: Exception) {}
                }
            }
        }
        // ── v3.1.6（用户 9/16 17:11"识别错被当真"根治——照抄 AC3.0 getMoveByBoard）──
        // AC3.0 原版（MyUtils.java:223）：if (i != 2 || ...) return null——
        // 【恰好 2 格变化 + 走子合法，否则 null 绝不猜】。识别错 1 格 = diff=3 =
        // 不匹配 = 丢帧，绝不吸收。旧版第二轮"噪声容忍"（丢≤2子/冒≤3子也匹配）
        // 是识别错误进入内置棋盘的唯一通道（云手机实测评估 +3.35→-7.03 暴跌），
        // 整轮删除。类型抖动（P↔N 同格异值）帧会变成 null → 丢帧 → 下一帧稳定
        // 复现后第一轮精确匹配成功——AC3.0 同款行为，不需要宽容轮。
        return null
    }
}
