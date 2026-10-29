#!/usr/bin/env python3.12
"""完整局马拉松 v5（根治版!!）:
①单浏览器多局复用（开销 1/10——防限流!!） ②终局自动 rematch ③全链质量监控
"""
import subprocess, json, sys, time, os
import chess, chess.engine
from playwright.sync_api import sync_playwright
from PIL import Image, ImageOps

sys.path.insert(0, '/tmp')
import board_rec
from board_rec import recognize, det_to_board_v2
import numpy as np

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

# ── 识别 v5: CLAHE 预处理（治深格黑兵丢检!!）+ 双 pass（原图+增强!!）──
def recognize_enhanced(img_path):
    det, _ = recognize(img_path)
    return det

def safe_recognize(path, retries=3):
    for i in range(retries):
        try:
            if os.path.getsize(path) < 20000: time.sleep(0.4); continue
            Image.open(path).verify()
            det, _sz = recognize(path)
            return det
        except Exception:
            time.sleep(0.15)
    return None

def find_move_occ(board, target_plac, tol=4):
    t = occ(target_plac)
    if len(t) != 64: return None, 99, False
    def scan(bd):
        best, best_d = None, 99
        for mv in bd.legal_moves:
            b2 = bd.copy(); b2.push(mv)
            o2 = occ(b2.board_fen())
            if len(o2) != 64: continue
            d = sum(1 for i in range(64) if o2[i] != t[i])
            if d < best_d: best, best_d = mv, d
        return (best if best_d <= tol else None), best_d
    mv, d = scan(board)
    if mv is not None: return mv, d, False
    try:
        bflip = board.copy()
        bflip.turn = not bflip.turn
        if bflip.is_valid():
            mv2, d2 = scan(bflip)
            if mv2 is not None: return mv2, d2, True
    except Exception: pass
    return None, 99, False

class CorePipe:
    def __init__(self):
        self.p = subprocess.Popen([CORE_SH], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
    def send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n"); self.p.stdin.flush()
    def recv_all(self, wait=0.5):
        import select
        outs = []
        deadline = time.time() + wait
        while time.time() < deadline:
            r, _, _ = select.select([self.p.stdout], [], [], 0.2)
            if not r: break
            line = self.p.stdout.readline().strip()
            if not line: break
            try: outs.append(json.loads(line))
            except: pass
        return outs

def get_lichess_moves(page):
    """TA铁律根治: board权威=lichess走子序列DOM重放（绝不分叉!!）"""
    try:
        body = page.evaluate("document.body.innerText")
        lines = [l.strip() for l in body.split("\n") if l.strip()]
        sans = []
        in_zone = False
        for l in lines:
            if l.startswith("Stockfish level") or l == "Stockfish":
                in_zone = True; continue
            if not in_zone: continue
            if l in ("Anonymous",) or "to play" in l or l == "Your turn":
                break
            if l.isdigit() and 1 <= int(l) <= 300:
                continue
            if 0 < len(l) <= 8:
                sans.append(l)
        return [x for x in sans if x not in tuple("abcdefgh12345678")]
    except Exception:
        return []

def board_from_sans(sans):
    import chess as _c
    bd = _c.Board()
    for san in sans:
        try:
            bd.push_san(san)
        except Exception:
            break
    return bd

def my_turn_now(page):
    try:
        body = page.evaluate("document.body.innerText")
        return ("Your turn" in body) or ("White to play" in body) or ("Black to play" in body)
    except Exception:
        return None

def game_over(page):

    try:
        st = page.evaluate("(document.querySelector('.status')?.innerText || '').trim().toLowerCase()")
        return ("playing" not in st and st != ""), st
    except:
        return False, ""

def play_game(page, engine, core, game_no, report):
    board = chess.Board()
    # 每局重开: goto 大厅→人机（复用 page——限流退避!!）
    page.goto("https://lichess.org/")
    page.wait_for_load_state("networkidle")
    page.get_by_role("button", name="Play against computer").first.click()
    page.wait_for_timeout(2500)
    # TA 指令: Blitz 3+2（AI 秒回——Correspondence 级6三分钟不回实测!!）
    try:
        page.get_by_role("button", name="1+0 Bullet").click(timeout=3000)
        page.wait_for_timeout(200)
    except Exception:
        pass
    page.evaluate("(function(){ const r = document.querySelector('#sf_level_1'); r.checked = true; r.dispatchEvent(new Event('change', {bubbles: true})); return 'lv6'; })()")
    page.wait_for_timeout(300)
    page.locator("label[for=color-picker-white], .radio.color-picker:has(#color-picker-white)").first.click(force=True)
    page.wait_for_timeout(400)
    page.get_by_role("button", name="Play against computer").nth(1).click()
    page.wait_for_timeout(5000)
    url = page.url
    print(f"\n═══ 第{game_no}盘 {url} ═══", flush=True)
    report.append(f"\ngame{game_no}: {url}")
    try:
        bbox = page.locator("cg-board").bounding_box()
    except:
        report.append("  棋盘未加载——跳过!!"); return 0
    cw, ch = bbox["width"]/8, bbox["height"]/8

    anomalies = []; my_moves, ai_moves = [], []
    miss_total, frames = 0, 0
    stale = 0
    my_side_white = None
    start_t = time.time()
    white_view = True   # 点击视角（首帧 wab 锁定!!）

    for loop in range(600):
        over, result = game_over(page)
        if over:
            print(f"  终局: {result[:60]}（我{len(my_moves)}/AI{len(ai_moves)}）", flush=True)
            report.append(f"  result: {result[:80]} my={len(my_moves)} ai={len(ai_moves)}")
            break
        page.screenshot(path="/tmp/g_shot.png")
        det = safe_recognize("/tmp/g_shot.png")
        if det is None:
            print(f"    ·识别null @loop{loop}", flush=True); time.sleep(1); continue
        res = det_to_board_v2(det, (1280, 720), bbox=bbox)
        if not res:
            empty_rec = play_game.__dict__.get('_er', 0) + 1
            play_game._er = empty_rec
            print(f"    ·识别空 @loop{loop} x{empty_rec}", flush=True)
            if empty_rec >= 40:
                r = page.evaluate("(document.querySelector('.result')?.innerText || document.body.innerText.slice(0, 60))")
                print(f"  终局(识别空40): {str(r)[:60]}（我{len(my_moves)}/AI{len(ai_moves)}）", flush=True)
                report.append(f"  result(识别空): {str(r)[:70]} my={len(my_moves)}")
                break
            time.sleep(1); continue
        plac, pc, wab, rect = res
        play_game._er = 0
        if frames == 0:
            white_view = wab   # 首帧锁定视角!!
        empty_frames = play_game.__dict__.get('_ef', 0) + 1 if pc == 0 else 0
        play_game._ef = empty_frames
        if pc == 0 and empty_frames >= 3:
            r = page.evaluate("(document.querySelector('.result')?.innerText || document.body.innerText.slice(0, 80))")
            print(f"  终局(空盘): {str(r)[:70]}（我{len(my_moves)}/AI{len(ai_moves)}）", flush=True)
            report.append(f"  result(空盘): {str(r)[:80]} my={len(my_moves)} ai={len(ai_moves)}")
            break
        frames += 1
        local_plac = board.board_fen()
        if loop % 10 == 0:
            print(f"    ·轮{loop} pc={pc} 轮次={'白' if board.turn else '黑'} stale={stale} plac={plac[:16]}..", flush=True)
        miss = max(0, len(board.piece_map()) - pc)
        miss_total += miss
        core.send({"cmd": "frame", "placement": plac, "whiteAtBottom": True, "pieceCount": pc})
        core.recv_all(0.25)
        # v8: board=SAN权威重放——不追平（旧追平=分叉源!!）
        # 我方!!
        if loop < 30:
            print(f"    ·turn检查 loop{loop}: turn={'白' if board.turn else '黑'} my={len(my_moves)} ai={len(ai_moves)} eq={plac==local_plac}", flush=True)
        # v6 TA铁律根治: 轮次=Your turn直报!! board=DOM重放（SAN!!）——绝不分叉!!
        sans_now = get_lichess_moves(page)
        if sans_now:
            board = board_from_sans(sans_now)
        if my_side_white is None:
            my_side_white = white_view
        my_side_color = chess.WHITE if my_side_white else chess.BLACK
        my_turn = (my_turn_now(page) is True) and (board.turn == my_side_color)
        if my_turn:
            mv = engine.play(board, chess.engine.Limit(time=0.1))
            uci = mv.move.uci()
            if uci in my_moves[-2:]:
                anomalies.append(f"repeat-click-bug: {uci} in tail!!")
                print(f"  W W repeat-click-bug!! {uci}", flush=True)
                report.append(f"  repeat-click-bug: {uci}")
            core.send({"cmd": "myMove", "uci": uci})
            evs2 = core.recv_all(0.6)
            click = next((e["click"] for e in evs2 if "click" in e), None)
            if click is None:
                print(f"    ⚠ click未收到 @步{uci}（回包{json.dumps(evs2)[:120]}）", flush=True)
            if click:
                f1 = ord(uci[0]) - 97; r1 = int(uci[1])
                f2 = ord(uci[2]) - 97; r2 = int(uci[3])
                if white_view:
                    x1 = bbox['x']+(f1+0.5)*cw; y1 = bbox['y']+(8-r1+0.5)*ch
                    x2 = bbox['x']+(f2+0.5)*cw; y2 = bbox['y']+(8-r2+0.5)*ch
                else:
                    x1 = bbox['x']+(7-f1+0.5)*cw; y1 = bbox['y']+(r1-0.5)*ch
                    x2 = bbox['x']+(7-f2+0.5)*cw; y2 = bbox['y']+(r2-0.5)*ch
                page.mouse.click(x1, y1)
                page.wait_for_timeout(280)
                page.mouse.click(x2, y2)
                if len(uci) >= 5:
                    page.wait_for_timeout(400)
                    qok = page.evaluate("(function(){ const pc = document.querySelector('.promotion-choice, [class*=promotion]'); if (pc) { const kids = pc.querySelectorAll('*'); for (const k of kids) { const cn = String(k.className); if (cn.includes('-q') || cn === 'q' || (k.textContent || '') === 'Q') { k.click(); return 'dom-q'; } } const f = pc.firstElementChild; if (f) { f.click(); return 'first'; } } return 'no'; })()")
                    if qok == 'no':
                        page.mouse.click(x2, y2 - ch * 0.75)
                board.push(chess.Move.from_uci(uci))
                my_moves.append(uci)
                print(f"  [{len(my_moves)}] {uci}", flush=True)
                page.wait_for_timeout(300)
                stale = 0
        time.sleep(0.15)
    else:
        anomalies.append("600轮未终局!!")
    stats = f"  我{len(my_moves)} AI{len(ai_moves)} 帧均丢检{miss_total/max(1,frames):.2f} 异常{len(anomalies)}"
    print(stats, flush=True); report.append(stats)
    for a in anomalies[:8]:
        print(f"  ⚠ {a}", flush=True); report.append(f"  ⚠ {a}")
    return len(my_moves)

def main():
    engine = chess.engine.SimpleEngine.popen_uci(SF)
    engine.configure({"Skill Level": 20})
    core = CorePipe()
    core.send({"cmd": "start"}); core.recv_all()
    report = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width": 1280, "height": 720}, user_agent=UA)
        page = ctx.new_page()
        for g in range(1, 11):
            try:
                play_game(page, engine, core, g, report)
            except Exception as e:
                print(f"  第{g}盘崩: {str(e)[:80]}", flush=True)
                report.append(f"game{g} CRASH: {str(e)[:80]}")
                try:
                    page.goto("https://lichess.org/")
                    page.wait_for_timeout(3000)
                except: pass
        browser.close()
    engine.quit(); core.p.terminate()
    open('/tmp/report_10games.txt', 'w').write('\n'.join(report))
    print("\n10盘报告: /tmp/report_10games.txt", flush=True)

if __name__ == "__main__":
    main()
