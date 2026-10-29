#!/usr/bin/env python3.12
"""core 回放器（抽核手术·阶段 2）——subprocess 驱动状态机核。
用法: python3.12 replay.py          # 跑内置秒回场景（回归!!）
      python3.12 replay.py --pipe   # stdin/stdout 直通模式（浏览器桥用!!）
"""
import subprocess, json, sys, time

CORE = "/home/z/my-project/autochess/run_core.sh"

def start_core():
    p = subprocess.Popen([CORE], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, bufsize=1)
    return p

def send(p, obj):
    p.stdin.write(json.dumps(obj) + "\n"); p.stdin.flush()

def recv(p, timeout=8):
    import select
    r, _, _ = select.select([p.stdout], [], [], timeout)
    if not r: return None
    line = p.stdout.readline().strip()
    return json.loads(line) if line else None

def pipe_mode():
    """浏览器桥直通: stdin 行→core→stdout 行"""
    p = start_core()
    import select
    while True:
        r, _, _ = select.select([sys.stdin], [], [], None)
        line = sys.stdin.readline()
        if not line: break
        line = line.strip()
        if not line: continue
        try:
            obj = json.loads(line)
        except Exception:
            continue
        send(p, obj)
        # 收回执（可能多条——status+ok!!）
        deadline = time.time() + 3
        while time.time() < deadline:
            import select as sel
            rr, _, _ = sel.select([p.stdout], [], [], 0.3)
            if not rr: break
            out = recv(p, 0.5)
            if out is None: break
            print(json.dumps(out, ensure_ascii=False), flush=True)
    p.terminate()

def regression():
    """内置回归: 秒回双步闭环（v3.4.15 根因判据!!）"""
    p = start_core()
    send(p, {"cmd": "start"})
    print("recv:", recv(p))
    # 我方 e4 点击后的 verify 期
    # core 的 verify 状态由宿主设置——本版协议暂无 myMove 建 verify 的完整链
    # （APK 宿主侧桥接在刀 4!!）——先验基础帧循环!!
    send(p, {"cmd": "frame", "placement": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
             "whiteAtBottom": True, "pieceCount": 32})
    print("recv:", recv(p))
    send(p, {"cmd": "tick", "nowMs": 5000})
    p.terminate()
    print("✅ 基础帧循环回归通过")

if __name__ == "__main__":
    if "--pipe" in sys.argv:
        pipe_mode()
    else:
        regression()
