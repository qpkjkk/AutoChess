#!/usr/bin/env python3.12
"""完整浏览器闭环 v2（Playwright 桌面 UA——走子已验证生效!!）
截图 → ONNX → core（管道）→ Stockfish → click-click 落子 → 循环
"""
import subprocess, json, sys, time
import chess, chess.engine
from playwright.sync_api import sync_playwright

sys.path.insert(0, '/tmp')
from board_rec import recognize, det_to_board

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

def det_to_board_v2(det, size):
    """识别 → placement + 白底判定 + 棋盘rect!!"""
    if not det: return None
    xs = [d[2] for d in det]; ys = [d[3] for d in det]
    x0, x1 = min(xs), max(xs); y0, y1 = min(ys), max(ys)
    cw = (x1 - x0) / 7.0; ch = (y1 - y0) / 7.0
    if cw < 8 or ch < 8: return None
    grid = [['.' for _ in range(8)] for _ in range(8)]
    best = {}
    for fen, sc, x, y, ww, hh in det:
        c = round((x - x0) / cw); r = round((y - y0) / ch)
        if 0 <= c < 8 and 0 <= r < 8:
            if (r, c) not in best or sc > best[(r, c)][1]:
                best[(r, c)] = (fen, sc)
    for (r, c), (fen, sc) in best.items():
        grid[r][c] = fen
    rows = []
    for r in range(8):
        row = ''; empty = 0
        for c in range(8):
            ch2 = grid[r][c]
            if ch2 == '.': empty += 1
            else:
                if empty: row += str(empty); empty = 0
                row += ch2
        if empty: row += str(empty)
        rows.append(row)
    # 执色: 下半（row4-7）大写多=白底!!
    wBot = sum(1 for r in range(4, 8) for c in range(8) if grid[r][c].isupper())
    bBot = sum(1 for r in range(4, 8) for c in range(8) if grid[r][c].islower())
    white_at_bottom = wBot >= bBot
    # 黑视角 → 翻转成白视角（core 契约!!）
    if not white_at_bottom:
        grid = [row[::-1] for row in grid[::-1]]
        rows = []
        for r in range(8):
            row = ''; empty = 0
            for c in range(8):
                ch2 = grid[r][c]
                if ch2 == '.': empty += 1
                else:
                    if empty: row += str(empty); empty = 0
                    row += ch2
            if empty: row += str(empty)
            rows.append(row)
    pc = sum(1 for r in range(8) for c in range(8) if grid[r][c] != '.')
    return '/'.join(rows), pc, white_at_bottom, (x0, y0, cw, ch)

def main():
    core = CorePipe()
    core.send({"cmd": "start"}); core.recv_all()
    engine = chess.engine.SimpleEngine.popen_uci(SF)
    engine.configure({"Skill Level": 1})
    board = chess.Board()
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1280, "height": 720}, user_agent=UA)
        page = context.new_page()
        page.goto("https://lichess.org/")
        page.wait_for_load_state("networkidle")
        page.get_by_role("button", name="Play against computer").click()
        page.wait_for_timeout(2000)
        page.get_by_role("button", name="Play against computer").nth(1).click()
        page.wait_for_timeout(4000)
        print("对局:", page.url, flush=True)
        # 棋盘 box
        bbox = page.locator("cg-board").bounding_box()
        cw, ch = bbox["width"]/8, bbox["height"]/8
        print(f"棋盘: {bbox}", flush=True)

        def sq_screen(file, rank, white_view):
            # 白视角: rank1=底!! 黑视角: rank1=顶!!
            if white_view:
                return bbox["x"] + (file+0.5)*cw, bbox["y"] + (8-rank+0.5)*ch
            else:
                return bbox["x"] + (7-file+0.5)*cw, bbox["y"] + (rank-0.5)*ch

        def click_uci(uci, white_view):
            f1 = ord(uci[0]) - 97; r1 = int(uci[1])
            f2 = ord(uci[2]) - 97; r2 = int(uci[3])
            x1, y1 = sq_screen(f1, r1, white_view)
            x2, y2 = sq_screen(f2, r2, white_view)
            page.mouse.click(x1, y1)
            page.wait_for_timeout(350)
            page.mouse.click(x2, y2)
            return (int(x1), int(y1), int(x2), int(y2))

        white_view = True   # 由识别帧判定!!
        for loop in range(100):
            page.screenshot(path="/tmp/loop_shot.png")
            det, size = recognize("/tmp/loop_shot.png")
            res = det_to_board_v2(det, size)
            if not res:
                print(f"[{loop}] 识别失败", flush=True); time.sleep(2); continue
            plac, pc, wab, rect = res
            white_view = wab
            core.send({"cmd": "frame", "placement": plac, "whiteAtBottom": True, "pieceCount": pc})
            evs = core.recv_all(1.5)
            # 引擎出步（board 同步由 placement 重建!!）
            try:
                fen = plac + (" w" if True else " b") + " - - 0 1"
                b2 = chess.Board(fen)
                board = b2
            except Exception:
                pass
            if board.turn == chess.WHITE:
                mv = engine.play(board, chess.engine.Limit(time=0.7))
                uci = mv.move.uci()
                core.send({"cmd": "myMove", "uci": uci})
                evs2 = core.recv_all(2.0)
                click = next((e["click"] for e in evs2 if "click" in e), None)
                if click:
                    xyxy = click_uci(uci, white_view)
                    print(f"[{loop}] {uci} click=({click['fx']},{click['fy']}→{click['tx']},{click['ty']}) 屏{xyxy}", flush=True)
                    try: board.push(chess.Move.from_uci(uci))
                    except: pass
            else:
                print(f"[{loop}] 轮黑（AI）·监视…（{plac[:16]}..）", flush=True)
            time.sleep(2.2)
        browser.close()
    engine.quit(); core.p.terminate()

if __name__ == "__main__":
    main()
