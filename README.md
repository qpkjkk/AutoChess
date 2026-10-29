# AutoChess v3.4.25

Android 自动象棋应用源码 — Kotlin/Java + Native Stockfish + YOLOv8 棋子识别。

## 技术栈

| 模块 | 说明 |
|---|---|
| `app/src/main/java/com/rogic/autochess/` | 主程序，含 `autoplay/AutoPlayService.kt` 自动对局服务 |
| `app/src/main/jniLibs/` | Stockfish 原生库（arm64-v8a / armeabi-v7a / x86_64） |
| `app/src/main/assets/nnue/` | Stockfish NNUE 评估网络 |
| `app/src/main/assets/models/` | YOLOv8 ONNX 棋盘棋子检测模型 |
| `design/` | 棋子图形素材（cburnett / alpha / merida 三套） |
| `docs/` | 文档 |

## ⚠️ 大文件说明

两个超过 GitHub 单文件 100 MB 硬限制的模型文件已**按 45 MB 切分**存放在 `_split/` 目录下。
克隆后执行一次还原脚本即可得到原始文件：

```bash
sh _split/restore.sh
```

### 校验和（SHA256）

还原后应为：

```
c288c895ea924429ea9092e3f36b2b3c1f00f2a3a4c759ff7e57e79e3b43e4a7  app/src/main/assets/nnue/nn-c288c895ea92.nnue
2d5fb17a556b5d6fc501f80f10874d22f52b61ea6d2ad8d2cca0d13a909c2a38  app/src/main/assets/models/chess_yolov8m.onnx
```

### 分片明细

`_split/nnue/nn-c288c895ea92.nnue.part-*` — 合计 103.9 MB
```
3b62b37c07f45360d90727ebb0d368fbeb0d9308a3d994dd905debac2e89656d  part-aa (45 MB)
53cc8486028ad8c164ba16e3cbdd7010d519a71da093bb0cfa9f8b437dc0d879  part-ab (45 MB)
bc1b576062dd8fbbf1c1621f7c2f3f445a595cda1ec7ed9c9655595734267e3f  part-ac (13.9 MB)
```

`_split/models/chess_yolov8m.onnx.part-*` — 合计 98.7 MB
```
4d16c3c3fd1b511f0428535f0e20e05553b50c58a64898b820cb1e713ec70df7  part-aa (45 MB)
0efe5b36164748f81f77f90d7df313d31c923f070e576118446b1dc06f47b7d4  part-ab (45 MB)
45c25677160506ba6d41a4802ee350b67d0026e35d0e42221fc2649d43cf80cb  part-ac (8.7 MB)
```

> 若不希望仓库携带这些大文件，可删除 `_split/` 目录；构建时需自行从上游获取对应模型。

## 构建

需要 Android SDK 34+ 与 JDK 17。

```bash
./gradlew assembleRelease
```

## 版本

`v3.4.25` — 源码快照，2026-09-28。

## 许可

私有仓库，未授权分发。
