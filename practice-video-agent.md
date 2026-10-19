# video-agent 14 天完整项目复刻计划

目标不是逐行复制 DOVideo-AI，而是新建一个 `video-agent-mini` 仓库，按 14 课的顺序把完整主链复刻成你能讲、能改、能被追问的最小可运行系统。

**总原则**

1. 每天 2-4 小时：先读 `tutorial-video-agent.md` 对应课 20-30 分钟，再写代码 90-180 分钟，最后写 3 句复盘。
2. 不复制业务代码，只参考本仓库源码结构和源码事实，自己重写最小版本。
3. 每天完成一个可运行闭环并提交一次 git commit。
4. 中间件统一使用 MySQL、Redis、MinIO、RocketMQ、Qdrant；模型能力前期可以 mock，后面再接真实服务。
5. 不做复杂 UI、多租户、运营后台、高可用集群；只做主链路和可解释边界。

## 里程碑

| 里程碑 | 时间 | 复刻完成后的能力 |
| --- | --- | --- |
| M1：接入与文件主链 | Day 1-3 | 登录会话、权限校验、分片上传、断点续传、合并落库 |
| M2：异步与证据主链 | Day 4-7 | 异步任务、防重复消费、多模态上下文、向量入库 |
| M3：Agent 与模式主链 | Day 8-11 | 证据检索、Agent 闭环、证据校验、模式路由 |
| M4：可靠性与交付主链 | Day 12-14 | 断点恢复、SSE 推送、追问限流、离线评测与面试表达 |

---

## Day 1：项目骨架与全链路总览

对应：课 1「全链路总览」。

**当天目标**

- 建一个空 `video-agent-mini` 后端项目。
- 接入 MySQL、Redis、MinIO、RocketMQ、Qdrant 的最小配置。
- 理解“上传 -> 异步分析 -> 证据 -> Agent -> 推送”的主线。

**最小代码任务**

1. 新建 Spring Boot 工程，接入 `Result`、全局异常、基础日志。
2. 写 `HealthController`，返回 MySQL、Redis、MinIO、RocketMQ、Qdrant 的连通状态。
3. 建一张空 `media_files` 表，先用接口插入一条测试数据。
4. 画一张手写顺序图：浏览器、后端、Redis、MinIO、MQ、Qdrant、模型服务之间的关系。

**验收**

1. 能用一句话说出全项目主链。
2. 五个中间件的连通状态都能检查。
3. 能解释每个中间件在这条链路里承担什么。

**面试口径**

> 我把这个项目拆成接入层、数据层、执行层。接入层负责认证、校验、限流和任务提交；数据层用 MySQL 保存真源，Redis 保存状态和缓存，MinIO 保存文件，Qdrant 保存向量证据；执行层负责异步分析、Agent 推理和事件推送。

---

## Day 2：认证、会话与资源归属

对应：课 2「认证与会话」。

**当天目标**

- 完成注册、登录、登出、拦截器、资源归属校验。

**最小代码任务**

1. 建 `users` 表，`username` 加唯一索引。
2. 实现 `POST /auth/register`、`POST /auth/login`、`POST /auth/logout`。
3. 密码哈希用 `PBKDF2WithHmacSHA256`，练习值：210000 次迭代、16 字节随机盐、256 位输出。
4. 登录后写 Redis：`auth:session:<sha256(token)> -> userId`，TTL 24 小时。
5. 实现 `AuthInterceptor`：放行 `OPTIONS`，解析 Bearer token，查 Redis，成功后把 `userId` 放入 request attribute。
6. 实现 `requireOwnedMedia(mediaId, userId)`。
7. 登录失败限流：`auth:login-failures:<username>`，10 分钟窗口最多 8 次。

**验收**

1. 注册重复用户名时应用层查重和数据库唯一索引都生效。
2. 登录成功后能拿到 token，Redis 中只存 token 的 SHA-256。
3. 无 token 返回 401，访问他人资源返回 403。

**面试口径**

> 认证解决“你是谁”，授权解决“这个资源是不是你的”。登录后服务端生成随机 token，Redis 只保存 token 的 SHA-256，value 是 userId，TTL 24 小时。拦截器负责还原用户身份，资源读取时再用 requireOwnedMedia 校验归属，避免合法 token 访问别人的媒体。

---

## Day 3：分片上传、断点续传与合并

对应：课 3「分片上传与断点续传」。

**当天目标**

- 完成大文件切片上传、进度查询、断点续传、按序合并、幂等返回。

**最小代码任务**

1. 实现：
   - `POST /media/init-upload`
   - `GET /media/upload-status`
   - `POST /media/upload-chunk`
   - `POST /media/complete-upload`
2. Redis 结构：
   - `upload:chunked:<uploadId>`：Hash，保存 `filename`、`totalChunks`、`userId`；
   - `upload:chunked:<uploadId>:parts`：Set，保存已成功分片序号；
   - `upload:chunked:<uploadId>:completed`：String，保存 `mediaId`；
   - TTL 1 天。
3. MinIO 分片路径：`chunk-uploads/<uploadId>/part-N`。
4. 前端或脚本按 5 MiB 切片，最多 3 路并发，单分片最多重试 4 次。
5. 合并前检查 Redis Set 数量是否等于 `totalChunks`。
6. 合并使用 Redisson 锁 `lock:upload:merge:<uploadId>`。
7. 用 `DigestOutputStream` 边合并边计算 MD5。
8. 落库后写 completed 标记，再清理分片和元数据。

**验收**

1. 中断后重新上传，只补缺失分片。
2. 重复合并返回旧 mediaId，不重复插库。
3. 分片不足返回 409。
4. uploadId 被其他用户使用时返回无权访问。

**面试口径**

> 大文件上传的难点不是切片本身，而是失败后的状态恢复。浏览器只保存 uploadId，服务端 Redis Set 才是可信分片进度。每个分片先写 MinIO，成功后再记录到 Redis。合并时用分布式锁防止并发合并，用 completed 标记保证幂等，合并过程中顺带生成 MD5 内容指纹。

---

## Day 4：异步提交与幂等防重

对应：课 4「异步提交与幂等防重」。

**当天目标**

- 把耗时分析从 HTTP 请求拆到异步任务，防止重复提交。

**最小代码任务**

1. 实现 `POST /analysis/ai`。
2. 生成 `contentHash(mediaId)` 和 `goalDigest(goal, mode)`。
3. 拼 Redis active key，例如 `analysis:active:<contentHash>:<goalDigest>`。
4. 用 `SETNX` 抢占任务，TTL 6 小时。
5. 抢到后发 RocketMQ 消息，消息里至少包含 `mediaId`、`contentHash`、`goal`、`mode`。
6. 发布 `QUEUED` 事件，HTTP 返回 202。
7. 重复提交返回 409；限流返回 429；MQ 发送失败清理 active key。
8. 增加用户级和全局限流。

**验收**

1. 相同媒体、目标、模式重复提交时不会重复入队。
2. 不同目标或模式可以各自提交。
3. MQ 发送失败时 active key 被清理，用户可以重试。

**面试口径**

> 视频分析是长耗时任务，不能占用 HTTP 请求线程。提交时我用 contentHash 和 goalDigest 生成 activeKey，用 Redis SETNX 抢占，抢到后发 MQ 并返回 202。这样同一任务在有效期内的重复提交会被挡住；如果发 MQ 失败，再回滚 activeKey，避免任务永久卡死。

---

## Day 5：消息消费、状态推进与死信

对应：课 5「消息消费与任务阶段推进」。

**当天目标**

- 消费 MQ 消息，防止重复消费，处理毒消息和失败任务。

**最小代码任务**

1. 实现 `VideoAnalysisConsumer.onMessage`。
2. 消费前做基础校验：`mediaId`、`contentHash`、`goal`、`mode` 是否合法。
3. 用 Redisson 锁按 `contentHash + goalDigest` 抢锁。
4. 抢不到锁时跳过本轮，让 MQ 重投。
5. Redis 记录投递次数，最多 3 次。
6. 超过投递上限投到死信主题，例如 `video-analysis-dead-topic`。
7. 失败任务落库到 `failed_analysis_tasks`。
8. 每个阶段开始、结束都发布事件。

**验收**

1. 同一条消息重复投递时只有一个消费者真正执行。
2. 参数非法的消息进入死信，不会无限重试。
3. 反复失败的任务有失败记录可查。

**面试口径**

> 消费侧的关键是幂等和毒消息隔离。我按 contentHash 和 goalDigest 加分布式锁，抢不到锁就跳过本轮；Redis 计数器限制投递次数，超过上限进死信主题。这样既能容忍 MQ 重复投递，也能避免坏消息一直阻塞正常任务。

---

## Day 6：多模态上下文构建

对应：课 6「多模态上下文构建」。

**当天目标**

- 把视频里的语音和画面证据统一成带时间戳的结构化上下文。

**最小代码任务**

1. 实现 `VideoContextService.build(videoPath, goal)`。
2. 建 ASR 分支：
   - FFmpeg 抽音频；
   - 按 60 秒切片；
   - 调 ASR 或 mock 得到文本和时间戳；
   - 失败时有限指数退避重试。
3. 建 OCR 分支：
   - 按场景变化抽帧；
   - 30 秒保底采样；
   - 感知哈希去重；
   - OCR 后把证据帧上传 MinIO `evidence-frames/`。
4. 用两个线程池并行执行 ASR 和 OCR。
5. 用 `CountDownLatch` 等待两路完成，总超时 60 分钟。
6. 合并成统一 `VideoSegment`：开始时间、结束时间、文本、证据帧。
7. 单路失败保留另一路，并用分支结果标记失败原因。

**验收**

1. 同一个视频能生成带时间戳的语音证据和画面证据。
2. 一路失败不会导致整条任务失败。
3. 处理完成后临时文件和失败证据帧被清理。

**面试口径**

> 长视频里的信息分散在语音和画面里，所以我把上下文构建拆成 ASR 和 OCR 两条分支。ASR 按 60 秒切片转写，OCR 按场景变化抽帧并做感知哈希去重。两条分支并行执行，最后统一成带起止时间的 VideoSegment。单路失败时保留另一路证据，避免一个模型故障拖垮整个任务。

---

## Day 7：长视频分块与向量化

对应：课 7「长视频分块与向量化」。

**当天目标**

- 把长上下文变成可检索的向量证据。

**最小代码任务**

1. 实现 `VideoChunkingService`。
2. 把原始 60 秒片段聚合成约 5 分钟的 `VideoChunk`。
3. 每块生成摘要和关键词，模型可先用 mock。
4. 调 Embedding 服务生成向量，练习使用 BGE-M3。
5. Qdrant collection 先固定为 `video_chunks`。
6. upsert 时 payload 至少包含 `mediaId`、`startMs`、`endMs`、摘要、关键词。
7. Embedding 失败时过滤空向量。
8. Qdrant 写入失败只累计 fallback 计数，不阻断主链路。

**验收**

1. 长视频能被分成稳定时间块。
2. 每块都有文本、向量、payload。
3. Qdrant 中能按 mediaId 查到对应 chunks。

**面试口径**

> 几小时视频不能整段塞给模型，所以我先把原始证据聚合成约 5 分钟的块。每块生成摘要和关键词，再计算 embedding 写入 Qdrant。这样后面检索时不是按帧或秒检索，而是按语义块检索，payload 里保留时间范围，方便回溯原始证据。

---

## Day 8：证据检索与上下文预算

对应：课 8「证据检索与上下文预算」。

**当天目标**

- 只把和目标相关的片段喂给模型，并控制上下文长度。

**最小代码任务**

1. 实现 `LongVideoContextService.selectRelevant(context, goal)`。
2. 先用模型或规则提取检索意图。
3. 将查询文本转成向量。
4. Qdrant 可用时走远程检索；不可用时降级为本地余弦。
5. 评分公式练习为：`score = 语义分 * 0.6 + 关键词分 * 0.4`。
6. 取 TopK 3 块，再还原成原始 `VideoSegment`。
7. 按段得分排序。
8. 实现 `withinBudget(maxContextChars = 24000)`，超出就截断。

**验收**

1. 同一个视频，不同目标会选不同片段。
2. 没有相关片段时不编造证据。
3. 最终上下文不超过预算。

**面试口径**

> 检索层要解决两个问题：相关性和成本。我先根据用户目标提取检索意图，再用语义向量和关键词混合打分，取 TopK 证据块。最后按预算裁剪上下文，避免把整条视频原样发给模型。这样既提高相关性，也控制 token 成本。

---

## Day 9：AgentLoop 规划-执行-校验闭环

对应：课 9「AgentLoop 规划-执行-校验闭环」。

**当天目标**

- 写一个受预算控制的 Agent 循环，而不是一次性问答。

**最小代码任务**

1. 实现 `AgentLoopService.run(goal, context)`。
2. 每轮包含三步：
   - Plan：拆解任务；
   - Execute：基于证据生成答案；
   - Critic：检查是否缺少证据、是否越界。
3. 输出 `AgentState`，至少包含 plan、execute、critic、round、stage。
4. 设置预算：
   - 最多 2 轮；
   - 最长 120 秒；
   - 最大预估 50000 tokens。
5. 超预算时停止循环并返回当前可用结果。
6. 每轮结果写入阶段检查点。

**验收**

1. Agent 不只给答案，还保留推理状态。
2. 超过轮次、时间或 token 预算时能停止。
3. Critic 能指出缺少证据的部分。

**面试口径**

> 我没有把 Agent 写成一次调用，而是拆成 Plan、Execute、Critic 三个阶段。Plan 负责拆任务，Execute 负责基于证据回答，Critic 负责检查证据是否足够。每轮都有轮次、时间和 token 预算，超预算就退出，避免 Agent 无限循环或成本失控。

---

## Day 10：证据校验与定向补证

对应：课 10「证据校验与定向补证」。

**当天目标**

- 检查结论是否可追溯，并让校验结果影响下一轮检索。

**最小代码任务**

1. 实现 `EvidenceVerificationService.verify(result, context)`。
2. 校验每条结论：
   - 是否引用证据；
   - 时间戳是否有效；
   - 引用原文是否能查到；
   - 是否属于不支持声明。
3. Critic 输出缺失清单、不支持声明、需要补证的时间戳。
4. 未通过时调用 `refineForCritique()`。
5. 根据缺失时间戳附近补检索。
6. 根据 critiqueQuery 生成新的查询。
7. 按时间去重后合并到下一轮上下文。

**验收**

1. 没有证据的结论被标记、删除或降级表达。
2. 缺少时间戳时能触发定向补证。
3. 音画冲突时不强行给出唯一结论。

**面试口径**

> Agent 最怕看起来合理但查不到依据。我在 Critic 后增加证据校验：每条结论必须有来源、有效时间戳和可查原文。校验失败不是简单重试，而是把缺失信息转成新的检索条件，去补最近的证据片段。这样下一轮上下文是根据错误反馈定向扩大的。

---

## Day 11：模式路由与多模式产物

对应：课 11「模式路由与多模式产物」。

**当天目标**

- 根据目标自动选择分析模式，并让不同模式产生不同结构化结果。

**最小代码任务**

1. 定义四种模式：`GENERAL`、`LEARNING`、`REVIEW`、`CREATION`。
2. 实现 `ModeRouter.route(goal, userId)`。
3. 路由前加限流：用户级和全局。
4. 调模型分类模式，返回 `RouteDecision(mode, reason)`。
5. 路由失败、超时、未知模式时回退 `GENERAL`。
6. 建 `ModeRegistry`，每个模式都有 `ModeProfile`。
7. `ModeProfile` 包含 Plan、Execute、Critic 三段指令和必需产物键。
8. `AgentLoopService` 按 profile 拼接 prompt 和校验清单。

**验收**

1. 学习目标、审查目标、创作目标路由到不同模式。
2. 每种模式有不同必需产物。
3. 分类失败时不会阻断主流程。

**面试口径**

> 不同目标适合不同回答结构，所以我加了模式路由。路由层先用模型分类目标，再从 ModeRegistry 拿对应 Profile，把 Plan、Execute、Critic 的指令和必需产物键注入 Agent。路由失败时安全回退 GENERAL，保证主链路不被分类模块拖垮。

---

## Day 12：Checkpoint 与断点恢复

对应：课 12「Checkpoint 与断点恢复」。

**当天目标**

- 让任务中断后能从已完成阶段继续，而不是从头执行。

**最小代码任务**

1. 实现 `AgentCheckpointRepository`。
2. MySQL 保存真源，Redis 做 7 天热缓存。
3. 检查点至少分两类：
   - 视频级：`context`、`chunks`；
   - 目标级：`plan`、`criticState`、`result`、`stage`。
4. key 设计要区分 `mediaId`、`goal`、`mode`。
5. 读取时优先 Redis，缓存缺失再查 MySQL，查到后回填 Redis。
6. 每完成一个阶段都保存检查点。
7. 消费者启动时先读取 checkpoint，再跳过已完成阶段。

**验收**

1. 任务在上下文构建后中断，重启后不再重复构建上下文。
2. 任务在 Agent 阶段中断，重启后能从上次 stage 恢复。
3. 同一视频不同目标、不同模式互不串键。

**面试口径**

> 长任务不能只靠内存状态，所以我做了双写：MySQL 保存真源，Redis 做 7 天热缓存。视频级上下文和目标级 Agent 状态分开保存，因为上下文可以复用，而不同目标的推理状态必须隔离。重启后先读 checkpoint，跳过已完成阶段，再执行未完成阶段。

---

## Day 13：SSE 进度推送与前端恢复

对应：课 13「SSE 进度推送与前端恢复」。

**当天目标**

- 前端实时看到任务阶段，并在断线后恢复当前状态。

**最小代码任务**

1. 实现 `GET /analysis-events`。
2. `TaskEventService.subscribe()` 创建 `SseEmitter`，超时 30 分钟。
3. 订阅者保存到本地 map，key 由 `mediaId/goal/mode` 组合。
4. 订阅成功后先发一次当前状态。
5. 业务侧调用 `publishAnalysis()`。
6. 通过 Redis Pub/Sub 通道广播事件，例如 `dovideo:task-events`。
7. `TaskEventService.onMessage()` 根据本地订阅者分发事件。
8. 完成、超时、异常时移除 emitter。
9. 前端断线后指数退避重连，再调 `GET /analysis-status` 补当前状态。

**验收**

1. 阶段变化时前端实时收到事件。
2. 断线重连后能补当前状态。
3. emitter 完成后不会泄漏。

**面试口径**

> SSE 的难点不只是推送，还有多实例和断线恢复。我用 Redis Pub/Sub 广播任务事件，本地服务只负责把事件发给当前连接的 SseEmitter。前端断线后先指数退避重连，再调用状态接口补一次终态，这样即使事件丢失也不会永远停在旧阶段。

---

## Day 14：追问、成本护栏与离线评测

对应：课 14「追问、成本护栏与离线评测」。

**当天目标**

- 支持追问、控制成本、用固定用例检验质量，并完成最终面试表达。

**最小代码任务**

1. 实现追问接口。
2. 追问前校验 `VideoContext` 是否已就绪。
3. 未就绪抛 `VideoContextNotReadyException`，返回明确错误。
4. 复用分析配额，不单独放开无限追问。
5. 用异步线程池执行追问；线程池拒绝时返回 429。
6. 追问基于已保存上下文和证据，而不是重新做完整分析。
7. 汇总成本护栏：
   - 提交限流；
   - 路由限流；
   - 追问限流；
   - ASR/LLM 有限重试；
   - MQ 投递上限；
   - 死信；
   - Agent 轮次/时间/token 预算。
8. 建 `golden-video-tasks.json`，至少 4 类用例：
   - ASR + OCR 互补；
   - 纯语音；
   - 纯画面；
   - 音画冲突。
9. 实现 `OfflineAgentEvaluationRunner` 和 `AgentEvaluationService`。
10. 输出离线评测报告：通过数、失败数、证据缺失数、不支持声明数。

**验收**

1. 上下文没就绪时追问返回明确错误。
2. 追问不会绕过限流。
3. 四个固定用例可以批量跑出结果。
4. 音画冲突场景能表达不确定性。

**面试口径**

> 我把追问当成另一个受限任务，而不是免费聊天。它必须复用已保存上下文，走独立线程池和配额；上下文没就绪时直接返回明确错误。最后我用四类离线用例检查质量，尤其是纯语音、纯画面和音画冲突，确保系统在证据不足时能表达不确定性。

---

## 最终自检

14 天完成后，你应该能同时做到：

1. 独立说出完整主链：上传 -> 异步分析 -> 证据构建 -> 向量检索 -> Agent -> 校验 -> 推送 -> 追问。
2. 说清 MySQL、Redis、MinIO、RocketMQ、Qdrant、SSE 分别解决什么问题。
3. 画出分片上传、异步任务、Agent 闭环、Checkpoint、SSE 五张图。
4. 解释至少 10 个边界场景：
   - 重复注册；
   - token 过期；
   - 越权访问；
   - 分片丢失；
   - 重复合并；
   - MQ 重复投递；
   - ASR/OCR 单路失败；
   - 向量库不可用；
   - Agent 超预算；
   - 断线后恢复。
5. 诚实说明 mini 版和生产版差距，而不是把复刻项目说成已上线系统。
