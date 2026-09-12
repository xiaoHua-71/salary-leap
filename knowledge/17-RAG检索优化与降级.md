# RAG 检索优化与降级

> 在「Milvus 接入」（`15`）之后做的四块工程化改造：**AI 失败降级、异步入库、检索缓存、Rerank 重排序**。
> 目标是把 RAG 从「能跑」推到「扛得住故障 + 检索质量更高」。

## 一、总览

| 改造 | 解决的问题 | 关键类/方法 |
|------|-----------|------------|
| AI 失败降级 | AI 一挂，出题/判分全废 | `LevelServiceImpl.fallbackLevel / localScore`、`PresetLevels` |
| 异步入库 | rebuild 同步阻塞 HTTP 请求 | `RagIndexService.triggerRebuild / doRebuild` |
| 检索缓存 | 相同查询重复「向量化 + 检索」 | `RetrievalCache` |
| Rerank 重排序 | 向量检索不够准 | `QwenScoringModel`、`KnowledgeService.rerank` |

---

## 二、AI 失败降级（可靠性）

### 问题
出题、判分都强依赖 AI（通义千问）。AI 抖动/欠费/超时 → 整个闯关游戏不可玩。**企业铁律：外部依赖故障不能拖垮核心业务。**

### 方案：出题走「三级兜底链」
```
AI 出题失败
  ├─ ① 按用户方向取预设题（selectRandomByDirection）
  ├─ ② 取「通用」题池（direction='通用'）
  └─ ③ 内置保底题（PresetLevels.BUILTIN，落库后返回）
```

### 方案：判分走「本地判分」
AI 判分失败时，直接对比用户选项和正确选项算分，不走 AI：
```
score = clamp(0, 100, round(100 * (选对 - 选错) / 正确总数))
```

### 关键设计
- `LevelServiceImpl.generateLevel`：AI 出题包 try/catch，失败 → `fallbackLevel`
- `LevelServiceImpl.submitLevel`：AI 判分包 try/catch，失败 → `localScore`
- `LevelMapper.selectRandomByDirection`：优先 `source='PRESET'`，排除该用户已答过的题
- 数据模型：`user.direction`（用户标签）、`level.source`（AI/PRESET）、`level.standardAnswer`（预设题解析）
- 种子数据：`sql/preset_level.sql`

### 一句话
**出题主流程永远不因为「检索/向量化/AI」挂了而报错。**

---

## 三、异步入库（工程完备性）

### 问题
`POST /rag/rebuild` 原本是**同步**的：建集合 + 向量化 + 入库全在 HTTP 线程里跑。知识库一大就阻塞请求、甚至超时。

### 方案
```
POST /rag/rebuild → triggerRebuild()：置 RUNNING、丢给后台线程、立即返回
GET  /rag/rebuild/status → 轮询状态
```

### 关键设计
- **单线程执行器**（`Executors.newSingleThreadExecutor`）：重建天生要串行，天然避免并发打架
- **状态用 volatile 整体替换**：`RebuildStatusVO`（IDLE/RUNNING/SUCCESS/FAILED），每次变化 new 一个覆写
- **拒绝重复提交**：`triggerRebuild` 用 `synchronized` 做「检查-置位」原子操作
- `@PreDestroy` 优雅关闭线程池；线程设 `daemon` 不挡 JVM 退出

### 注意
**启动时的首次入库仍是同步的**（`init()`）——应用起来前必须保证向量库就绪，否则第一批请求会检索失败。异步只用于「运行中重建」。

---

## 四、检索缓存（性能）

### 问题
每次出题都调一次 `retrieve(direction)`：向量化（调 DashScope）+ 向量检索（调 Milvus），相同方向的重复调用纯属浪费。

### 方案
用本地 Caffeine 缓存 `retrieve` 的结果，key = 查询（方向）。

### 关键设计
- **只缓存非空结果**：空结果（无命中）和失败的 `""` 不缓存，避免把「临时性故障」固化成空结果、掩盖恢复
- **重建索引后必须清缓存**：知识库变了，旧缓存失效 → `doRebuild` 成功后 `retrievalCache.clearAll()`
- **依赖拆解避免循环依赖**：`RetrievalCache` 谁都不依赖，`KnowledgeService` 和 `RagIndexService` 都能注入它
  （如果让 `RagIndexService` 直接注入 `KnowledgeService` 去清缓存，就成环了）

---

## 五、Rerank 重排序（检索质量，提升最明显）

### 问题
向量检索（双塔）是「查询和文档各自算向量、比距离」，**快但不精确**，容易漏掉语义最相关的片段。

### 方案：两步检索
```
之前：查询 ──向量检索──► 直接取 top-3
现在：查询 ──向量检索──► 召回 top-20 ──rerank精排──► 取 top-3
```

| | 向量检索 | Rerank |
|---|---------|--------|
| 原理 | 查询/文档各自编码（双塔） | 查询+文档一起喂模型（交叉编码） |
| 优点 | 快，能扫百万级 | 准，能抓语义细节 |
| 缺点 | 不够精确 | 慢，只能处理少量 |

**企业标准打法：向量负责「广撒网」（召回），rerank 负责「精挑细选」（精排）。**

### 实现
- **LangChain4j 没有现成的 dashscope rerank 类**，需自己实现 `ScoringModel` 接口
- `QwenScoringModel implements ScoringModel`：调 DashScope 的 rerank API（`gte-rerank-v2`，标准端点）
  - 接口：`scoreAll(List<TextSegment>, String query) → Response<List<Double>>`
  - 返回的分数与入参**顺序一一对应**（接口按分数降序返回，用 `index` 还原顺序）
- `KnowledgeService.rerank`：三层保护
  1. 关了 rerank / 候选本来就不多 → 直接取前 topK
  2. rerank 调用失败 → 退回向量检索原始顺序
  3. 返回分数数量不匹配 → 退回原始顺序

### 配置
```yaml
rag:
  retrieve:
    top-k: 3            # 最终交给大模型的片段数
  rerank:
    enabled: true
    recall: 20          # 向量检索先召回多少候选
    model: gte-rerank-v2
    base-url: https://dashscope.aliyuncs.com/api/v1
```

---

## 六、文件清单

| 文件 | 作用 |
|------|------|
| `constant/PresetLevels.java` | 内置保底题 |
| `service/RetrievalCache.java` | 检索缓存（Caffeine） |
| `service/ai/QwenScoringModel.java` | Rerank 打分模型（实现 `ScoringModel`） |
| `service/RagIndexService.java` | 异步入库 + 重建后清缓存 |
| `service/KnowledgeService.java` | 召回 → rerank → 缓存 |
| `service/impl/LevelServiceImpl.java` | 三级兜底链 + 本地判分 |
| `sql/preset_level.sql` | 预设题库种子数据 |

## 七、验证要点

| 改造 | 怎么验证 |
|------|---------|
| AI 失败降级 | 把 api-key 改错 → 出题返回 `source="PRESET"`；日志有「AI 出题失败，降级到预设题库」 |
| 判分降级 | AI 故障下提交作答 → 报告 comment 带「本地判分」 |
| 异步入库 | `POST /rag/rebuild` 立即返回 → `GET /rag/rebuild/status` 轮询到 SUCCESS |
| 检索缓存 | 同一方向连调两次，第二次日志「检索缓存命中」（需开 debug） |
| Rerank | 改错 `rag.rerank.base-url` → 日志「Rerank 失败，退回向量检索原始顺序」 |
