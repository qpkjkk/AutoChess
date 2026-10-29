#!/bin/sh
# 还原被拆分的大文件到原始位置
# 用法：在仓库根目录执行 sh _split/restore.sh
set -e
cd "$(dirname "$0")"

echo "[1/2] 还原 Stockfish NNUE 网络..."
cat nnue/nn-c288c895ea92.nnue.part-* > ../app/src/main/assets/nnue/nn-c288c895ea92.nnue

echo "[2/2] 还原 YOLOv8 棋子识别模型..."
cat models/chess_yolov8m.onnx.part-* > ../app/src/main/assets/models/chess_yolov8m.onnx

echo
echo "完成。校验 SHA256（应与下方值一致）："
sha256sum ../app/src/main/assets/nnue/nn-c288c895ea92.nnue
sha256sum ../app/src/main/assets/models/chess_yolov8m.onnx
