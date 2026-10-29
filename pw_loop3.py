#!/usr/bin/env python3.12
"""浏览器闭环 v3（轮次权威版!!）:
python-chess 维护棋盘（我步 push + AI 步识别追平 push!!）——
core 管状态机语义（verify/门/守卫!!）——Stockfish 出步——Playwright 点击!!
"""
import subprocess, json, sys, time
import chess, chess.engine
from playwright.sync_api import sync_playwright

sys.path.insert(0, '/tmp')
from board_rec import recognize, det_to_board_v2

CORE_SH = "/home/z/my-project/autochess/run_core.sh"
SF = "/home/z/dl/Stockfish/src/stockfish"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

class CorePipe:
    def __init__(self):
        self.p = subprocess.Popen([CORE_SH], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
    def send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n"); self.p.stdin.flush()
    def recv_all(self, wait=2.5):
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

def occ(plac):
    out = []
    for ch in plac:
        if ch == '/': continue
        if ch.isdigit(): out.extend('0' * int(ch))
        else: out.append('1')
    return ''.join(out)

def find_ai_move(board, target_plac):
    """AI 步追平（occupancy 层!!——丢检容忍2格取差最小!!——深格黑兵丢检实测!!）"""
    t = occ(target_plac)
    if len(t) != 64: return None
    best = None; best_d = 99
    for mv in board.legal_moves:
        b2 = board.copy(); b2.push(mv)
        o2 = occ(b2.board_fen())
        if len(o2) != 64: continue
        d = sum(1 for i in range(64) if o2[i] != t[i])
        if d <= 2 and d < best_d:
            best = mv; best_d = d
    return best

def main():
    core = CorePipe()
    core.send({"cmd": "start"}); core.recv_all()
    engine = chess.engine.SimpleEngine.popen_uci(SF)
    engine.configure({"Skill Level": 1})
    board = chess.Board()
    moves_played = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1280, "height": 720}, user_agent=UA)
        page = context.new_page()
        page.goto("https://lichess.org/")
        page.wait_for_load_state("networkidle")
        page.get_by_role("button", name="Play against computer").first.click()
        page.wait_for_timeout(2000)
        # 强制执白（v3.3: 固定白视角——消灭执色变量!!）
        page.locator("label[for=color-picker-white], .radio.color-picker:has(#color-picker-white)").first.click(force=True)
        page.wait_for_timeout(300)
        page.get_by_role("button", name="Play against computer").nth(1).click()
        page.wait_for_timeout(4000)
        print("对局:", page.url, flush=True)
        bbox = page.locator("cg-board").bounding_box()
        cw, ch = bbox["width"]/8, bbox["height"]/8

        white_view = None
        stale = 0
        for loop in range(150):
            page.screenshot(path="/tmp/loop_shot.png")
            det, _ = recognize("/tmp/loop_shot.png")
            res = det_to_board_v2(det, (1280, 720))
            if not res:
                print(f"[{loop}] 识别失败", flush=True); time.sleep(1.5); continue
            plac, pc, wab, rect = res
            # 首帧定执色!!
            if white_view is None:
                white_view = wab
                # 执黑: AI 白先走——board 不变等 AI!!
                if not white_view:
                    print("我执黑——等 AI 白先走…", flush=True)
            # 喂 core（识别帧——core 的状态机消化!!）
            core.send({"cmd": "frame", "placement": plac, "whiteAtBottom": True, "pieceCount": pc})
            evs = core.recv_all(1.2)
            local_plac = board.board_fen()
            # AI 步追平（board.turn 即 AI 轮!!）
            if plac != local_plac:
                mv_ai = find_ai_move(board, plac)
                if mv_ai:
                    board.push(mv_ai)
                    print(f"[{loop}] AI走 {mv_ai.uci()}（总{len(moves_played)+1}步）", flush=True)
                    stale = 0
                else:
                    stale += 1
            # 我方回合（执白=轮白 / 执黑=轮黑!!）
            if (board.turn == chess.WHITE) == white_view:
                mv = engine.play(board, chess.engine.Limit(time=0.7))
                uci = mv.move.uci()
                # core 建 verify+出 click
                core.send({"cmd": "myMove", "uci": uci})
                evs2 = core.recv_all(2.0)
                click = next((e["click"] for e in evs2 if "click" in e), None)
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
                    page.wait_for_timeout(320)
                    page.mouse.click(x2, y2)
                    board.push(chess.Move.from_uci(uci))
                    moves_played.append(uci)
                    print(f"[{loop}] 我走 {uci}（第{len(moves_played)}步）", flush=True)
                    stale = 0
            else:
                if stale > 12:
                    print(f"[{loop}] AI 迟迟未回应…", flush=True)
                    stale = 0
            time.sleep(1.6)
        browser.close()
    engine.quit(); core.p.terminate()
    print(f"对局结束: {len(moves_played)} 我方步: {' '.join(moves_played[:40])}", flush=True)

if __name__ == "__main__":
    main()
