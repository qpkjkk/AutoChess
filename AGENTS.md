# AGENTS.md - AutoChess 项目规则

> 项目级持久指令。写码前先读这个，再动代码。全局纪律见 `/home/z/my-project/CODING_DISCIPLINE.md`。

## ⛔ 强制开发流程（2026-09-25 起生效，TA 指令）

**本项目的任何代码改动，必须走完以下流程，没有例外。跳过任何一步=不许出 APK。**

1. **动工前**：通读 `/home/z/my-project/CODING_DISCIPLINE.md`（全局纪律）+ 本文件 + SOUL.md 铁律
2. **修 bug**（TA 报的任何异常行为）→ 必须走 `systematic-debugging` 技能四阶段：上下文回溯 → 根因调查（先复现实锤）→ 假设验证 → 最小修复+防回归。**没拿到根因铁证前一行代码都不许改**
3. **新功能**（如 TestBridge v3.4.8）→ 必须走 `superpowers` 技能管线：需求澄清 → 写计划文件 → TDD 实现 → code-review 审查 → 收尾
4. **交付前**：diff 必须过 `code-review` 技能七维度清单 + CODING_DISCIPLINE.md 第 4 条对抗性审查 6 问
5. **交付后**：按 CODING_DISCIPLINE.md 第 5 条复盘——"哪条规则缺失让这个错误溜进来"，答案写回规则文件

历史教训背书：v1.x~v3.x 期间 40+ 版血战的根因，几乎全部可以归咎于跳过了上述某一步（猜测式修复/无复现就改/交付前没审查）。这套流程不是仪式，是止血带。

## 构建

- JDK21：`export JAVA_HOME=/home/z/dl/jdk21`（系统 java 是 JRE 无 javac）
- 构建：`cd /home/z/my-project/autochess && export JAVA_HOME=/home/z/dl/jdk21 && /home/z/gradle-8.11.1/bin/gradle assembleRelease`
- 一次构建 8-11 分钟（lintVital 最慢）：**一律 `setsid nohup ... & disown` 后台 + 轮询日志**，前台跑会被网关 504 连坐
- 产物：`app/build/outputs/apk/release/`，签名用 `app/release.keystore`
- 质检：build-tools 34.0.0 的 apksigner/aapt（35 没有可执行文件）
- 更多环境坑见 `/home/z/my-project/TOOLS.md`（磁盘满/daemon崩/缓存损坏排查顺序都在那）

## 架构速览

- 包名 `com.rogic.autochess`，Kotlin + AGP 8.7.3 + Gradle 8.11.1
- **三层架构（不可违背）**：识别层只管 occupancy（哪里有子）；棋子类型走标准答案/本地推演；状态机只在 occupancy 层闭环。类型噪声（k↔K/p↔Q 错读）没有资格进任何判据。
- assets：`book/`（rodent.bin 开局库——**排序数组，二分依赖 keys 有序**）、`models/`（YOLO）、`nnue/`、`pieces/`
- 引擎：Stockfish 外置进程 stdin/stdout 协议，独立进程；杀进程先 EOF 再超时强杀
- 竞品对标源码：`/home/z/my-project/ac30x/`（AC3.0 反编译）——改状态机前先看它的架子（状态门/分支序/复位点）

## 禁区

- **不许绕过引擎直出点击**——每条出手路径必须：来源上屏可追溯 + 合法性守卫（MyBook 快出路径的历史教训）
- assets 下的数据文件（rodent.bin / 模型权重）出怪问题时，**当尸体解剖**（python 逐字节），不围着调用代码转
- 不擅自加生产依赖、不改 gradle 配置、不动签名
- 诊断信息必须上屏（TA 拍照反馈靠它）

## 修改后必须验证

1. 编译过
2. 状态机改动：同步改仿真 + 逐行对照 + 全量仿真跑绿
3. 出手路径改动：随机仿真抓鬼步（60 步起步）
4. 交付前 diff 过对抗性审查（CODING_DISCIPLINE.md 第 4 条）

## 历史

- 40+ 版血战沉淀的九条铁律在 `/home/z/my-project/SOUL.md`——修 bug 前扫一眼对应条目，别重交学费。
