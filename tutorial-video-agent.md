# 源码课程大纲：DOVideo-AI 长视频理解 Agent（tutorial-video-agent）

## 文件头

- **项目用途**：面向长视频内容理解的 Video Agent。用户上传课程 / 会议 / 操作录屏，输入分析目标，系统通过分片异步上传、多模态时序证据提取、混合检索与规划-执行-校验的 Agent 闭环，产出带时间戳证据的结构化结论，并支持基于同一视频继续追问。
- **本次学习目标**：沿着真实调用链读通"上传 -> 提交 -> 消费 -> 上下文 -> 检索 -> Agent -> 校验 -> 状态 -> 进度 -> 追问"全过程，每一课能独立回答一个项目问题。
- **源码版本**：当前 HEAD `94fd7d7`（2026-10-10，GitButler Workspace Commit），分支 `gitbutler/workspace`。
- **影响结论的本地改动**：工作区相对 HEAD 仅新增 `导学-video-agent.md`、`面经-video-agent.md`（文档，不影响源码结论）以及 `study/` 下的历史学习记录删除；后端源码无改动，课程以当前工作区代码为准。
- **前置基础**：Java 21 / Spring Boot 基本概念、HTTP 与 SSE、Redis 键值语义、RocketMQ 消费模型、MySQL 表结构常识、LLM API 与结构化输出的基本认知；建议先按 `README.md` 本地运行章节把项目跑起来，再按本大纲阅读。
- **本次覆盖范围**：认证 -> 分片上传 -> 异步提交与幂等 -> 消息消费与阶段推进 -> 多模态上下文构建 -> 分块与向量化 -> 证据检索 -> Agent 编排 -> 证据校验 -> 模式体系 -> Checkpoint 断点恢复 -> SSE 进度 -> 追问与成本护栏 -> 离线评测。
- **尚未覆盖的模块**：前端 UI 组件与样式（`client/src/App.vue`、`style.css`）、管理接口（`AdminController`）、媒体处理控制器（`MediaProcessingController`）、列表 / 删除 / 播放接口细节（`MediaService`）、在线视频链接解析（`utils/YtDlpUtils.java`）、中间件编排与部署脚本（`docker-compose.yml`、`scripts/dev-up.sh`）、Demo 模式（`client/src/demoData.js`）。这些作为课后延伸，不在本大纲主链中。

## 课程目录

| 课 | 标题 | 读完能回答的问题 |
| --- | --- | --- |
| 1 | 全链路总览 | 一次"上传视频 -> 输入目标 -> 拿到结果"端到端经历了什么？ |
| 2 | 认证与会话 | 一次受保护请求如何被鉴权，为什么选 Redis 会话而非 JWT？ |
| 3 | 分片上传与断点续传 | 大文件上传如何从断点恢复，分片状态存在哪里？ |
| 4 | 异步提交与幂等防重 | 分析请求如何变成消息，怎么保证不重复提交？ |
| 5 | 消息消费与任务阶段推进 | 消费者如何从接消息推进到终态，如何防重复消费与毒消息？ |
| 6 | 多模态上下文构建 | 语音与画面如何变成带时间戳的统一证据？ |
| 7 | 长视频分块与向量化 | 上下文如何按 5 分钟分块并写入向量库？ |
| 8 | 证据检索与上下文预算 | 几小时视频如何只把相关片段喂给模型？ |
| 9 | AgentLoop 规划-执行-校验闭环 | 受控 Agent 如何从目标走到带证据的结果？ |
| 10 | 证据校验与定向补证 | 每条结论如何验证"有据可查"，反馈如何改变下一轮输入？ |
| 11 | 模式路由与多模式产物 | 系统如何按目标自动选模式，新模式如何扩展？ |
| 12 | Checkpoint 与断点恢复 | 任务中断后如何跳过已完成阶段恢复？ |
| 13 | SSE 进度推送与前端恢复 | 前端如何实时看到阶段，断线后如何恢复？ |
| 14 | 追问、成本护栏与离线评测 | 继续追问如何复用证据，成本与失败边界有哪些护栏，质量如何离线验证？ |

---

## 课 1：全链路总览

读完能回答：用户上传一个视频并输入分析目标，端到端发生了什么？

主链：`前端工作台(useAnalysisWorkspace.js + chunkUpload.js) -> 分片上传接口(MediaController /init-upload,/upload-chunk,/complete-upload) -> 提交 POST /analysis/ai(AnalysisController.aiAnalyze) -> AnalysisDispatchService.submit(activeKey 幂等 + 限流) -> RocketMQ(video-analysis-topic) -> VideoAnalysisConsumer.onMessage(分布式锁 + completedKey 复用 + 毒消息拦截) -> AiService.asyncAnalyze(目标级 Checkpoint 恢复) -> VideoContextService.build(ASR/OCR 双分支) -> LongVideoContextService.selectRelevant(VideoChunkingService 分块 -> VideoEvidenceRetrievalService 混合检索) -> AgentLoopService.run(Planner/Executor/Critic 闭环) -> TaskEventService.publishAnalysis(Redis 通道) -> SSE(/analysis-events) -> 前端展示结论、证据与追问入口`

### 1. 场景与目标

**触发**：用户在 Vue 工作台选中一个已上传视频，输入分析目标（goal），选择模式（GENERAL/LEARNING/REVIEW/CREATION，或 AUTO 由 `/analysis/route` 先判出具体模式），点击"开始分析"。整个链路从这一次点击，推进到工作台展示带时间戳证据的结构化结论。

**学完本课你能解释**：
- 一次分析从"用户点击"到"结果展示"，依次经过前端、REST API、RocketMQ、消费者、多模态上下文、向量检索、AgentLoop、SSE，各自承担什么职责；
- 为什么提交不是同步等待，而是"202 已受理 + SSE 逐阶段推送"；
- 同一视频或同一目标重复提交会发生什么，结果与中间产物如何被复用；
- 请求返回之后，后台任务按"上下文构建 -> 分块检索 -> Agent 闭环 -> 事件推送"的顺序推进。

**最少前置**：HTTP 状态码与 SSE 语义、Spring Boot `@RestController`/`@RequestParam`、RocketMQ 生产者/消费者概念、Redis 键值语义与 TTL、LLM 结构化 JSON 输出。本课不需要读懂任何一层内部实现，重点是行程顺序与异步边界。

### 2. 主链与阅读顺序

整条链路按"请求线程提交 -> 消息投递（第一条异步边界）-> 消费编排 -> LLM 分析 -> 事件回传（SSE）"推进。推荐按编号阅读，每步记下输入、下一步与输出：

1. `README.md` 的"核心功能"四层能力与系统流程 Mermaid 图——输入：无；下一步：2；输出：全貌。Mermaid 图是本课主链的可视版本。
2. `client/src/useAnalysisWorkspace.js` 的 `analyze()` / `startTaskStream()`，`client/src/chunkUpload.js` 的分片上传——输入：用户选视频与目标；下一步：3；输出：mediaId、`POST /analysis/ai`、SSE 订阅。
3. `server/src/main/java/com/example/server/controller/AnalysisController.java` 的 `aiAnalyze`——输入：id、goal、mode（归一后）；下一步：4；输出：200（有可复用结果）/ 202（已受理）/ 409（重复）/ 429（限流）/ 500（投递失败）。
4. `server/src/main/java/com/example/server/service/AnalysisDispatchService.java` 的 `submit`——输入：mediaFile、归一化 goal、mode；下一步：5；输出：写幂等 activeKey、扣限流配额、投递 `AnalysisTaskMsg` 到 RocketMQ。
5. `server/src/main/java/com/example/server/consumer/VideoAnalysisConsumer.java` 的 `onMessage`——输入：AnalysisTaskMsg；下一步：6；输出：毒消息拦截、内容级锁、completedKey 复用、调用 `aiService.asyncAnalyze`。
6. `server/src/main/java/com/example/server/service/AiService.java` 的 `asyncAnalyze`——输入：mediaId、goal、mode；下一步：7；输出：目标级 Checkpoint 恢复、上下文构建、Agent 结果落库。
7. `server/src/main/java/com/example/server/service/VideoContextService.java` 的 `build`——输入：videoPath、goal；下一步：8；输出：ASR/OCR 双分支合并的 `VideoContext`。
8. `server/src/main/java/com/example/server/service/LongVideoContextService.java` 的 `selectRelevant`（内部走 `VideoChunkingService.build` 与 `VideoEvidenceRetrievalService.retrieve`）——输入：VideoContext；下一步：9；输出：5 分钟分块 + 混合检索后的相关片段。
9. `server/src/main/java/com/example/server/service/AgentLoopService.java` 的 `run`——输入：相关上下文 + ModeProfile；下一步：10；输出：校验通过的 `AgentState`（Plan/Result/Critique）。
10. `server/src/main/java/com/example/server/service/TaskEventService.java` 的 `subscribe` / `publishAnalysis`，配合 `AnalysisController` 的 `/analysis-events`——输入：mediaId、goal、mode；输出：SSE 事件流与终态结果。

选读证据：`README.md` 的"本地运行"章节（跑通后可端到端实测）；`docs/images/` 工作台截图（前端展示形态）；`server/src/test/java/com/example/server/service/` 下 AgentCheckpoint、AgentExecutionBudget 等测试（测试覆盖的行为证据更可信）。

暂缓阅读：分片上传与断点续传细节（课 3）、提交幂等与限流细节（课 4）、消费幂等/重试/死信（课 5）、ASR/OCR 实现（课 6）、向量化与降级（课 7-8）、Critic 校验与定向补证（课 10）、模式路由（课 11）、Checkpoint 双写（课 12）、SSE 重连（课 13）。本课只交代顺序和职责。

### 3. 沿流程带读

#### 3.1 前端：上传与提交入口

`client/src/chunkUpload.js`：前端按 5 MB 分片（`CHUNK_SIZE`），先 `POST /media/init-upload?filename&totalChunks` 拿 uploadId，再以并发 3 路上传 `/media/upload-chunk`（`UPLOAD_CONCURRENCY = 3`），分片失败按指数退避重试（基址 800 ms、上限 8 s），全部完成后 `POST /media/complete-upload` 拿到 `MediaSummary`（含 mediaId）。后端入口是 `MediaController`，每个接口都带 `@RequestAttribute(AuthService.REQUEST_USER_ID)` 做归属校验（课 2 展开）。上传与分析是两段独立请求，课 3 展开断点续传；本课只把上传当作 mediaId 的来源。

拿到 mediaId 后，`useAnalysisWorkspace.js` 的 `analyze(id, goal, mode)`：
- 若同一（id, goal, mode）已有 SSE 订阅，直接"接管进度"，不重复提交；
- 否则 `POST /analysis/ai?id=&goal=&mode=`：409 说明后端已有在途任务 -> 直接开启事件流接管；请求失败 -> 回 compose 态展示错误；成功 -> `startTaskStream` 订阅 SSE。

#### 3.2 提交接口与受理判定

`AnalysisController.aiAnalyze` 做四件事：
1. `normalizeText(goal)`：trim 归一 + 字数上限 500——归一后文本参与幂等 key 计算，不能省；
2. `AnalysisMode.fromRequest(mode)`：空串或未知按 GENERAL 兜底；
3. `mediaService.requireOwnedMedia(id, userId)`：先确权，消费端还会再查一次 `mediaService.exists`；
4. 查 `checkpointService.loadResult(id, goal, mode)`：已有可复用结果直接 `200 OK`（"已完成"而非"已受理"）；否则 `dispatchService.submit(...)`，由 `submissionResponse` 翻译状态：`ACCEPTED -> 202`、`RATE_LIMITED -> 429`、`DUPLICATE -> 409`、`FAILED -> 500`。

关键设计：**先查可复用结果再投递**，已完成任务不会再次进 MQ。

#### 3.3 投递与幂等（请求线程）

`AnalysisDispatchService.submit(mediaFile, goal, revision = null, mode)`：
1. 计算任务身份：`contentHash = normalizeContentHash(mediaId, mediaService.contentHash(mediaId))`（内容是第一维），`goalDigest = goalDigest(goal, mode)`（目标+模式是第二维；非 GENERAL 模式把模式名并入 SHA-256 摘要，避免"同目标不同模式"串键，见 `AnalysisTaskKeys`）；
2. `redisTemplate.setIfAbsent(activeKey = analysis:active:<contentHash>:<goalDigest>, mediaId, TTL 6 小时)`：抢不到说明同任务在途，返回 DUPLICATE；
3. `tryAcquireQuota(userId)`：Redisson 令牌桶，用户级 5 次/分钟 + 全局 30 次/分钟；超限删 activeKey，返回 RATE_LIMITED；
4. `rocketMQTemplate.convertAndSend(video-analysis-topic, new AnalysisTaskMsg(mediaId, START_ANALYSIS, contentHash, goal, mode))`；
5. 投递成功后才发布 QUEUED 事件；投递抛异常则删 activeKey 并记日志返回 FAILED——此时任务对用户"未受理"，可安全重试。

这是第一条异步边界：HTTP 线程到"消息已投递"即返回 202，后续全部由消费者线程执行。topic 名配置在 `server/src/main/resources/application.properties` 的 `rocketmq.topic.video-analysis`（默认 `video-analysis-topic`，可被环境变量覆盖）。

#### 3.4 消费：锁、复用与 Checkpoint 恢复

`VideoAnalysisConsumer` 通过 `@RocketMQMessageListener(topic = video-analysis-topic, consumerGroup = video-analysis-consumer, maxReconsumeTimes = 2)` 消费 `AnalysisTaskMsg`。`onMessage` 顺序：
1. **毒消息拦截**：`rejectionReason` 校验 mediaId、goal、action（仅 `START_ANALYSIS` / `REVISE_ANALYSIS`）；非法消息落 `FailedAnalysisTask` 台账 + 转发 `video-analysis-dead-topic`，不浪费 ASR/LLM；
2. **分布式锁**：`redissonClient.getLock(lock:analysis:<contentHash>:<goalDigest>).tryLock()`，保证同一任务只被一个消费者执行；抢不到直接 return（`待确认`：本环境单实例消费，未验证多实例下未抢到锁消息的去向，只确认了跳过路径）；
3. **投递计数**：`INCR analysis:attempts:<contentHash>:<goalDigest>`（TTL 6 小时），与注解对齐出应用级上限 3 次投递；
4. **completedKey 复用**：`analysis:completed:<contentHash>:<goalDigest>`（TTL 7 天）存在时，读源 mediaId 的已完成 `AgentState`，经 `aiService.reuseResult` 挂到当前 mediaId，发布 `COMPLETED_REUSED` 后收尾——同一内容换用户上传不再重跑 ASR/Agent；
5. 正常路径：`saveStage(CONSUMING)` -> `aiService.asyncAnalyze(mediaId, goal, mode)`；
6. 收尾：写 completedKey（7 天），`loadResult` 拿到终态并发布 COMPLETED。

#### 3.5 AiService：恢复与上下文构建

`AiService.asyncAnalyze` 是后台分析的应用层编排：
1. `checkpointService.loadResult(mediaId, goal, mode)`：目标级 Checkpoint 命中直接 `persistResult`（把 `aiSummary` 写回 media 表并失效用户列表缓存）返回；
2. `resolveContext(mediaFile, goal, traceId, mode)`：
   - 先查 `loadContext(mediaId)`——本视频已构建则换目标直接复用同一份 ASR/OCR 结果；
   - 再查内容级归属索引 `analysis:context-owner:<contentHash>`——另一 mediaId 已构建过同一内容则挂接复用；
   - 都未命中则抢内容级锁 `lock:analysis-context:<contentHash>`（等待窗口 5 分钟），抢到才 `buildContext`；没抢到且窗口耗尽就抛异常交给 MQ 重投，重投时另一条消息已落盘，会在前两次复用检查中命中；
3. `buildContext` 发布 VIDEO_CONTEXT 事件 -> `videoContextService.build(filePath, goal, traceId)` -> `saveContext` + 登记 contextOwner。

#### 3.6 上下文构建：ASR/OCR 双分支

`VideoContextService.build`：`minioUtils.readableSource(videoPath)` 先取本地可读源，再开两个独立有界线程池并行：
- ASR 分支：`transcriptionService.transcribe`（按 60 秒切片逐段识别，课 6 展开）；
- OCR 分支：`extractKeyFrames`（ffmpeg 场景检测 `scene>0.35` + 30 秒保底采样抽关键帧 -> Tesseract OCR）。

两个分支提交后共享 60 分钟总预算；单路失败不阻塞另一路（`finishContext` 只计数，两路都失败才抛异常），OCR 失败会连带清理已上传证据帧。`merge` 把语音区间、OCR 文本、关键帧统一成带时间戳的 `VideoSegment` 列表，产出 `VideoContext`。

复用语义：上下文是**内容级**缓存，只取决于视频本身、与目标无关——"同一视频换目标不重新 ASR"由此而来（课 12 展开 Checkpoint 双写）。

#### 3.7 长视频：分块 + 证据检索

`AiService` 发布 AGENT_LOOP 事件后调 `agentLoopService.run(...)`。进入 LLM 前，`AgentLoopService.runWithinBudget` 第一步是 `longVideoContextService.selectRelevant(mediaId, context)`：
- 视频总时长 <= 5 分钟：不检索，按上下文预算（`MAX_CONTEXT_CHARS = 24_000`）裁剪后全量进 Agent；
- 超过 5 分钟：`resolveChunks` 优先读 `checkpointService.loadChunks`，未命中则 `VideoChunkingService.build`（每 5 分钟一个块：LLM 摘要 + 关键词 + Embedding，异常时降级为原文截断与空向量）并 `saveChunks` + `retrievalService.index` 写 Qdrant；再 `retrievalService.retrieve(mediaId, goal, chunks)` 混合检索召回 Top3 区块。

检索打分是三级混合（`VideoEvidenceRetrievalService`）：chunk 层 = Qdrant/余弦语义分 x 0.6 + 语音关键词 x 0.25 + OCR 视觉关键词 x 0.15（`待确认`：当前实现值，未见线上调参记录）；segment 层再叠加 transcript/OCR 词项分。Qdrant 或 Embedding 不可用时降级到内存向量与关键词排序，不阻断主链。

#### 3.8 AgentLoop：规划-执行-校验

`AgentLoopService.run` 在 `AgentExecutionBudget`（`agent.budget.*`：最多 2 轮、120 秒、Estimated Token/Cost 上限）内执行：
1. 恢复：`loadCriticState` 读上次 Plan/Result/Critique，`terminalCheckpoint` 命中且校验通过直接返回；
2. Planner：`deepSeekUtils.plan` 把目标拆成任务计划（结构非法则 `repairPlan`），保存后发布 PLAN_COMPLETED；
3. Executor：发布 EXECUTOR_STARTED -> `deepSeekUtils.execute` 生成含时间戳证据的结构化结论 -> `saveExecutionState` -> EXECUTOR_COMPLETED；
4. Critic：发布 CRITIC_STARTED -> `deepSeekUtils.critique` -> 结构约束与证据约束修正 -> 通过则 CRITIC_PASSED 结束；未通过且轮次 < 2，`longVideoContextService.refineForCritique` 按反馈与缺失时间戳定向补证，`revisePlanForRetry` 进入下一轮；
5. 收尾：`checkpointService.saveResult` 持久化终态，回到 `AiService.persistResult` 写 media 表。

#### 3.9 SSE 事件回传与前端恢复

`TaskEventService` 是事件中枢：
- `subscribe`：`/analysis-events` 调它创建 `SseEmitter`（30 分钟超时），注册进内存 map（键 `analysis:<mediaId>:<goalDigest>`），先推送当前状态，断线重连/重开面板能立刻看到现场；
- `publishAnalysis`：业务侧每推进一个阶段调它，序列化成 `{key, event}` 后 `redisTemplate.convertAndSend(dovideo:task-events)`；通道接收数为 0 或异常时降级 `publishLocal` 直发本机订阅者；
- `onMessage`：`TaskEventRedisConfig` 的 `RedisMessageListenerContainer` 把通道消息回调给它，再 `publishLocal` 分发到对应 emitter，终态事件触发 `complete` 并移除订阅。

前端 `client/src/taskEvents.js` 指数退避重连 SSE（课 13 展开）；断线恢复后 `useAnalysisWorkspace.js` 先调 `/analysis-status` 拿当前终态/阶段，再决定补流还是直接展示。COMPLETED 事件携带 Markdown 结果，前端渲染后调 `/agent-plan`、`/agent-trace`、`/agent-evaluation` 补齐计划、阶段耗时与质量评估。

### 4. 重要分支

同一场景的变体，按提交/消费/分析三个阶段归类：

**提交阶段**（`AnalysisController` + `AnalysisDispatchService`）：
- A 已完成可复用：`loadResult` 命中 -> 200，前端直接展示旧结果；
- B 在途重复：`setIfAbsent(activeKey)` 失败 -> 409，前端"接管"现有事件流而非重新排队；
- C 限流：用户 5 次/分或全局 30 次/分耗尽 -> 429，activeKey 已删可稍后重试；
- D 投递失败：MQ 不可用 -> 删 activeKey + 500，任务不可见可安全重试。

**消费阶段**（`VideoAnalysisConsumer`）：
- E 毒消息：字段非法 -> 失败台账 + 死信主题，不消费不重投；
- F 内容复用：completedKey 命中 -> 结果挂接新 mediaId，发布 COMPLETED_REUSED；
- G 上下文锁争用：同一视频的 ASR/OCR 正在被别人构建 -> 等待 5 分钟 -> 抛异常由 MQ 重投，重投后命中复用；
- I 消费失败收敛：永久失败（`IllegalArgumentException`/`SecurityException`/`NoSuchElementException` 及其 cause 链内同类）或达 3 次投递 -> 失败台账 + 死信主题 + DEAD_LETTERED 事件；临时失败未达上限 -> RETRYING 事件 + 抛出由 RocketMQ 重投；
- K 媒体被删除：消费时 `mediaService.exists` 为假 -> 直接丢弃；分析完成后媒体不存在 -> `purgeRuntimeArtifacts` 清理产物。

**分析阶段**（`AiService` / `AgentLoopService`）：
- H ASR/OCR 单路失败：另一路信息保留，OCR 失败连带清理已上传证据帧；两路都失败 -> 抛异常进入消费端失败收敛；
- J Agent 预算耗尽：`BudgetExceededException` -> BUDGET_EXHAUSTED + FAILED 事件，任务明示"预算耗尽"而非无提示失败。

### 5. 完整流程串联

**主流程示例**：用户上传 40 分钟课程录制，提交目标"生成结构化学习笔记，引用关键时间戳"（LEARNING 模式）。
1. 前端分片上传 -> 合并 -> 拿到 mediaId；`analyze()` 确认无在途任务后 POST `/analysis/ai`。
2. 后端归一目标、验归属、查目标级结果未命中 -> `submit` 写 activeKey、扣配额、投递 START_ANALYSIS 消息 -> 前端收到 202 并建立 SSE。
3. 消费者抢内容级锁 -> 计数 -> `asyncAnalyze`：无目标级结果、无内容级上下文 -> 内容锁内并行 ASR+OCR，产出带时间戳的分段上下文并落 Checkpoint、登记 contextOwner -> 发布 AGENT_LOOP。
4. `AgentLoopService`：长视频走 `selectRelevant` -> 5 分钟分块（摘要 + 关键词 + Embedding 写 Qdrant）-> 混合检索召回相关块 -> Planner 拆任务 -> Executor 生成带证据结论 -> Critic 首轮不通过 -> 定向补证 -> 第二轮通过 -> `saveResult` 落库、`persistResult` 写 media 表、completedKey 置 7 天。
5. 每个阶段一条 `dovideo:task-events` 通道消息 -> SSE 逐阶段推给前端 -> COMPLETED 携带 Markdown 结论 -> 前端渲染并拉取 plan/trace/evaluation。

**已实现的失败与恢复路径**（细节归课 5/12/13）：提交阶段限流/重复/投递失败见分支 B/C/D；消费遇临时异常 -> RETRYING + 重投，Checkpoint 保证已落盘阶段跳过；永久失败或 3 次投递耗尽 -> 死信主题 + 失败台账，可人工重放；前端断线 -> SSE 重连 + `/analysis-status` 补齐现场。

**流程止步之处**（不补造能力）：仓库没有"任务主动取消、删除已排队 MQ 消息"的实现——分析中删除媒体只会在消费时刻被 `exists` 拦截，或完成后清理运行产物；已进入 Agent 的任务会执行到终态。RocketMQ 跨实例的锁竞争与未抢到锁消息去向按 `待确认` 标注，本课停在已证实的接口边界。

### 6. 回顾与复述任务

**一分钟回顾**：一次分析 = 两条异步边界。请求线程内：确权 -> 幂等 activeKey -> 限流 -> 投 MQ，返回 202；消费者线程内：锁 -> 毒消息拦截 -> completedKey 复用 -> `asyncAnalyze`（目标级恢复 -> 内容级上下文 ASR/OCR -> 长视频分块检索 -> Agent 规划/执行/校验 -> 落库）-> 逐阶段事件经 Redis 通道推到 SSE -> 前端渲染。每个可省的算力都有复用键：目标级 result、内容级 context、内容级 completed。

**复述任务**（不运行项目）：不看代码，用你自己的话按顺序写一次"提交 -> 消费 -> 上下文 -> Agent -> 事件"的完整调用链，标出：① 两条异步边界分别在哪个环节；② 三个不同维度的复用键各自的名字、作用与 TTL；③ ASR 临时故障与永久故障时消息分别走哪条路。写完后回到 3.4 / 3.9 对照检查。

状态：`已展开`

---

## 课 2：认证与会话

读完能回答：一次受保护请求如何被鉴权，为什么选 Redis 会话而非 JWT？

### 1. 场景与目标

**触发**：首次使用时，用户在 Vue 登录面板提交注册或登录；后续每次打开上传列表、提交分析、订阅 SSE 或退出登录，都带着登录后拿到的 token 访问受保护接口。本课从"用户证明我是谁"走到"后端知道这次请求属于哪个 userId"。

**学完本课你能解释**：
- 注册如何防重复用户名，密码如何存储与比对；
- 登录成功后 token 放在哪里，Redis 里又存了什么；
- `AuthInterceptor` 如何把 HTTP 请求转换成受控制器使用的 `userId`；
- 为什么资源操作还要二次校验归属，以及 Redis 会话相对 JWT 的实际差异。

**最少前置**：HTTP Header、Bearer Token、Redis key/TTL、Spring MVC 拦截器、请求属性。生活类比：注册是"给身份建档"，登录是"前台核对密码后发一张临时门禁卡"，Redis 是"门禁系统里的卡片登记表"，拦截器是"每次进门先刷卡验卡"，资源归属校验是"刷卡通过后还要确认这个房间确实属于你"。

**一句话总结**：本课要打通的边界是"凭据 -> Redis 会话 -> 请求属性 userId -> 资源归属"，中间任何一步失效或越权都不应进入业务写入。

### 2. 主链与阅读顺序

完整主链：`client/src/App.vue 的 handleAuth -> client/src/api.js 的 apiRequest -> POST /user/register | /user/login(UserController) -> AuthService.register / login -> createSession 写 Redis -> 前端 setAuthToken -> 后续 Authorization: Bearer -> AuthInterceptor.preHandle -> AuthService.resolveUser -> request attribute authenticatedUserId -> Controller 注入 userId -> MediaService.requireOwnedMedia`

推荐阅读顺序：

1. `server/src/main/java/com/example/server/controller/UserController.java`——输入：注册/登录/登出请求；下一步：2；输出：统一 `Result` 响应。注意 `/user/register` 与 `/user/login` 用不同校验分组。
2. `server/src/main/java/com/example/server/service/AuthService.java` 的 `register` / `login` / `passwordMatches` / `createSession` / `resolveUser`——输入：`AuthRequest` 或 `Authorization`；下一步：3；输出：用户档案、token 或认证失败。
3. `server/src/main/java/com/example/server/config/AuthInterceptor.java` 的 `preHandle`——输入：HTTP 请求头；下一步：4；输出：放行并写入 userId，或返回 401 统一响应。
4. `server/src/main/java/com/example/server/config/WebConfig.java` 的 `addInterceptors`——输入：拦截器实例；下一步：5；输出：`/media/**`、`/analysis/**`、`/admin/**`、`/user/logout` 的保护范围。
5. `server/src/main/java/com/example/server/service/MediaService.java` 的 `requireOwnedMedia`——输入：mediaId 与 userId；下一步：6；输出：本人媒体记录或 `SecurityException`。
6. `client/src/api.js` 的 `apiRequest` 与 `client/src/App.vue` 的登录/失效处理——输入：token 或 401；下一步：7；输出：自动携带 token、清理本地会话、触发 `auth-expired`。
7. `server/src/main/resources/db/migration/V1__create_core_tables.sql` 的 `users` 表——输入：无；输出：唯一索引 `uk_users_username`，解释并发注册的数据库兜底。

暂缓阅读：PBKDF2 的密码学推导可只看参数与比对方式，不必展开 JCA 细节；分片上传本身归课 3；`AdminController` 的管理端业务只在本课关注 `requireAdmin` 这一层角色校验。

**一句话总结**：先读"发证"（注册/登录/建会话），再读"验卡"（拦截器/Redis），最后读"验房间"（资源归属），三段合起来才是完整认证链。

### 3. 沿流程带读

#### 3.1 前端：登录面板如何拿到并保存 token

`client/src/App.vue` 的 `handleAuth` 根据当前模式选择 `/user/register` 或 `/user/login`。注册成功只提示"直接点登录"，不自动建会话，因为 `AuthService.register` 返回 `token = null`；登录成功后从 `AuthData` 拿 `userInfo` 与 `token`，先保存用户展示信息，再调用 `setAuthToken(data.token)` 写入 `localStorage.authToken`。

此后所有普通请求和 SSE 请求都经过 `client/src/api.js` 的 `apiRequest`。这个函数在每次请求前读取 `authToken`，若存在则设置 `Authorization: Bearer <token>`；收到非 `/user/` 路径的 401 时清掉 token 并派发 `auth-expired`。`App.vue` 的 `handleAuthExpired` 与 `logout` 共用 `resetSessionState`，停止上传与任务流、清空工作台并移除本地用户信息。

**一句话总结**：前端不做权限判断，只负责保存和携带 token，并在 401 或退出时统一清理现场。

#### 3.2 后端：注册、登录与密码校验

`UserController` 先用 Bean Validation 分组拦截明显非法输入：注册要求用户名 3-32 位、密码 8-128 位；登录只要求非空且密码不超过 128 位。这个差异不是宽松，而是兼容历史短密码账号：如果登录也强制新强度，老账号会被挡在门外，无法进入登录后的哈希升级逻辑。

进入 `AuthService.register` 后：`normalizeUsername` 只接受字母、数字、下划线；先查一次用户名；用 `hashPassword` 生成 PBKDF2WithHmacSHA256 哈希（210,000 次迭代、16 字节随机盐、256 位输出）；插入 `User`。如果并发请求绕过了"先查后插"的间隙，数据库 `uk_users_username` 会触发 `DuplicateKeyException`，代码再次转成 409。也就是说，唯一索引才是最终防重边界，前面的查询只是给用户更快、更友好的提示。

进入 `AuthService.login` 后：先检查该用户名的失败计数是否小于 8；再按用户名查 `users`；无论"用户不存在"还是"密码不对"，都返回同一条"账号或密码错误"，避免给攻击者区分用户是否存在的明确信号。`passwordMatches` 对新格式哈希重算 PBKDF2 并用 `MessageDigest.isEqual` 常量时间比较；对未哈希的旧密码做常量时间字节比较，登录通过后立即改写为 PBKDF2 哈希。成功后清除失败计数并创建会话。

**一句话总结**：注册靠"应用预检 + 数据库唯一索引"保证用户名唯一，登录靠慢哈希重算与常量时间比较验证凭据，并把旧密码透明升级到新格式。

#### 3.3 会话：token、Redis key 与 TTL

`AuthService.createSession` 用 `SecureRandom` 生成 32 字节随机数，经 URL 安全 Base64 编码后作为 token 返回。Redis 并不直接保存 token，而是先对 token 做 SHA-256，再写：

```text
auth:session:<Base64Url(sha256(token))> -> userId
TTL = 24 hours
```

这样即使 Redis 数据或日志被看到，也不应直接还原出原始 Bearer token。会话值只存 userId，不存用户角色和昵称；`resolveUser` 每次只恢复身份 ID，控制器如需角色再查库（例如 `AdminController` 调 `requireAdmin`）。

退出登录 `UserController.logout` 接收 `Authorization` 头，`AuthService.revokeSession` 删除对应 Redis key。由于每次请求都要查 Redis，删除 key 后下一次请求立即失效。

关于"为什么不用 JWT"，代码没有写明设计动机，这是设计推断：JWT 的签名验证天然无状态，服务端主动吊销通常需要额外黑名单或版本机制；本项目已经用 Redis 做限流、状态缓存和后续幂等，把"可随时删除的会话"也放进去，能用最短的 `DEL key` 实现登出和踢人下线。代价是每次受保护请求都要查 Redis，Redis 不可用会导致受保护接口无法正常认证。

**一句话总结**：会话是"随机 token 在客户端、SHA-256 后的登记表在 Redis"，TTL 决定自然过期，删除 key 决定立即失效。

#### 3.4 拦截器：从 Bearer token 到请求属性

`WebConfig.addInterceptors` 只把 `AuthInterceptor` 绑定到 `/media/**`、`/analysis/**`、`/admin/**` 和 `/user/logout`，所以 `/user/register`、`/user/login` 和 `HealthController` 不需要已有会话。浏览器发出的 CORS 预检 `OPTIONS` 请求在 `preHandle` 开头直接放行，否则跨域正式请求可能先被鉴权挡住。

正式请求进入 `AuthInterceptor.preHandle` 后：

1. 读取 `Authorization`；
2. `AuthService.bearerToken` 要求前缀是 `Bearer ` 且剩余 token 长度不小于 32；
3. `sessionKey(token)` 计算 SHA-256 并查 Redis；
4. 查到则把 `Long userId` 写入 `request.setAttribute(AuthService.REQUEST_USER_ID, userId)`；
5. 控制器方法用 `@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId` 接住这个值。

失败时拦截器不能依赖 `@RestControllerAdvice`，因为此时还没进入控制器流程，所以它直接写 HTTP 401 和统一 JSON 信封：`code = 40100`、`message` 来自 `SecurityException`。这保证了前端解包层看到的仍是 `{code, message, data}` 结构。

**一句话总结**：拦截器是受保护请求的统一门卫，把 Redis 会话翻译成请求内可用的 userId，并把所有认证失败收敛成同一个 401 响应契约。

#### 3.5 授权：认证通过不等于可以访问任意资源

认证回答"这是哪个 userId"，授权回答"这个 userId 能否操作这个对象"。`MediaService.requireOwnedMedia` 先按 mediaId 查 `MediaFile`；不存在抛 `NoSuchElementException`；若数据库里的 `userId` 与请求属性里的 userId 不等，抛 `SecurityException("无权访问该文件")`。

`MediaController`、`AnalysisController.aiAnalyze` 和 `MediaProcessingController` 的资源接口都先调用这个方法。以提交分析为例：`AnalysisController.aiAnalyze` 先 `requireOwnedMedia(id, userId)`，之后才检查可复用结果、进入 `AnalysisDispatchService.submit`。也就是说，后端不会相信前端传入的 mediaId 本身，前端是否出现在自己的列表里不是安全边界。

这里有一个边界要诚实区分：进入后台消费后，`AiService.asyncAnalyze` 按 mediaId 直接 `mediaFileMapper.selectById`，不再重新做当前用户的归属校验，因为消息已经在 HTTP 提交边界通过了授权。这是"请求线程做授权、消费线程按已授权任务执行"的信任边界，不是每条消息都重复一次用户鉴权。

**一句话总结**：拦截器解决"你是谁"，`requireOwnedMedia` 解决"这东西是不是你的"；消费线程信任提交边界的授权结果，只按任务继续执行。

### 4. 重要分支

**分支 A：登录失败限流。** `AuthService` 用 `auth:login-failures:<username>` 记录失败次数。第一次失败时 `INCR` 返回 1，随后设置 10 分钟 TTL；达到或超过 8 次后，`loginAttemptAllowed` 返回 false，登录入口直接 429。成功登录会删除计数。注意这是"用户名维度"限流，不是 IP 维度；并发失败请求仍可能在阈值边界多通过几次，因为"检查"和"失败后递增"不是同一个原子命令。

**分支 B：会话过期。** Redis key 有 24 小时 TTL，到期后 `resolveUser` 拿到 null，抛"登录状态已失效"。`AuthInterceptor` 转成 401；前端 `apiRequest` 清 token、派发 `auth-expired`，`App.vue` 重置工作台。

**分支 C：主动登出。** `/user/logout` 先被拦截器验卡，通过后由 `revokeSession` 删除 key。如果 key 已过期，这本身也会 401；前端 `.catch(() => {})` 后仍会本地清理。这和"门禁卡过期就不能用它去登记台注销"是同一语义。

**分支 D：并发注册。** 两个请求可能同时通过 `selectCount` 检查；数据库唯一索引保证只有一条插入成功，失败方捕获 `DuplicateKeyException` 返回 409。

**分支 E：管理员操作。** 拦截器只恢复 userId，`AdminController` 再调用 `AuthService.requireAdmin(userId)` 查库并要求 `role == ADMIN`。因此角色不在会话里长期缓存，角色变化在下一次管理操作时生效。

**一句话总结**：本课的失败路径集中在"限流计数、会话过期、主动吊销、唯一索引兜底和角色二次判定"，认证主流程成立不代表这些边界可以忽略。

### 5. 完整流程串联

用一个具体请求走一遍：用户在登录面板输入 `coder_day1 / study123`，前端 `POST /user/login`；`UserController` 用 Login 分组校验非空和长度；`AuthService.login` 检查失败计数、查库、重算 PBKDF2 并比较；密码正确后清失败计数，`createSession` 生成 token 并写 `auth:session:<sha256(token)> -> userId`，TTL 24 小时；前端保存 `authToken`，刷新媒体列表。

接着用户点击自己的某个视频提交分析，`apiRequest` 自动带上 `Authorization: Bearer <token>`；`AuthInterceptor` 计算 token 摘要、查 Redis、写入 userId；`AnalysisController.aiAnalyze` 注入 userId 后先 `requireOwnedMedia(id, userId)`；确认归属后才继续判断结果复用和异步受理。若另一个用户直接猜到这个 mediaId 并请求同一接口，认证能通过，但资源归属校验会抛 `SecurityException`，请求不会继续。

已实现的失败与恢复路径：密码错误会累计 10 分钟窗口内的用户名失败数；超过阈值登录被 429；会话过期或删除 key 后下一次受保护请求 401；前端收到 401 后统一清 token 并重置界面；并发重名注册由数据库唯一索引兜底。本课不展开媒体对象的持久化与清理，那些细节由课 3 和后续课程承接。

**一句话总结**：一条完整请求从"登录发卡"到"验卡、拿 userId、验资源归属"层层收窄，任何一层失败都会把请求停在业务写入之前。

### 6. 回顾与复述任务

本课的关键源码证据集中在：`UserController` 的校验分组与响应归一；`AuthService` 的用户名防重、PBKDF2 校验、旧密码升级、Redis 会话、登出和失败计数；`AuthInterceptor` 的 Bearer 解析与 401 统一输出；`WebConfig` 的保护路径；`MediaService.requireOwnedMedia` 的资源归属；`api.js` 与 `App.vue` 的 token 保存、自动携带和失效清理。未确认项只有一类：仓库没有明文记录"为什么选 Redis 会话而非 JWT"，上文的设计取舍是基于已实现的吊销能力和已有 Redis 依赖做出的推断。

**复述任务**：不看本节，按顺序说出 `POST /user/login` 成功后 Redis 里写入的 key/value/TTL，以及下一次 `POST /analysis/ai` 从 `Authorization` 头到 `requireOwnedMedia` 的五步变化。若你能把"认证、授权、会话吊销"三件事分开讲清楚，课 2 就算过关。

**一句话总结**：能用自己的话讲出"发卡、验卡、验房间、注销"四个动作，并指出 Redis key 和请求属性的边界，就掌握了本课。

状态：`已展开`

---

## 课 3：分片上传与断点续传

读完能回答：大文件上传如何从断点恢复，分片状态存在哪里？

### 1. 场景与目标

**触发**：用户在工作台选择本地视频并点击上传。前端把文件切成多个 5 MiB 分片，最多 3 路并发发送；网络中断或用户取消后，可以重新选择同一文件并继续上传。本课从"选择文件"走到"服务端生成 `MediaFile` 并返回 `MediaSummary`"，不展开后续提交分析。

**学完本课你能解释**：

- 前端如何分片、并发、重试和恢复；
- `uploadId`、Redis、MinIO 分片对象分别保存什么；
- 服务端如何判断"哪些分片已经成功"；
- 合并时为什么用 Redisson 锁和 completed 标记；
- MD5 内容指纹如何产生，课 4 为什么能基于它继续；
- 上传失败后哪些进度会保留、哪些会清理。

**最少前置**：HTTP `FormData`、浏览器 `File.slice()`、Redis Hash/Set/TTL、对象存储基本概念、MD5 摘要、Redisson 分布式锁。生活类比：寄一批大件家具前先登记总单；每个箱子单独寄出，仓库签收表记录"哪些箱子到了"；全部到齐后按编号拆箱组装；组装成功后登记成品编号，并清掉空箱子。

**一句话总结**：本课主线是"初始化上传会话 -> 分片落 MinIO -> Redis 记录成功序号 -> 全部到齐后合并 -> 生成 MediaFile 和内容指纹 -> 清理分片现场"。

### 2. 主链与阅读顺序

完整主链：`client/src/App.vue 的 uploadFile -> client/src/chunkUpload.js 的 uploadVideoInChunks -> resolveUploadSession -> POST /media/init-upload -> 最多 3 路并发 POST /media/upload-chunk -> GET /media/upload-status 断点恢复 -> POST /media/complete-upload -> ChunkUploadService.complete -> MinIO 合并上传 -> MediaService.saveUploadedMedia -> MediaSummary`

推荐阅读顺序：

1. `client/src/chunkUpload.js` 的常量与 `uploadVideoInChunks`——输入：`File`、进度回调、`AbortSignal`；下一步：2；输出：分片计划、恢复会话、并发上传计划。
2. `client/src/chunkUpload.js` 的 `resolveUploadSession` / `initializeUpload` / `uploadChunkWithRetry`——输入：本地文件与 uploadId；下一步：3；输出：待传分片队列、重试策略、完成后的 `MediaSummary`。
3. `server/src/main/java/com/example/server/controller/MediaController.java` 的 `/init-upload`、`/upload-status`、`/upload-chunk`、`/complete-upload`——输入：filename/uploadId/chunkIndex/file；下一步：4；输出：统一 `Result`。
4. `server/src/main/java/com/example/server/service/ChunkUploadService.java` 的 `initialize` / `uploadedChunks` / `uploadChunk` / `complete`——输入：uploadId 与分片；下一步：5；输出：Redis 状态、MinIO 分片对象、`MediaFile`。
5. `server/src/main/java/com/example/server/service/MediaService.java` 的 `normalizeVideoFilename` / `saveUploadedMedia` / `requireOwnedMedia`——输入：文件名、文件地址、userId、MD5；下一步：6；输出：合法文件名、媒体记录、归属校验。
6. `server/src/main/java/com/example/server/utils/MinioUtils.java` 的 `uploadObject` / `copyObjectTo` / `uploadLocalFile` / `removeObject`——输入：对象名和流；输出：MinIO 对象读写删除。
7. `server/src/main/resources/db/migration/V1__create_core_tables.sql` 的 `media_files`——输入：无；输出：媒体表结构，尤其是 `content_hash`。

暂缓阅读：`MediaIngestService.ingestUrl` 和 `YtDlpUtils` 的在线链接解析；媒体列表缓存细节；课 4 的 RocketMQ 提交链路。本课只需要知道合并产物会带着 `contentHash` 进入后续流程。

**一句话总结**：先读前端"怎么切和怎么重试"，再读后端"怎么登记和合并"，最后读"成功后如何产生媒体记录和内容指纹"。

### 3. 沿流程带读

#### 3.1 前端：切分、恢复与并发上传

`client/src/chunkUpload.js:3` 定义核心参数：

- `CHUNK_SIZE = 5 * 1024 * 1024`：单片 5 MiB；
- `UPLOAD_CONCURRENCY = 3`：最多 3 路并发；
- `MAX_TOTAL_CHUNKS = 410`：最多 410 片；
- `CHUNK_MAX_ATTEMPTS = 4`：单片最多尝试 4 次；
- `CHUNK_RETRY_BASE_MS = 800`：重试基础延迟；
- `CHUNK_RETRY_CEILING_MS = 8_000`：重试延迟上限。

前端单分片是 5 MiB，最多 410 片，理论上限约 2.0 GB。`validateVideoFile()` 会拒绝空文件和超过上限的文件；后端也会校验单片大小和 `totalChunks`。

进入 `uploadVideoInChunks()` 后，`totalChunks = Math.ceil(file.size / CHUNK_SIZE)`。例如 12 MiB 的文件会切成 3 片：前两片约 5 MiB，最后一片约 2 MiB。

断点恢复的关键不是把每个分片状态存在浏览器，而是只保存一个 uploadId。本地 key 是：

    upload:<file.name>:<file.size>:<file.lastModified>

`resolveUploadSession()` 会先查这个本地 key。如果有 uploadId，就调用 `GET /media/upload-status`。服务端返回已成功分片序号集合。前端把它过滤成合法索引后，只把缺失索引放进 `pendingChunks`。

如果服务端明确返回 400，并且文案包含 `does not exist`、`has expired` 或 `invalid uploadid`，前端判断这是死会话，清掉本地进度并重新初始化；如果只是断网、401、403、429 或 5xx，则保留本地进度并中断本次尝试。

正式上传时，前端维护一个待传分片队列，启动最多 3 个 worker。每个 worker 从队列取一个 `chunkIndex`，用 `file.slice(start, end)` 生成 `FormData`，发送到 `/media/upload-chunk`。单片失败最多重试 4 次；网络异常、408、429、5xx 会重试，400/401/403 这类确定性错误不重试。重试延迟从 800ms 开始，按指数退避到 8s，并加入随机抖动。

所有分片成功后，前端发 `POST /media/complete-upload`。成功返回 `MediaSummary`，前端调用 `forgetUploadProgress(file)` 清掉本地续传凭据。

**一句话总结**：前端只保存 uploadId，真实分片进度以后端 Redis 为准；失败时区分"死会话"和"临时故障"，并按指数退避重试单个分片。

#### 3.2 初始化：生成 uploadId 和 Redis 元数据

后端入口是 `MediaController.java:41`：

    POST /media/init-upload?filename=xxx.mp4&totalChunks=20

进入 `ChunkUploadService.initialize()`，位置在 `ChunkUploadService.java:58`。

步骤：

1. `mediaService.normalizeVideoFilename(filename)` 校验文件名和扩展名；
2. 校验 `totalChunks` 在 `1..410`；
3. 校验 userId 非空；
4. 生成 `uploadId = UUID.randomUUID()`；
5. 写 Redis Hash 元数据；
6. 设置 1 天 TTL；
7. 返回 uploadId。

Redis 元数据结构：

    upload:chunked:<uploadId>  Hash
      filename    = xxx.mp4
      totalChunks = 20
      userId      = 1001
    TTL = 1 day

这里已经把上传会话和登录用户绑定。后续状态查询、分片上传、合并都会用 `requireUpload()` 校验元数据里的 `userId`。

**一句话总结**：初始化阶段生成一个服务端 uploadId，并把文件名、总片数、归属用户写入 Redis，作为后续所有分片请求的校验依据。

#### 3.3 上传分片：MinIO 保存分片，Redis 记录序号

分片接口在 `MediaController.java:56`，请求体是 multipart/form-data：

    uploadId=...
    chunkIndex=2
    totalChunks=20
    file=<5 MiB binary>

进入 `ChunkUploadService.uploadChunk()`，位置在 `ChunkUploadService.java:85`。

先校验分片非空，且大小不超过 5 MiB。然后 `requireUpload(uploadId, userId)` 做三项校验：

1. uploadId 必须能解析成 UUID；
2. Redis Hash 必须存在，否则说明任务不存在或已过期；
3. Redis 元数据里的 userId 必须等于当前登录用户。

接着校验：

    totalChunks == expectedChunks
    chunkIndex >= 0
    chunkIndex < expectedChunks

通过后把分片写入 MinIO：

    chunk-uploads/<uploadId>/part-<chunkIndex>

MinIO 成功后，把序号加入 Redis Set：

    upload:chunked:<uploadId>:parts

并刷新元数据和 parts 两个 key 的 1 天 TTL。

顺序很重要：先写 MinIO，再把 `chunkIndex` 加入 Redis Set。如果 MinIO 成功但 Redis 写入失败，重试会覆盖同一个 MinIO 分片对象，再补写序号；如果 Redis 里没有序号，合并时不会把不可信分片算成已完成。

**一句话总结**：每个分片先落到 MinIO，成功后才把序号写进 Redis Set；Redis Set 是"哪些分片可信已完成"的记录，元数据 Hash 则负责校验任务存在和归属。

#### 3.4 查询状态：断点续传的真实来源

`GET /media/upload-status` 调用 `ChunkUploadService.uploadedChunks()`，位置在 `ChunkUploadService.java:75`。

服务端先 `requireUpload()` 校验任务存在和归属，再读取 Redis Set，并用 `TreeSet` 去重升序。返回结果可能像：

    [0, 1, 2, 5, 6]

前端看到后就知道：

    总片数 = 20
    已成功 = [0, 1, 2, 5, 6]
    待传   = [3, 4, 7, 8, ..., 19]

所以断点续传的真实进度在服务端 Redis，不在浏览器。浏览器 localStorage 只保存"我可以回到哪个 uploadId"。

**一句话总结**：`upload-status` 每次从 Redis 读成功分片集合，浏览器只保存 uploadId，因此页面刷新或重选同一文件后仍以服务端状态为准。

#### 3.5 合并：锁、幂等、MD5 与 MediaFile

合并接口在 `MediaController.java:65`：

    POST /media/complete-upload?uploadId=...

核心在 `ChunkUploadService.complete()`，位置在 `ChunkUploadService.java:113`。

先拿 Redisson 锁：

    lock:upload:merge:<uploadId>

锁粒度是单个 uploadId，不会阻塞其他用户或其他上传任务，主要避免同一个上传任务被并发合并。`tryLock()` 失败时返回 409"该上传任务正在合并中"。

然后查幂等标记：

    upload:chunked:<uploadId>:completed -> mediaId

如果存在，说明这个 uploadId 已经合并成功，就通过 `mediaService.requireOwnedMedia(mediaId, userId)` 校验后直接返回旧 `MediaFile`，不会重复插入媒体记录。

如果不是重复合并，则：

1. `requireUpload()` 再次校验任务存在和归属；
2. 读取 filename 和 totalChunks；
3. `uploadedChunks()` 读取 Redis Set；
4. 如果 `uploadedChunks.size() != totalChunks`，返回 409"分片尚未全部上传完成"；
5. 创建本地临时文件；
6. 按序从 0 到 `totalChunks - 1` 读取 MinIO 分片；
7. 写入 `DigestOutputStream`，边合并边计算 MD5；
8. 把合并后的本地临时文件上传到 MinIO 完整对象；
9. 调 `mediaService.saveUploadedMedia()` 插入 `MediaFile`；
10. 写 completed 标记；
11. 清理分片对象和 Redis 上传状态；
12. 删除本地临时文件。

合并时的输出流链是：

    MinIO 分片
    -> BufferedOutputStream
    -> DigestOutputStream 更新 MD5
    -> 本地临时文件

合并完成后，`HexFormat.of().formatHex(digest.digest())` 得到 32 位十六进制 MD5 字符串。这个 MD5 存入 `MediaFile.contentHash`。

注意：这里的 MD5 不是密码学安全哈希，也不是登录密码哈希。它是视频内容指纹，用于后续幂等与状态管理；课 4 会继续使用它。

`mediaService.saveUploadedMedia()` 会：

1. 规范化文件名；
2. 设置 filePath、status = COMPLETED、uploadTime；
3. 写入 userId 和 contentHash；
4. 插入 MediaFile；
5. 缓存内容指纹；
6. 失效该用户媒体列表缓存。

成功后，`MediaController` 用 `MediaSummary.from(mediaFile)` 返回 id、filename、status、coverUrl、uploadTime。

**一句话总结**：合并用 Redisson 锁防并发，用 completed 标记做幂等，按序重建文件并顺带计算 MD5，最后生成 `MediaFile` 和内容指纹，再清理分片现场。

#### 3.6 失败路径与清理边界

合并前如果分片不全，服务端读取 Redis Set 后发现数量不足，返回 409"分片尚未全部上传完成"；用户继续补传缺失分片后再点完成。

本地临时文件通过 `finally { Files.deleteIfExists(mergedFile); }` 清理。Redisson 锁也通过 finally 释放。

`saveUploadedMedia()` 如果数据库插入失败，会尝试删除刚上传的完整 MinIO 对象，避免留下无数据库记录的孤儿完整文件；但分片对象要等后续成功合并时才清理。

合并成功后写 completed 标记，再清理：

    chunk-uploads/<uploadId>/part-0
    chunk-uploads/<uploadId>/part-1
    upload:chunked:<uploadId>
    upload:chunked:<uploadId>:parts

清理单个分片失败只记录 warn，不阻塞返回媒体记录。因为完整对象和 `MediaFile` 已经成功，重试合并会通过 completed 标记直接返回，不需要分片对象。

但边界也要诚实：如果服务在 `saveUploadedMedia()` 成功之后、completed 标记写入之前崩溃，客户端重试理论上可能再次合并并插入重复媒体记录。这是当前实现的极端崩溃边界；正常清理失败或重复请求由 completed 标记兜住。

另外，分片上传不校验"除最后一片外必须正好 5 MiB"，也不在分片阶段校验全文件总大小。它只校验单片不超过 5 MiB、totalChunks 不超过 410、chunkIndex 在合法范围、totalChunks 与初始化元数据一致。前端按 file.size 正常切片时不会产生异常分片；直接调接口时存在这些宽松边界。

**一句话总结**：常见失败靠"补传缺失分片 + completed 幂等标记 + finally 清理"恢复，但进程在落库后、标记写入前崩溃，或直接调接口构造异常分片，属于当前实现没有完全堵住的极端边界。

#### 3.7 安全边界：uploadId 不是授权凭证

uploadId 是 UUID，看起来不可猜，但它不是权限凭证。

每个状态查询、分片上传、合并请求都会带登录 token：

    Authorization: Bearer <token>
    -> AuthInterceptor
    -> userId

然后 `ChunkUploadService.requireUpload()` 比较 Redis 元数据里的 userId 和当前登录 userId。如果拿别人的 uploadId 查状态、传分片或合并，会抛 `SecurityException("无权访问该上传任务")`，全局异常处理器转成 HTTP 403。

合并成功后查询旧媒体也走 `mediaService.requireOwnedMedia(mediaId, userId)`。所以链路是：

    登录 token -> userId
    uploadId 元数据 userId == 当前 userId
    MediaFile.userId == 当前 userId

**一句话总结**：uploadId 只是定位上传任务的 UUID，不是授权凭证；每次操作都把登录用户和 Redis/数据库中的归属字段再对一遍。

### 4. 重要分支

**分支 A：正常上传。** 初始化后前端并发传所有分片；每个分片写 MinIO 成功后序号进 Redis；全部分片到齐后合并、落库、清理。

**分支 B：断点续传。** 前端保存 uploadId；重新选择同一文件时调用 `upload-status`；服务端返回已成功分片；前端只传缺失分片。

**分支 C：死会话。** Redis 元数据过期或 uploadId 无效，后端返回 400；前端识别死会话文案后清除本地 uploadId，重新初始化上传。

**分支 D：临时网络故障。** 分片请求网络异常、408、429、5xx 时，前端最多重试 4 次；重试之间使用指数退避和随机抖动。

**分支 E：取消上传。** `AbortController` 触发 `AbortSignal`；前端抛 `UploadAbortedError`，保留本地 uploadId和服务端已成功分片，用户可以继续上传。

**分支 F：分片不全就合并。** 服务端读取 Redis Set，发现数量不足，返回 409；用户补齐后重试。

**分支 G：重复合并。** 如果 completed 标记存在，服务端不重新合并、不重复插库，而是校验 `MediaFile` 归属后直接返回旧记录。

**分支 H：清理失败。** 分片对象或 Redis 状态清理失败只记录 warn，不影响已成功返回的媒体记录；重试合并会走 completed 幂等返回，残留分片可能需要运维观察。

**一句话总结**：常见失败由"补传 + 临时重试 + 幂等返回"覆盖，死会话会重新初始化，但崩溃窗口和清理残留是明确的实现边界。

### 5. 完整流程串联

假设用户上传一个 12 MiB 的 `lesson.mp4`。

1. 前端计算 `totalChunks = ceil(12MiB / 5MiB) = 3`。
2. 前端调用 `POST /media/init-upload?filename=lesson.mp4&totalChunks=3`。
3. 服务端生成 `uploadId = abc123`，Redis 写入 filename、totalChunks、userId，TTL 1 天。
4. 前端把 12 MiB 切成 part-0 约 5 MiB、part-1 约 5 MiB、part-2 约 2 MiB。
5. 3 个 worker 并发上传。假设 part-0、part-2 成功，part-1 网络失败并重试 2 次成功。
6. Redis Set 变化为 `{0}`、`{0,2}`、`{0,1,2}`。
7. MinIO 中出现 `chunk-uploads/abc123/part-0`、`part-1`、`part-2`。
8. 前端调用 `POST /media/complete-upload?uploadId=abc123`。
9. 服务端确认 Set 大小等于 3，然后按 part-0、part-1、part-2 合并到临时文件，并计算 MD5。
10. 合并后的完整文件上传到 MinIO，数据库插入一条媒体记录：id、filename、user_id、status、content_hash。
11. Redis 写 `upload:chunked:abc123:completed = mediaId`，TTL 1 天。
12. 服务端清理三个分片对象和 upload 元数据，返回 `MediaSummary`。
13. 前端刷新媒体列表并打开该视频的分析工作台。

如果用户在 part-1 失败时断网，浏览器保存 `uploadId=abc123`。用户重新选择同一个文件后，前端调用 `upload-status`，看到 `{0,2}`，只重新传 part-1，不需要从零开始。

**一句话总结**：一条完整上传链路从"前端切片"到"服务端按序合并"再到"媒体记录和内容指纹"，断点恢复靠服务端分片集合而不是浏览器猜测。

### 6. 回顾与复述任务

本课关键源码证据集中在：

- `client/src/chunkUpload.js` 的 5 MiB 分片、3 路并发、4 次重试、指数退避；
- `resolveUploadSession()` 的 uploadId 恢复与死会话判断；
- `MediaController` 的四个上传接口；
- `ChunkUploadService.initialize()` 的 Redis Hash 元数据；
- `ChunkUploadService.uploadChunk()` 的 MinIO 写入和 Redis Set 记录；
- `ChunkUploadService.complete()` 的合并锁、幂等标记、MD5、数据库落库和清理；
- `MediaService.saveUploadedMedia()` 的媒体记录与内容指纹；
- `requireUpload()` / `requireOwnedMedia()` 的归属校验。

需要标注的边界：MD5 在这里作为内容指纹使用，不应宣传为强密码学完整性证明；进程在落库后、completed 标记写入前崩溃时，当前实现存在重复插入媒体的极端窗口；分片清理失败后可能残留 MinIO 对象；直接调接口时，分片大小和总数校验比前端正常路径宽松。

**复述任务**：不看正文，回答五个问题：

1. 前端为什么只保存 uploadId，而不保存每个分片的成功状态？
2. Redis 里上传元数据、已传分片、完成标记分别是什么 key？
3. 一个分片写 MinIO 成功但 Redis Set 写失败，会发生什么？
4. 合并时为什么要 Redisson 锁和 completed 标记？
5. MD5 是怎么算出来的，课 4 会用它做什么？

如果你能完整说出"初始化、分片、查询状态、合并、落库、清理"六步，并指出服务端 Redis 是断点进度真源，课 3 就算过关。

**一句话总结**：分片上传不是"前端记住进度"，而是"前端定位会话、MinIO 保存分片、Redis 记录可信进度、后端合并时生成媒体指纹"。

状态：`已展开`

---

## 课 4：异步提交与幂等防重

读完能回答：分析请求如何变成消息，怎么保证不重复提交？

主链：`POST /analysis/ai(AnalysisController.aiAnalyze：已有可复用结果直接返回 200) -> normalizeText 归一目标 -> AnalysisDispatchService.submit -> contentHash(mediaId) + goalDigest(goal, mode) 拼 activeKey -> Redis SETNX(ACTIVE_TTL 6h) -> tryAcquireQuota(用户级 + 全局 RRateLimiter) -> convertAndSend(video-analysis-topic, AnalysisTaskMsg) -> 发布 QUEUED 事件 -> 返回 202`

重要分支：SETNX 失败 -> DUPLICATE（409 CONFLICT）；配额不足 -> 删 activeKey 后 RATE_LIMITED（429）；MQ 异常 -> 清理 activeKey 后 FAILED。

必读路径：
1. `server/.../controller/AnalysisController.java` 的 `/analysis/ai`（`aiAnalyze`）与 `submissionResponse`——输入：id/goal/mode；下一步：2；输出：200/202/409/429 的映射。
2. `server/.../service/AnalysisDispatchService.java` 的 `submit`——输入：MediaFile/goal/revision/mode；下一步：3；输出：`SubmissionResult` 枚举。
3. `server/.../utils/AnalysisTaskKeys.java`——输入：mediaId/goal/mode；输出：contentHash、goalDigest、activeKey 的拼键规则。
4. `server/.../dto/AnalysisTaskMsg.java`——输入：投递参数（mediaId/action/contentHash/goal/mode）；输出：MQ 消息结构。

选读证据：`.env.example` 的 `AGENT_MAX_ROUNDS`、`AGENT_MAX_DURATION_MS` 等预算配置（与限流独立）。

暂缓阅读：消费侧加锁与投递上限（课 5）；REVISE_ANALYSIS（追问修订）由课 14 负责。

状态：`大纲`

---

## 课 5：消息消费与任务阶段推进

读完能回答：消费者如何从接消息推进到终态，如何防重复消费与毒消息？

主链：`RocketMQ 投递 -> VideoAnalysisConsumer.onMessage(@RocketMQMessageListener, maxReconsumeTimes=2) -> rejectionReason 检查毒消息 -> 抢 Redisson RLock(内容指纹+目标摘要) -> Redis 计数器(MAX_DELIVERY_ATTEMPTS=3) -> AgentCheckpointService 读阶段 -> 跳过已完成阶段 -> 执行未完成阶段(上下文/检索/Agent) -> 每阶段 TaskEventService.publishAnalysis -> 终态 saveResult`

重要分支：毒消息 -> `discardPoisonMessage` 投死信主题 `video-analysis-dead-topic`；锁未抢到 -> 跳过本轮交由 MQ 重投；任务反复失败 -> `FailedAnalysisTaskService` 落库记录。

必读路径：
1. `server/.../consumer/VideoAnalysisConsumer.java` 的 `onMessage` 与 `rejectionReason`——输入：AnalysisTaskMsg；下一步：2；输出：阶段推进 / 死信。
2. `server/.../utils/AnalysisTaskKeys.java` 的 `normalizeContentHash` / `active`——输入：mediaId/hash/goal；输出：锁与计数键。
3. `server/.../service/AgentCheckpointService.java` 的 `loadStage` / `saveStage`——输入：mediaId/goal/mode；下一步：4；输出：阶段跳转。
4. `server/.../service/FailedAnalysisTaskService.java`——输入：失败任务信息；输出：复查记录。

选读证据：`VideoAnalysisConsumer` 顶部注释（maxReconsumeTimes 与 MAX_DELIVERY_ATTEMPTS 对齐的原因）。

暂缓阅读：各阶段具体实现（课 6-9）；事件推送与订阅机制（课 13）。

状态：`大纲`

---

## 课 6：多模态上下文构建

读完能回答：语音与画面如何变成带时间戳的统一证据？

主链：`消费链路需要上下文 -> VideoContextService.build(readableSource -> 临时 workDir) -> 双分支提交(asrExecutor: SegmentedTranscriptionService.transcribe; ocrExecutor: extractKeyFrames + OcrUtils) -> CountDownLatch(2) 等待 -> 60 分钟总超时 -> finishContext 合并 VideoSegment -> saveContext -> 清理 workDir 与失败证据帧`

重要分支：ASR 分支按 60 秒切片（FFmpeg 抽音频）并做有限指数退避重试；视觉分支按场景变化抽帧 + 30 秒保底采样兜底，感知哈希去重后 OCR，证据帧上传 MinIO `evidence-frames/`；单路失败仍保留另一路，由 `BranchResult` 标记。

必读路径：
1. `server/.../service/VideoContextService.java` 的 `build` / `finishContext`——输入：videoPath/userGoal/traceId；下一步：2、3；输出：VideoContext。
2. `server/.../service/SegmentedTranscriptionService.java` 的 `transcribe`——输入：可读视频路径/音频目录；下一步：1（finishContext）；输出：TranscriptSegment 列表。
3. 视觉分支（`VideoContextService` 内 `extractKeyFrames`）与 `server/.../utils/OcrUtils.java`——输入：视频路径/帧目录；输出：FramePart（帧文件 + OCR 文本 + 时间戳）。
4. `server/.../dto/VideoContext.java` 与 `VideoSegment`——输入：合并结果；输出：统一结构（语音区间 / OCR 文本 / 证据帧 / 起止时间）。

选读证据：`README.md` 的 `[02:00 - 03:00]` 三段式示例；`server/src/main/resources/evaluation/golden-video-tasks.json` 的"纯语音 / 纯画面"用例。

暂缓阅读：分块与向量化（课 7）；音频切片与 ASR 重试的深水区可在本课选读路径 2 内自行下钻。

状态：`大纲`

---

## 课 7：长视频分块与向量化

读完能回答：上下文如何按 5 分钟分块并写入向量库？

主链：`VideoContext 已保存 -> VideoChunkingService(CHUNK_MS=5min 聚合原始 60 秒段) -> 每块生成摘要与关键词(DeepSeek) + Embedding(BGE-M3) -> VideoEvidenceRetrievalService.index -> QdrantVectorStore.upsert(collection video_chunks, payload mediaId/startMs/endMs) -> saveChunks 写检查点`

重要分支：Embedding 失败产出空向量被过滤；Qdrant 写入失败 -> `vectorStoreFallbacks` 计数，不阻断主链路。

必读路径：
1. `server/.../service/VideoChunkingService.java`——输入：VideoSegment 列表；下一步：2；输出：`List<VideoChunk>`。
2. `server/.../utils/EmbeddingUtils.java`——输入：块文本；输出：向量（或降级空向量）。
3. `server/.../service/QdrantVectorStore.java` 的 `upsert` / `ensureCollection`——输入：mediaId/chunks；输 出：Qdrant points 写入。
4. `server/.../service/AgentCheckpointService.java` 的 `saveChunks`——输入：mediaId/chunks；输出：检查点（供课 12 细读）。

选读证据：`.env.example` 的 `EMBEDDING_MODEL=BAAI/bge-m3`、`QDRANT_COLLECTION=video_chunks`。

暂缓阅读：检索评分与 TopK（课 8）；Qdrant 过滤查询（课 8 选读路径）。

状态：`大纲`

---

## 课 8：证据检索与上下文预算

读完能回答：几小时视频如何只把相关片段喂给模型？

主链：`AgentLoop 取上下文 -> LongVideoContextService.selectRelevant -> VideoEvidenceRetrievalService.rank -> retrievalIntent(模型提取查询意图) -> embed 查询向量 -> vectorScores(Qdrant 远程或本地余弦) -> score = 语义分*0.6 + 关键词分*0.4 -> TOP_K=3 块 -> 还原原始段 -> 按段得分排序 -> withinBudget(MAX_CONTEXT_CHARS=24000) 截断 -> VideoContext`

重要分支：无 Qdrant/无向量 -> 本地余弦 + 关键词；全部段总时长小于 5 分钟 -> 直接预算内全量；检索分低片段被预算截掉。

必读路径：
1. `server/.../service/LongVideoContextService.java` 的 `selectRelevant` / `withinBudget`——输入：context/mediaId；下一步：2；输出：裁剪后的 VideoContext。
2. `server/.../service/VideoEvidenceRetrievalService.java` 的 `rank` / `search`——输入：goal/chunks；下一步：3；输出：ScoredSegment。
3. `server/.../service/QdrantVectorStore.java` 的 `search`——输入：mediaId/queryEmbedding/limit；输出：hits（payload 过滤）。
4. `server/.../dto/VideoChunk.java` 与 `VideoEvidenceHit.java`——输入：检索产物；输出：块与命中的字段定义。

选读证据：`VideoEvidenceRetrievalService` 内 TOP_K、MAX_USER_HITS、评分权重常量。

暂缓阅读：`refineForCritique` 定向补证（课 10）；检索意图的 prompt 细节在 `AiService` / `DeepSeekUtils`（课 9 顺带）。

状态：`大纲`

---

## 课 9：AgentLoop 规划-执行-校验闭环

读完能回答：受控 Agent 如何从目标走到带证据的结果？

主链：`消费链路进入 Agent 阶段 -> AgentLoopService.run -> AgentExecutionBudget.open 开预算 -> runWithinBudget -> loadCriticState(断点) -> 终态检查点直接复用 -> 计划(MAX_PLAN_TASKS=5) -> DeepSeekUtils 执行(结构化结论) -> Critic -> EvidenceVerificationService 校验 -> 未通过且轮次<maxRounds: refineForCritique 补证 -> replan -> 下一轮 -> 通过或超预算: saveResult(ANALYSIS_COMPLETED / WITH_WARNINGS)`

重要分支：预算超限（时长/预估 Token/预估费用）-> `BudgetExceededException` 独立终态，不交给 MQ 重试；`findDeadline` 遍历 cause 链定位预算截止异常。

必读路径：
1. `server/.../service/AgentLoopService.java` 的 `run` / `runWithinBudget` / `checkBudget`——输入：mediaId/context/profile；下一步：2；输出：AgentState。
2. `server/.../dto/AgentState.java` 的 `AgentPlan` / `AgentResult` / `CriticResult`——输入：各阶段产物；输出：状态结构。
3. `server/.../utils/DeepSeekUtils.java` 的规划/执行/校验调用——输入：上下文 + 角色指令；输出：JSON 结构化响应。
4. `server/.../service/AgentExecutionBudget.java`——输入：maxDurationMs；输出：预算作用域与 DeadlineExceededException。

选读证据：`.env.example` 的 `AGENT_MAX_ROUNDS=2`、`AGENT_MAX_DURATION_MS=120000`、`AGENT_MAX_ESTIMATED_TOKENS=50000`。

暂缓阅读：证据校验内部实现（课 10）；模式 Profile 注入（课 11）；Checkpoint 读写（课 12）。

状态：`大纲`

---

## 课 10：证据校验与定向补证

读完能回答：每条结论如何验证"有据可查"，反馈如何改变下一轮输入？

主链：`Critic 产物(CriticResult) -> EvidenceVerificationService 逐条检查(时间戳范围 / 证据来源 / 引用原文可查) -> 产出通过/缺失清单/不支持声明/需补时间戳 -> 未通过时 LongVideoContextService.refineForCritique: requiredTimestamps 附近段 + critiqueQuery 新检索 + 按时间去重合并 -> 下一轮上下文`

重要分支：引用存在但语义冲突（如音画矛盾）——字段级校验会通过，语义判断留给用户，离线评测专门覆盖。

必读路径：
1. `server/.../service/EvidenceVerificationService.java`——输入：result/context；下一步：2；输出：校验结论集合。
2. `server/.../service/LongVideoContextService.java` 的 `refineForCritique`——输入：full/selected/context + critique；输出：补证后的 VideoContext。
3. `server/.../dto/AgentState.java` 的 `CriticResult`（missingRequirements / unsupportedClaims / requiredTimestamps）——输入：校验反馈；输出：下轮检索意图来源。

选读证据：`golden-video-tasks.json` 的"音画冲突-保留不确定性"用例。

暂缓阅读：Critic 角色 prompt（`AiService` / `DeepSeekUtils`，课 9 必读路径 3）；轮次推进逻辑（课 9）。

状态：`大纲`

---

## 课 11：模式路由与多模式产物

读完能回答：系统如何按目标自动选模式，新模式如何扩展？

主链：`前端提交(AUTO 或指定模式) -> ModeRouter.route(先限流 limit:ai:route:user/global -> DeepSeek classifyMode -> AnalysisMode.fromNullable 宽松解析) -> RouteDecision(mode, reason) -> ModeRegistry.of(mode) 取 ModeProfile(plan/execute/critic 指令 + requiredSectionKeys) -> AgentLoopService 按 profile 拼接提示词与校验清单`

重要分支：路由失败 / 超时 / 未知模式 -> 安全回退 GENERAL，不抛异常不阻断主流程；`ModeRegistry` 构造器启动自检保证每个枚举都有 Profile，防读写键不对称。

必读路径：
1. `server/.../service/mode/ModeRouter.java` 的 `route`——输入：goal/userId；下一步：2；输出：RouteDecision。
2. `server/.../service/mode/ModeRegistry.java`——输入：AnalysisMode；输出：ModeProfile（GENERAL 为空指令、LEARNING/REVIEW/CREATION 各有三段指令）。
3. `server/.../dto/AnalysisMode.java`、`ModeClassification.java`、`RouteDecision.java`——输入：模型返回；输出：模式枚举与理由。
4. `server/.../service/AgentLoopService.java` 的 `planInstruction` / `executeInstruction` / `criticInstruction` 与 `missingSectionKeys`——输入：profile；输出：prompt 注入与必需产物检查。

选读证据：`ModeRegistry` 中四个模式的指令原文（学习模式的大纲/自测题/易错点、审查模式的漏洞/夸大/存疑等产物键）。

暂缓阅读：模式产物在前端的渲染差异（`client/src/useAnalysisWorkspace.js`）；路由限流与提交限流的关系（课 4 选读）。

状态：`大纲`

---

## 课 12：Checkpoint 与断点恢复

读完能回答：任务中断后如何跳过已完成阶段恢复？

主链：`消费者/AgentLoop 读状态 -> AgentCheckpointService.load*/save*(键: mediaCheckpoint(context/chunks) + goalCheckpoint(goal,mode)(plan/criticState/result/stage)) -> AgentCheckpointRepository -> Redis 热缓存(7 天) + MySQL 真源双写 -> rememberGoalKey -> 读取优先缓存、缺失回源`

重要分支：同一目标不同模式键不同（互不串键）；视频级上下文与目标级状态分离；阶段写入与结果写入使用不同键后缀。

必读路径：
1. `server/.../service/AgentCheckpointService.java` 的 `loadCriticState` / `saveResult` / `loadContext` / `saveChunks`——输入：mediaId/goal/mode；下一步：2；输出：AgentState / TaskStage。
2. `server/.../service/AgentCheckpointRepository.java`——输入：键 + 类型；输出：MySQL 读写（真源）+ Redis 缓存策略。
3. `server/src/main/resources/db/migration/V1__create_core_tables.sql` 的 checkpoint 相关表——输入：迁移文件；输出：真源表结构。

选读证据：`docs/interview-qa-v2.md`"写作前审计"（MySQL 为恢复真源、Redis 为 7 天热缓存的表述）。

暂缓阅读：阶段事件发布（课 13）；上下文/分块与目标级键的分离理由在本课内展开。

状态：`大纲`

---

## 课 13：SSE 进度推送与前端恢复

读完能回答：前端如何实时看到阶段，断线后如何恢复？

主链：`GET /analysis-events(SSE) -> TaskEventService.subscribe(创建 SseEmitter, 30 分钟超时, 注册到 subscribers map, 先发当前状态) -> 业务侧 publishAnalysis -> redisTemplate.convertAndSend(dovideo:task-events) -> RedisMessageListenerContainer -> TaskEventService.onMessage -> 按 key 找到本地订阅者 -> 逐个 send -> 前端展示`

重要分支：订阅者完成 / 超时 / 错误 -> 从 map 移除；前端断线 -> `client/src/taskEvents.js` 指数退避重连 -> 调 `/analysis-status` 补当前终态。

必读路径：
1. `server/.../service/TaskEventService.java` 的 `subscribe` / `publishAnalysis` / `onMessage`——输入：mediaId/type/goal/mode；输出：SseEmitter 与事件分发。
2. `server/.../config/TaskEventRedisConfig.java`——输入：RedisConnectionFactory；输出：RedisMessageListenerContainer（通道订阅）。
3. `server/.../controller/AnalysisController.java` 的 `/analysis-events`、`/analysis-status`——输入：id/goal/mode；输出：SSE 流 / 当前阶段。
4. `client/src/taskEvents.js`——输入：事件 URL 与重连参数；输出：事件恢复与终态补齐。

选读证据：`client/src/taskEvents.test.js`（重连与状态恢复的测试用例）。

暂缓阅读：前端工作台整体布局（`client/src/useAnalysisWorkspace.js`）。

状态：`大纲`

---

## 课 14：追问、成本护栏与离线评测

读完能回答：继续追问如何复用证据，成本与失败边界有哪些护栏，质量如何离线验证？

主链：`前端追问 -> AnalysisController 交互接口 -> runInteractive(aiTaskExecutor supplyAsync; RejectedExecutionException -> 429) -> requireAiQuota(复用分析配额) -> 基于已保存 VideoContext 继续回答 -> 返回结果；成本护栏汇总: 限流(提交/路由/追问) + 有限重试(ASR/LLM) + 投递上限 + 死信 + Agent 预算；质量评测: OfflineAgentEvaluationRunner + golden-video-tasks.json(4 用例) + AgentEvaluationService`

重要分支：`requireVideoContext` 未就绪 -> `VideoContextNotReadyException`；评测开关 `AGENT_EVALUATION_ENABLED`。

必读路径：
1. `server/.../controller/AnalysisController.java` 的交互式接口与 `runInteractive`——输入：id/goal/追问文本；下一步：2；输出：Future/Result。
2. `server/.../service/AnalysisDispatchService.java` 的 `requireAiQuota`——输入：userId；输出：放行或 429。
3. `server/src/main/resources/evaluation/golden-video-tasks.json`——输入：离线用例（ASR+OCR 互补 / 纯语音 / 纯画面 / 音画冲突）；输出：评测输入。
4. `server/.../service/OfflineAgentEvaluationRunner.java` 与 `AgentEvaluationService.java`——输入：golden 任务；输出：评测结果与指标。

选读证据：`.env.example` 的 `AGENT_EVALUATION_ENABLED`、`AI_INTERACTIVE_TIMEOUT_MS`、`AGENT_MAX_ESTIMATED_COST`。

暂缓阅读：前端交互 UI 细节；评测结果如何沉淀为长期数据集（当前仅 Redis 反馈采样，未闭环，见 `docs/interview-qa-v2.md`）。

状态：`大纲`

---

## 大纲状态说明

- 课 1-3 已展开，其余 11 课当前仍为 `大纲` 状态；用户指定展开某课时，只更新该课并保留其他课程原文。
- 阅读时建议按课 1 建全貌，课 2-5 是后端任务骨架，课 6-8 是"视频 -> 证据 -> 检索"，课 9-11 是 Agent 核心，课 12-13 是可靠性与交付，课 14 是成本与质量治理。
- 尚未覆盖模块见文件头，课后延伸时再评估是否并入课程。
