#!/usr/bin/env python3
"""棋子PNG白底转透明：边缘flood-fill + 边缘羽化"""
from PIL import Image, ImageFilter
import os, sys
from collections import deque

def make_transparent(path):
    img = Image.open(path).convert('RGBA')
    w, h = img.size
    px = img.load()
    # 近白判定
    def near_white(r, g, b):
        return r > 238 and g > 238 and b > 238
    # 从四边flood-fill标记背景
    bg = [[False]*w for _ in range(h)]
    q = deque()
    for x in range(w):
        for y in (0, h-1):
            if not bg[y][x] and near_white(*px[x, y][:3]):
                bg[y][x] = True; q.append((x, y))
    for y in range(h):
        for x in (0, w-1):
            if not bg[y][x] and near_white(*px[x, y][:3]):
                bg[y][x] = True; q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)):
            nx, ny = x+dx, y+dy
            if 0 <= nx < w and 0 <= ny < h and not bg[ny][nx] and near_white(*px[nx, ny][:3]):
                bg[ny][nx] = True; q.append((nx, ny))
    # 生成alpha
    alpha = Image.new('L', (w, h), 255)
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            if bg[y][x]:
                ap[x, y] = 0
    # 边缘羽化：对alpha做轻微模糊后再与原值取小，只影响边界
    blurred = alpha.filter(ImageFilter.GaussianBlur(1.2))
    bp = blurred.load()
    for y in range(1, h-1):
        for x in range(1, w-1):
            a = ap[x, y]
            if a == 255:
                # 若邻居有透明，取模糊值（半透明过渡）
                if ap[x-1, y] == 0 or ap[x+1, y] == 0 or ap[x, y-1] == 0 or ap[x, y+1] == 0:
                    ap[x, y] = max(0, min(255, int(bp[x, y] * 0.85)))
    img.putalpha(alpha)
    img.save(path)
    return True

base = '/home/z/my-project/autochess/design/pieces'
total, fail = 0, []
for s in ['cburnett', 'merida', 'alpha']:
    d = os.path.join(base, s, 'png')
    for p in ['wK','wQ','wR','wB','wN','wP','bK','bQ','bR','bB','bN','bP']:
        f = os.path.join(d, p + '.png')
        try:
            make_transparent(f)
            total += 1
        except Exception as e:
            fail.append(f'{s}/{p}: {e}')
print(f'transparent OK: {total}/36')
for f in fail: print('FAIL', f)
