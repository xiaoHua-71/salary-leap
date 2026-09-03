# RAG 检索增强出题改造

> 目标：让 AI 出题「照着知识库出」，而不是凭空编。核心是给生成关卡前加一步「检索」。

## 一句话理解 RAG

RAG = **Retrieval-Augmented Generation（检索增强生成）**。

以前模型出题是「脑子里想到什么出什么」；现在先从一个知识库里「查」出相关片段，再把片段塞进提示词，让模型「照着查到的内容出题」。题目的可信度、方向性都更可控。

---

## 嵌入模型是干什么的（最重要，先搞懂这个）

大模型（chat 模型）不认识「字」，只认识「数字」。所以要比较两段文字像不像，不能直接比文字，得先把它们都变成数字。

**嵌入模型（embedding model）就是把一段文字变成一串固定长度的数字（叫「向量」）。** 关键特性：

> **语义相近的文字，向量也挨得近。**

打个比方：把每段文字都变成地图上的一个「坐标」，意思相近的句子坐标也靠在一起。所谓「检索」，就是「找到离我的问题最近的几个坐标」。

所以嵌入模型是 RAG 检索的**地基**——没有它，就没法用「算距离」的方式找相关片段。

注意它和 chat 模型是两回事：

| | 嵌入模型 `QwenEmbeddingModel` | chat 模型（原来的出题模型） |
|---|---|---|
| 干什么 | 文字 → 向量（数字） | 文字 → 文字（生成题目） |
| 输入 | 一句话/一段话 | 提示词 |
| 输出 | 一串数字 | 一段 JSON |
| 本项目的用途 | 给知识库和查询做向量化，算相似度 | 生成关卡 / 报告 |

---

## 整体流程

```
【离线 · 应用启动时跑一次】RagConfig.embeddingStore()
  加载 3 个 txt 知识库
    → 切分（每段 ≤300 字，重叠 50 字）
    → 逐段向量化
    → 存进内存向量库

【在线 · 每次生成关卡时跑】LevelServiceImpl.generateLevel(salary, direction)
  ① knowledgeService.retrieve(direction)
       把 direction 也向量化 → 在库里算相似度 → 取最像的前 3 段 → 拼成一段文本
  ② levelAiService.generateLevel(salary, direction, knowledge)
       把检索到的知识塞进提示词 {{knowledge}}，模型照着它出题
  ③ 入库（多存了 direction 字段）
```

---

## 跟之前生成题目有什么区别

**之前**（`05-生成关卡模块`）：

```
generateLevel(salary)
  → levelAiService.generateLevel(salary)   // 模型凭空出题
  → 存库 → 返回
```

**现在**：

```java
LevelVO generateLevel(int salary, String direction) {
    String directionText = StrUtil.isBlank(direction) ? "Java后端开发" : direction;
    // 新增：先检索
    String knowledge = knowledgeService.retrieve(directionText);
    // 新增：把 knowledge 和 direction 一起传进模型
    LevelResult result = levelAiService.generateLevel(salary, directionText, knowledge);
    ...
}
```

区别就一句话：**多了一步「检索」，模型从「凭空出题」变成「照着知识出题」。** 方向不同 → 检索到的知识不同 → 题目不同（已验证）。

---

## 增加了哪些方法

### 新增文件

| 文件 | 方法 | 作用 |
|------|------|------|
| `config/RagConfig.java` | `qwenEmbeddingModel(apiKey)` | 创建嵌入模型 bean |
| `config/RagConfig.java` | `embeddingStore(embeddingModel)` | 启动时加载+切分+向量化+入库，返回内存向量库 |
| `config/RagConfig.java` | `readClasspath(path)` | 私有：读 classpath 下的 txt |
| `service/KnowledgeService.java` | `retrieve(query)` | 查询向量化 → 相似度检索 top-3 → 拼成文本返回 |

### 改动文件

| 文件 | 改动 |
|------|------|
| `service/ai/LevelAiService.java` | `generateLevel` 加 `direction`、`knowledge` 两个参数；提示词加 `{{direction}}` `{{knowledge}}` 模板变量 |
| `service/LevelService.java` | 接口 `generateLevel(int salary)` → `generateLevel(int salary, String direction)` |
| `service/impl/LevelServiceImpl.java` | 注入 `KnowledgeService`，先检索再出题，存 `direction` |
| `controller/LevelController.java` | `/generate` 加可选 `direction` 参数 |

---

## 使用了哪些 API（LangChain4j）

> 注意：用的是 1.4.0-beta10，嵌入模型类名是 `QwenEmbeddingModel`，不是旧教程里的 `DashScopeEmbeddingModel`。

| 类 | 关键方法 | 干什么 |
|----|---------|--------|
| `QwenEmbeddingModel` | `.builder().apiKey().modelName().build()` | 创建嵌入模型 |
| `QwenEmbeddingModel` | `.embed(String)` | 单个文本 → 向量 |
| `QwenEmbeddingModel` | `.embedAll(List<TextSegment>)` | 批量文本 → 向量列表 |
| `Document` | `Document.from(String)` | 把一段原始文本包成文档对象 |
| `DocumentSplitters` | `.recursive(300, 50)` | 创建切分器（300 字一段，重叠 50 字） |
| `DocumentSplitter` | `.split(Document)` | 文档 → 多个 `TextSegment` |
| `TextSegment` | `.text()` | 取切分后的片段原文 |
| `InMemoryEmbeddingStore` | `new InMemoryEmbeddingStore<>()` | 内存向量库（重启即丢） |
| `EmbeddingStore` | `.addAll(向量列表, 片段列表)` | 向量+原文成对入库 |
| `EmbeddingStore` | `.search(EmbeddingSearchRequest)` | 相似度检索 |
| `EmbeddingSearchRequest` | `.builder().queryEmbedding().maxResults().minScore().build()` | 构造检索请求 |
| `EmbeddingSearchResult` | `.matches()` | 取命中的结果列表 |
| `EmbeddingMatch` | `.embedded().text()` / `.score()` | 取命中片段原文 / 相似度分数 |

---

## 几个要点

1. **向量化必须用同一个模型**：知识库和查询都得用同一个 `QwenEmbeddingModel` 向量化，才能在同一个「空间」里比距离。换模型=换坐标系，距离就乱套了。

2. **`minScore` 是相似度门槛**：`minScore(0.2)` 表示低于 0.2 的片段直接丢弃。值越高越保守（宁缺毋滥），越低越宽松（可能捞到不相关的）。

3. **内存向量库不落盘**：每次重启都重新切分+向量化。数据小无所谓，生产要换 Redis / pgvector / Milvus 等真向量库。

4. **启动时就会发一次 embedding 请求**：`embeddingStore` 这个 bean 在启动时执行 `embedAll`，会真实调用一次 DashScope。所以只要应用能起来，就说明嵌入模型的 key 是通的。

---

## 接口

```
POST /api/level/generate?salary=10000&direction=Java后端开发
```

`direction` 可选，为空默认 `Java后端开发`。中文参数要用 URL 编码，或直接用 Knife4j（`/api/doc.html`）测试。
