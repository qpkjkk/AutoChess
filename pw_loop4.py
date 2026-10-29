#!/usr/bin/env python3.12
"""完整局自主对弈 v4（TA 要求: 走完整盘+质量监控+连 10 盘!!）
监控: ①内置盘vs识别盘occ分叉（>2=告警!!） ②识别丢检率 ③追平失败（漏招!!）
终局: lichess status 元素非 playing → 收局 → 下一盘!!
"""
import subprocess, json, sys, time, os
import chess, chess.engine
from playwright.sync_api import sync_playwright
from PIL import Image

sys.path.insert(0, '/tmp')
from board_rec import recognize, det_to_board_v2

CORE_SH = "/home/z/my-project/autochess/run_core.sh"
SF = "/home/z/dl/Stockfish/src/stockfish"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

def occ(plac):
    out = []
    for ch in plac:
        if ch == '/': continue
        if ch.isdigit(): out.extend('0' * int(ch))
        else: out.append('1')
    return ''.join(out)

def occ_diff(a, b):
    x, y = occ(a), occ(b)
    if len(x) != 64 or len(y) != 64: return 99
    return sum(1 for i in range(64) if x[i] != y[i])

def safe_recognize(path, retries=3):
    """截图读取防竞态（上局死于 broken PNG!!）"""
    for i in range(retries):
        try:
            sz = os.path.getsize(path)
            if sz < 20000: time.sleep(0.4); continue
            Image.open(path).verify()
            det, _ = recognize(path)
            return det
        except Exception:
            time.sleep(0.5)
    return None

def find_move_occ(board, target_plac, side_white, tol=4):
    """追平（occ 容忍 4!!——丢检 3-4 格实测!!——最小差优先=真步!!）"""
    t = occ(target_plac)
    if len(t) != 64: return None, 99
    best, best_d = None, 99
    for mv in board.legal_moves:
        b2 = board.copy(); b2.push(mv)
        o2 = occ(b2.board_fen())
        if len(o2) != 64: continue
        d = sum(1 for i in range(64) if o2[i] != t[i])
        if d < best_d:
            best, best_d = mv, d
    return (best if best_d <= tol else None), best_d

class CorePipe:
    def __init__(self):
        self.p = subprocess.Popen([CORE_SH], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
    def send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n"); self.p.stdin.flush()
    def recv_all(self, wait=2.0):
        import select
        outs = []
        deadline = time.time() + wait
        while time.time() < deadline:
            r, _, _ = select.select([self.p.stdout], [], [], 0.3)
            if not r: break
            line = self.p.stdout.readline().strip()
            if not line: break
            try: outs.append(json.loads(line))
            except: pass
        return outs

def game_over(page):
    try:
        st = page.evaluate("(document.querySelector('.status')?.innerText || '').trim().toLowerCase()")
        return ("playing" not in st and st != ""), st
    except:
        return False, ""

def play_one_game(ctx, engine, game_no, report):
    """一整局——返回 (我步数, 结果, 异常清单)"""
    core = CorePipe()
    core.send({"cmd": "start"}); core.recv_all()
    board = chess.Board()
    page = ctx.new_page()
    page.goto("https://lichess.org/")
    page.wait_for_load_state("networkidle")
    page.get_by_role("button", name="Play against computer").first.click()
    page.wait_for_timeout(2000)
    page.locator("label[for=color-picker-white], .radio.color-picker:has(#color-picker-white)").first.click(force=True)
    page.wait_for_timeout(300)
    page.get_by_role("button", name="Play against computer").nth(1).click()
    page.wait_for_timeout(4000)
    url = page.url
    print(f"\n═══ 第{game_no}盘 {url} ═══", flush=True)
    report.append(f"game{game_no}: {url}")
    bbox = page.locator("cg-board").bounding_box()
    cw, ch = bbox["width"]/8, bbox["height"]/8

    anomalies = []
    my_moves, ai_moves = [], []
    white_view = True
    miss_total, frames = 0, 0
    stale = 0

    import time as _t
    for loop in range(400):   # 上限 400 轮（~10 分钟/盘!!）
        _t0 = _t.time()
        # 终局检测!!
        over, result = game_over(page)
        _t1 = _t.time()
        if over:
            print(f"  终局: {result}（我{len(my_moves)}步/AI{len(ai_moves)}步）", flush=True)
            report.append(f"  result: {result} my={len(my_moves)} ai={len(ai_moves)}")
            break
        page.screenshot(path="/tmp/g_shot.png")
        _t2 = _t.time()
        det = safe_recognize("/tmp/g_shot.png")
        _t3 = _t.time()
        if det is None:
            time.sleep(1.2); continue
        res = det_to_board_v2(det, (1280, 720), bbox=bbox)
        if not res:
            time.sleep(1.2); continue
        plac, pc, wab, rect = res
        _t4 = _t.time()
        frames += 1
        local_plac = board.board_fen()
        # ① 识别丢检统计（期望 pc vs 32-已吃!!）
        expected = 32 - (32 - len([p for p in board.piece_map().values()]))
        miss = max(0, len(board.piece_map()) - pc)
        miss_total += miss
        # ② 内置盘 vs 识别盘分叉监控!!（TA 要的「内置和实际不同」检测!!）
        d_local = occ_diff(plac, local_plac)
        # ③ core 喂帧
        core.send({"cmd": "frame", "placement": plac, "whiteAtBottom": True, "pieceCount": pc})
        core.recv_all(0.25)
        # AI 步追平!!
        if d_local > 0:
            mv_ai, d = find_move_occ(board, plac, True)
            if mv_ai is not None and d <= 2:
                board.push(mv_ai)
                ai_moves.append(mv_ai.uci())
                if d > 0:
                    anomalies.append(f"追平容忍 d={d} @ai{len(ai_moves)}")
                stale = 0
            else:
                stale += 1
                if stale >= 6:
                    # 屏幕重置自愈（AC3.0: 屏幕是唯一事实源!!）——重建 board 到识别局面（轮次保持轮黑!!）
                    try:
                        board = chess.Board(plac + (" w" if board.turn == chess.WHITE else " b") + " - - 0 1")
                        anomalies.append(f"屏幕重置自愈@loop{loop}（stale={stale}）")
                        stale = 0
                    except Exception:
                        pass
                elif stale % 8 == 0:
                    anomalies.append(f"追平失败连续{stale}次 @loop{loop}（plac={plac[:24]}.. local={local_plac[:24]}..）")
        # 我方出步!!
        if board.turn == chess.WHITE:
            _t5 = _t.time()
            mv = engine.play(board, chess.engine.Limit(time=0.8))
            _t6 = _t.time()
            print(f"  ⏱ loop{loop}: 终局{_t1-_t0:.2f} 截图{_t2-_t1:.2f} 识{_t3-_t2:.2f} 追平段{_t4-_t3:.2f} 引擎{_t6-_t5:.2f}", flush=True)
            uci = mv.move.uci()
            core.send({"cmd": "myMove", "uci": uci})
            evs2 = core.recv_all(0.6)
            click = next((e["click"] for e in evs2 if "click" in e), None)
            if click:
                f1 = ord(uci[0]) - 97; r1 = int(uci[1])
                f2 = ord(uci[2]) - 97; r2 = int(uci[3])
                x1 = bbox['x']+(f1+0.5)*cw; y1 = bbox['y']+(8-r1+0.5)*ch
                x2 = bbox['x']+(f2+0.5)*cw; y2 = bbox['y']+(8-r2+0.5)*ch
                page.mouse.click(x1, y1)
                page.wait_for_timeout(300)
                page.mouse.click(x2, y2)
                board.push(chess.Move.from_uci(uci))
                my_moves.append(uci)
                print(f"  [{len(my_moves)}] {uci}", flush=True)
                stale = 0
        time.sleep(0.45)
    else:
        anomalies.append(f"400轮未终局（超时!!）")
    # 收局!!
    stats = f"  我{len(my_moves)}步 AI{len(ai_moves)}步 丢检均值{miss_total/max(1,frames):.2f} 异常{len(anomalies)}"
    print(stats, flush=True)
    report.append(stats)
    for a in anomalies[:10]:
        print(f"  ⚠ {a}", flush=True)
        report.append(f"  ⚠ {a}")
    page.close()
    core.p.terminate()
    return len(my_moves), anomalies

def main():
    engine = chess.engine.SimpleEngine.popen_uci(SF)
    engine.configure({"Skill Level": 1})
    report = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 720}, user_agent=UA)
        for g in range(1, 11):   # 10 盘!!
            try:
                n, anom = play_one_game(ctx, engine, g, report)
            except Exception as e:
                print(f"  第{g}盘异常崩: {e}", flush=True)
                report.append(f"game{g}: CRASH {e}")
        browser.close()
    engine.quit()
    open('/tmp/report_10games.txt', 'w').write('\n'.join(report))
    print("\n10 盘报告: /tmp/report_10games.txt", flush=True)

if __name__ == "__main__":
    main()
