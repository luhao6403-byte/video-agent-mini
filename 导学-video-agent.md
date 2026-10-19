# 导学-video-agent：DOVideo-AI 长视频理解 Agent

> 项目简称：video-agent｜求职方向：后端 / Agent 开发
> 本文基于当前仓库源码（server 为 Spring Boot 3.5.9 / Java 21 后端）整理，文档与实现冲突时以实现为准。

## 1. 项目一句话定位

面向长视频内容理解的 Video Agent：用户上传课程、会议或操作录屏并输入分析目标（如“生成复习笔记”“提取操作步骤”“核验视频观点”），系统先把视频整理成带时间戳的语音 + 画面文字证据，再按目标检索相关片段，由受控 Agent 完成计划、执行与校验，输出可回到原视频核验的结构化结论，并支持基于同一视频继续追问。

## 2. 重点亮点

1. 可靠的视频任务链路：分片上传 + 断点续传（Redis 记录已完成分片，MinIO 保存分片并按序合并时计算内容指纹），RocketMQ 把解析移出请求线程，提交即返回（202 Accepted）；Redisson 按“内容指纹 + 目标摘要”加锁与幂等，拦截并发与重复消费。
2. 时序多模态上下文：ASR 与 OCR 双分支在独立有界线程池并行执行；音频按 60 秒切片，关键帧按场景变化抽取并以 30 秒保底采样兜底，感知哈希去重，语音区间、OCR 文本与证据帧时间戳合并为统一结构，单路失败仍保留另一路信息。
3. 有证据约束的 AgentLoop：规划 - 执行 - 校验闭环（Planner 拆任务、Executor 产出结论与时间戳证据、Critic 检查覆盖与证据），不通过时按缺失内容和时间范围定向补充证据后重跑；最多两轮，并有执行时长 / Token / 费用的终止预算。
4. 长视频混合检索：按 5 分钟聚合分段，生成摘要、关键词与向量（BGE-M3），写入 Qdrant；检索时先提取查询意图，再以向量（0.6）+ 关键词（0.4）混合排序取 TopK，命中段再按原始 60 秒片段细化；向量库不可用时回退本地排序，检索不阻断主链路。
5. 可恢复的任务状态机：Checkpoint 以 MySQL 为恢复真源、Redis 为热缓存，区分视频级上下文与目标级计划/校验状态/结果；消费者启动先查 Checkpoint 跳过分阶段，SSE 通过 Redis Pub/Sub 推送阶段事件，前端断线后指数退避重连并通过状态接口恢复终态。

## 3. 学习顺序

建议按“跑通全链路 -> 异步任务骨架 -> 多模态上下文 -> 检索 -> Agent 编排 -> 状态与进度 -> 前端消费”推进，每步先看控制器（入口与参数边界），再看 Service（主逻辑），最后看 Utils（外部命令/API 封装）。

1. 跑通项目：按 README.md 本地运行章节启动中间件、后端与前端；推荐先用 ?demo 模式看 Agent 工作台形态，再走一次真实上传 -> 分析 -> 结果。
2. 后端任务链路：AuthService（会话与限流）-> MediaController + ChunkUploadService（分片续传）-> AnalysisController + AnalysisDispatchService（目标归一、幂等键、投递）-> VideoAnalysisConsumer（消费、锁、阶段推进）。核心问题：一个请求从进来到最后结果，状态存在哪里、谁负责推进。
3. 视频上下文构建：VideoContextService（双分支并行主流程）-> SegmentedTranscriptionService（60 秒切片与 ASR）-> 关键帧抽取与 OcrUtils（场景变化 / 保底采样 / 去重）。核心问题：语音与画面如何变成带时间戳的统一证据。
4. 分块与检索：VideoChunkingService（5 分钟聚合与摘要关键词）-> VideoEvidenceRetrievalService（意图提取与混合排序）-> QdrantVectorStore（写入与查询）-> LongVideoContextService（预算内选段）。核心问题：数小时视频如何只把相关片段喂给模型。
5. Agent 编排：AgentLoopService（主力循环与预算）-> AiService / DeepSeekUtils（结构化输出与重试）-> EvidenceVerificationService（结论证据校验）-> service/mode 包（意图路由与多模式注册）。核心问题：模型如何被约束在证据边界内完成任务。
6. 状态机与进度：AgentCheckpointService + AgentCheckpointRepository（MySQL + Redis 双存储、键设计）-> TaskEventService + TaskEventRedisConfig（Pub/Sub 与 SSE 订阅）。核心问题：任务中断后从哪里恢复、前端如何感知进度。
7. 前端消费：client/src/chunkUpload.js（并发分片）、client/src/taskEvents.js（SSE 重连与状态恢复）、client/src/useAnalysisWorkspace.js（工作台状态机）、client/src/api.js（统一请求与 401 处理）。

## 4. 推荐阅读

| 优先级 | 文件 | 用途 |
| --- | --- | --- |
| P0 | README.md | 项目定位、链路图、本地运行、常见问题 |
| P0 | server/src/main/java/com/example/server/service/AnalysisDispatchService.java | 异步提交：幂等键、限流、MQ 投递、四种提交结果 |
| P0 | server/src/main/java/com/example/server/consumer/VideoAnalysisConsumer.java | 消费入口：拒毒消息、锁、Checkpoint 恢复、阶段推进、死信 |
| P0 | server/src/main/java/com/example/server/service/VideoContextService.java | 双分支并行：ASR/OCR、超时兜底、临时目录清理、证据帧上传 |
| P0 | server/src/main/java/com/example/server/service/AgentLoopService.java | Agent 主力循环：计划/执行/校验、重规划、终止预算 |
| P1 | server/src/main/java/com/example/server/service/ChunkUploadService.java | 分片元数据、MinIO 分片对象、Redis 集合、按序合并与 MD5 |
| P1 | server/src/main/java/com/example/server/service/VideoEvidenceRetrievalService.java | 查询意图、向量 + 关键词混合评分、TopK 与退化路径 |
| P1 | server/src/main/java/com/example/server/service/QdrantVectorStore.java | 集合管理与向量写入 / 过滤查询 |
| P1 | server/src/main/java/com/example/server/service/AgentCheckpointService.java | 视频级 / 目标级 Checkpoint、键设计与模式隔离 |
| P1 | server/src/main/java/com/example/server/service/TaskEventService.java | Redis Pub/Sub -> 本地 SSE 订阅分发、超时清理 |
| P1 | server/src/main/java/com/example/server/service/mode/ModeRouter.java | 自动意图路由与安全回退 |
| P1 | server/src/main/java/com/example/server/service/mode/ModeRegistry.java | 多模式 Profile 注册与启动自检 |
| P2 | server/src/main/java/com/example/server/service/EvidenceVerificationService.java | 结论 -> 时间戳 / 来源 / 引用原文的校验 |
| P2 | server/src/main/java/com/example/server/service/SegmentedTranscriptionService.java | 60 秒音频切片与 ASR 重试 |
| P2 | server/src/main/java/com/example/server/utils/OcrUtils.java | OCR 命令调用与中文模型配置 |
| P2 | server/src/main/java/com/example/server/service/AuthService.java | Redis 会话、PBKDF2、登录失败限流 |
| P2 | server/src/main/resources/db/migration/V1__create_core_tables.sql | 表结构与 Checkpoint 真源 |
| P2 | server/src/main/resources/evaluation/golden-video-tasks.json | 4 条离线评测用例（互补 / 纯语音 / 纯画面 / 音画冲突） |
| P2 | client/src/taskEvents.js | 前端 SSE 重连与终态恢复 |

## 5. 自学提醒

若某文件或原理看不懂，请继续追问 AI；本技能负责给学习路径与题目，不提供逐行讲解。

## 6. 项目技术定位

后端 + AI Agent 交叉项目：主导能力是后端工程化——异步消息、幂等与锁、限流、状态机与双存储、SSE 进度、外部进程编排（FFmpeg/Tesseract）；Agent 部分是工程化的受控工作流（规划/执行/校验、证据约束、预算），不是开放式自由 Agent。求职后端 / Agent 开发时两条线都能展开，但主叙事建议放在“后端工程如何支撑 Agent 可靠落地”。

## 7. 核心原理解析

1. 问题：长视频解析耗时数分钟且依赖外部 AI，放进请求线程会阻塞接口、重复烧钱。机制：提交即投递消息队列（202 Accepted），消费者独立推进；以“内容指纹 + 目标摘要”为幂等键，配合分布式锁与 TTL，同一任务不重复消费。落点：AnalysisDispatchService.submit -> 消费者 VideoAnalysisConsumer 的锁与 Redis 计数投递上限。
2. 问题：语音和画面在数小时视频里要么漏掉关键信息、要么全量处理成本爆炸。机制：60 秒音频切片走 ASR，场景变化检测抽关键帧 + 30 秒保底采样兜底静态板书，感知哈希去重后走 OCR；两分支并行、单路失败降级。落点：VideoContextService.build 的 CountDownLatch(2) + 独立线程池 + 60 分钟总超时。
3. 问题：模型上下文有限，把几小时视频全部塞进 Prompt 不可行且会产生幻觉。机制：5 分钟分块 -> 摘要/关键词/向量；检索先由模型提取查询意图，再做向量 + 关键词混合排序取 TopK，命中段还原为 60 秒原始片段，并按字符预算截断。落点：VideoChunkingService -> VideoEvidenceRetrievalService.rank -> LongVideoContextService.withinBudget（MAX_CONTEXT_CHARS=24000）。
4. 问题：模型可能编造结论或引用不存在的片段。机制：每条结论必须绑定时间戳、证据来源与原文；校验器检查这些字段能否在原始上下文里找到，找不到就反馈并定向检索附近时间段重跑。落点：AgentLoopService 的规划-执行-校验循环 + EvidenceVerificationService。
5. 问题：任务运行数分钟，进程重启或消费失败不能从头再来。机制：Checkpoint 双存储（MySQL 真源 + Redis 7 天热缓存），键区分“视频级上下文”与“目标级计划/校验/结果”；消费者先读 Checkpoint 跳过已完成阶段。落点：AgentCheckpointService / AgentCheckpointRepository。
6. 问题：用户需要实时看到阶段与结果，轮询浪费且不直观。机制：后端向 Redis 通道发布事件，SSE 订阅者通过本地监听器转发；前端断线后指数退避重连，并用状态接口补回终态。落点：TaskEventService + TaskEventRedisConfig + client/src/taskEvents.js。

## 8. 关键设计决策

| 决策 | 备选 | 取舍与风险 | 验证方式 |
| --- | --- | --- | --- |
| 会话用 Redis Token 而非 JWT | JWT 无状态 | 选 Redis：服务端可主动踢人、可限流；代价是多实例需共享 Redis（本项目单机部署，安全） | 删除会话 Key 后旧 Token 立即失效 |
| 分片任务用服务端 uploadId 而非前端 MD5 | 前端计算整个文件 MD5 管理任务 | 服务端权威，避免大文件在弱网下重复计算；分片对象落 MinIO、成功序号落 Redis，合并时统一计算内容指纹 | 中断后查询已完成分片，从断点续传 |
| 分片合并按序读入临时文件计算 MD5 后上传 | MinIO composeObject 原地合并 | 可校验合并产物、实现统一；代价是多一次 IO 与临时磁盘 | 比对合并后与逐分片 MD5 |
| 限流用 Redisson RRateLimiter | 手搓 Redis Hash + Lua 令牌桶 | 组件自带分布式一致性、少自研代码；风险是依赖 Redisson 版本行为 | 压测触发用户级 / 全局 429 |
| 进度用 SSE + Redis Pub/Sub | 前端 3 秒轮询 | 实时且请求量小；多实例部署时事件可跨实例广播，本地再分发 SSE | 断网重连后由状态接口补齐终态 |
| Agent 最多两轮 + 时长/Token/费用预算 | 无限反思提高质量 | 成本与延迟可控，质量牺牲有限；预算耗尽进入独立终态，不再交给 MQ 重试 | Golden 用例对比两轮内是否收敛 |
| 模式体系用枚举 + 注册表扩展 | 每个模式写一套编排 | 新增模式只加枚举值与 Profile，编排完全复用；启动自检保证每个模式都有 Profile，防读写键不对称 | 新增一个模式验证键一致 |
| 证据校验用字段级可查校验 | 完全语义蕴含判断 | 当前可实现、可解释；语义层面的“结论是否真的成立”仍需人工 | 音画冲突用例只保留不确定性 |

## 9. 量化与验证（待测）

- 分片上传：单分片 <= 5 MB、总数 <= 410，元数据 TTL 1 天；建议测：中断后 upload-status 返回已完成分片并续传成功。
- 上下文构建：音频 60 秒切片、关键帧 30 秒保底、总超时 60 分钟；建议测：纯视频无音轨、纯画面无语音、音画冲突三条路径（golden-video-tasks.json 已含 4 条离线用例）。
- 检索降级：关闭 Qdrant 或禁用 Embedding 后，是否回退本地向量 + 关键词排序；建议测：同一目标两种模式结果一致性。
- Agent 预算：轮次上限 2、时长 120 秒、预估 Token 5 万；建议测：Critic 持续不通过时是否按时长/轮次进入终态而不是无限循环。
- 端到端指标：待测——无真实生产流量；延迟、Token 成本、检索命中率、结果准确率均无线上数据，建议先用离线用例建立回归基线，再标注“待生产采集”。

