#!/usr/bin/env python3.12
"""完整闭环（抽核手术·阶段 3!!）:
agent-browser 截图 → ONNX 识别 → core 状态机 → Stockfish 引擎 → 浏览器点击落子 → 循环
"""
import subprocess, json, sys, time, os
import chess, chess.engine

sys.path.insert(0, '/tmp')
from board_rec import recognize, det_to_board

CORE_SH = "/home/z/my-project/autochess/run_core.sh"
SF = "/home/z/dl/Stockfish/src/stockfish"
TMP = "/tmp/loop_shot.png"

def ab(*args):
    return subprocess.run(["agent-browser"] + list(args), capture_output=True, text=True, timeout=60).stdout

def ab_mouse_click(x, y):
    subprocess.run(["agent-browser", "mouse", "move", str(int(x)), str(int(y))], capture_output=True, timeout=30)
    subprocess.run(["agent-browser", "mouse", "down", "left"], capture_output=True, timeout=30)
    time.sleep(0.06)
    subprocess.run(["agent-browser", "mouse", "up", "left"], capture_output=True, timeout=30)

class CorePipe:
    def __init__(self):
        self.p = subprocess.Popen([CORE_SH], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
    def send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n"); self.p.stdin.flush()
    def recv_all(self, wait=3.0):
        import select
        outs = []
        deadline = time.time() + wait
        while time.time() < deadline:
            r, _, _ = select.select([self.p.stdout], [], [], 0.4)
            if not r: break
            line = self.p.stdout.readline().strip()
            if not line: break
            try: outs.append(json.loads(line))
            except: pass
        return outs

def board_rect_from_det(det):
    """检出包围盒 → 棋盘 rect+cell 尺寸"""
    if not det: return None
    xs = [d[2] for d in det]; ys = [d[3] for d in det]
    x0, x1 = min(xs), max(xs); y0, y1 = min(ys), max(ys)
    cw = (x1 - x0) / 7.0; ch = (y1 - y0) / 7.0
    if cw < 8 or ch < 8: return None
    return (x0, y0, cw, ch)

def main():
    core = CorePipe()
    core.send({"cmd": "start"}); core.recv_all()
    engine = chess.engine.SimpleEngine.popen_uci(SF)
    engine.configure({"Skill Level": 1})
    board = chess.Board()
    print("闭环启动: core+SF18 就绪", flush=True)

    for loop in range(120):
        # 1. 截图+识别
        subprocess.run(["agent-browser", "screenshot", TMP], capture_output=True, timeout=60)
        det, size = recognize(TMP)
        res = det_to_board(det, size)
        if not res:
            print(f"[{loop}] 识别失败（{len(det)} 检出）", flush=True)
            time.sleep(2); continue
        plac, pc = res
        # 2. 喂 core（识别帧!!）
        core.send({"cmd": "frame", "placement": plac, "whiteAtBottom": True, "pieceCount": pc})
        evs = core.recv_all(2.0)
        # 3. 引擎出步（轮我方时!!）
        if board.turn == chess.WHITE:
            mv = engine.play(board, chess.engine.Limit(time=0.6))
            uci = mv.move.uci()
            print(f"[{loop}] 引擎出 {uci}（局面 {plac[:20]}..）", flush=True)
            # 4. core 建 verify+出 click
            core.send({"cmd": "myMove", "uci": uci})
            evs2 = core.recv_all(2.5)
            print(f"    core回包: {json.dumps(evs2, ensure_ascii=False)[:200]}", flush=True)
            click = next((e["click"] for e in evs2 if "click" in e), None)
            if click:
                fx, fy, tx, ty = click["fx"], click["fy"], click["tx"], click["ty"]
                # 5. click 格坐标 → 棋盘 rect 屏幕坐标 → 浏览器点击
                rect = board_rect_from_det(det)
                if rect is None:
                    print(f"    ⚠ rect失败: det数={len(det)} xs范围={[round(d[2]) for d in det[:4]]}", flush=True)
                if rect:
                    x0, y0, cw, ch = rect
                    ax = x0 + fy * cw; ay = y0 + fx * ch   # 注意: fx=行 fy=列!!
                    bx = x0 + ty * cw; by = y0 + tx * ch
                    ab_mouse_click(ax, ay)
                    time.sleep(0.15)
                    ab_mouse_click(bx, by)
                    print(f"    点击 ({fx},{fy})→({tx},{ty}) 屏({int(ax)},{int(ay)})→({int(bx)},{int(by)})", flush=True)
                    board.push(chess.Move.from_uci(uci))
        else:
            # 轮黑（Stockfish!!）——等浏览器上的 lichess SF 走（识别帧消化对方步!!）
            pass
        # 6. 从识别帧同步 python board（对方步!!——core 的 syncBoard 已消化!!——python 侧用 placement 对齐!!
        try:
            fen2 = plac + " w - - 0 1"
            b2 = chess.Board(fen2)
        except: pass
        time.sleep(2.5)
    engine.quit(); core.p.terminate()

if __name__ == "__main__":
    main()
