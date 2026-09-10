# Milvus 原生语句 vs LangChain4j（对照 MySQL / MyBatis-Plus）

> 一句话问题：**Milvus 也是"数据库"，那它的 DDL / DML / DCL 长什么样？本项目用 LangChain4j 又把它包成了什么样？**
>
> 类比：MySQL 你写 `SELECT ...`，MyBatis-Plus 帮你 `QueryWrapper`；Milvus 你用 REST/CLI 发原生请求，LangChain4j 帮你 `store.search()`。本文把两边一一对起来。

---

## 一、先纠正一个直觉：Milvus 没有 SQL

MySQL 里你写 SQL 字符串，数据库有「SQL 解析器」把它翻译成执行计划。**Milvus 没有 SQL 方言**（也就没法 `SELECT * FROM collection`）。它的原生接口是：

| 入口 | 形态 | 谁在用 | 类比 MySQL |
|------|------|--------|-----------|
| **RESTful API v2** | HTTP + JSON | curl / Postman / 各种语言 | JDBC 协议 |
| **gRPC SDK** | 二进制 RPC | `milvus-sdk-java`（本项目依赖它） | MySQL Connector/J |
| **pymilvus** | Python 库 | Python 生态、调试脚本 | PyMySQL |
| **milvus_cli** | 命令行 | 人工运维 | `mysql` 客户端 |
| **Attu** | Web GUI | 人工看数据 | Navicat / DataGrip |

但它们**照样有 DDL / DML / DCL 这三类操作**，只是「语句」变成 HTTP 请求或 SDK 方法调用。

> 本项目的语言是 Java，走的是 **gRPC SDK**：`io.milvus.client.MilvusServiceClient`（milvus-sdk-java **2.5.9**，即「v1 老 SDK」）。这一点很关键，见第七节的版本坑。

---

## 二、概念对照：MySQL 的表 → Milvus 的集合

| MySQL | Milvus | 说明 |
|-------|--------|------|
| database | **database** | Milvus 也支持多库，默认 `_default` |
| table | **collection**（集合） | 一个集合 ≈ 一张表 |
| row | **entity**（实体） | 一条记录 |
| column | **field**（字段） | 建集合时定 schema |
| INT PRIMARY KEY AUTO_INCREMENT | `id` 字段（`Int64` / `VarChar`，可 `autoID`） | 主键，必须显式声明哪个字段是主键 |
| 普通列 | 标量字段：`Int64/VarChar/Bool/JSON/Float/Double/Array...` | `VarChar` 要写 `max_length` |
| —— | **vector field**（向量字段） | MySQL 没有的，`FLOAT_VECTOR` / `BINARY_VECTOR` / `SPARSE_FLOAT_VECTOR` |
| CREATE INDEX | **vector index**（`FLAT/HNSW/IVF_FLAT...`） | 索引是给「向量字段」建的，用于加速相似度检索 |
| `SELECT ... WHERE` | `query(expr="...")` | 标量过滤，语法见第五节 |
| —— | **`search(vector)`** | 向量检索，Milvus 特有动词，MySQL 完全没有 |

`VarChar` 的 `max_length` 是硬约束（超了 insert 直接报错）。本项目 LangChain4j 默认给：
- `id` → `VarChar`，`max_length = 36`（放 UUID）
- `text` → `VarChar`，`max_length = 65535`

---

## 三、DDL：建库 / 建表 / 建索引

### 3.1 对照表

| 目的 | MySQL SQL | Milvus 原生 REST v2 | Milvus gRPC SDK（Java） | 本项目 LangChain4j |
|------|-----------|--------------------|------------------------|-------------------|
| 建集合 | `CREATE TABLE t (...)` | `POST /v2/vectordb/collections/create` | `createCollection(param)` | **builder 自动建**（不显式写） |
| 建索引 | `CREATE INDEX idx ON t(col)` | `POST /v2/vectordb/indexes/create` | `createIndex(param)` | **builder 自动建** |
| 加载到内存 | ——（MySQL 无此概念） | `POST /v2/vectordb/collections/load` | `loadCollection(param)` | **builder 自动 load** |
| 列出集合 | `SHOW TABLES` | `POST /v2/vectordb/collections/list` | `showCollections()` | 无（用原生） |
| 看结构 | `DESC t` | `POST /v2/vectordb/collections/describe` | `describeCollection(...)` | 无（用原生） |
| 行数统计 | `SELECT COUNT(*)` | `POST /v2/vectordb/collections/get_stats` | `getCollectionStatistics(...)` | **⚠ 本项目用原生**（见 3.4） |
| 删集合 | `DROP TABLE t` | `POST /v2/vectordb/collections/drop` | `dropCollection(param)` | 无（本项目刻意不用） |

> **「load」是 Milvus 独有概念**：集合数据平时躺在磁盘，检索前必须 `load` 进内存，否则报 `collection not loaded`。MySQL 没有这一步。

### 3.2 原生 REST 建集合（curl）

最简「quick setup」——只给名字和维度：

```bash
curl -X POST "http://localhost:19530/v2/vectordb/collections/create" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "level_knowledge",
    "dimension": 1536,
    "metricType": "IP"
  }'
```

要精细控制 schema（多个字段）就传 `schema` 数组，等价于写全 `CREATE TABLE` 的列定义。

### 3.3 建索引 + load

```bash
# 建索引（FLAT = 暴力检索，不建索引也可，但 load 前一般要有）
curl -X POST "http://localhost:19530/v2/vectordb/indexes/create" \
  -H "Content-Type: application/json" \
  -d '{ "collectionName": "level_knowledge",
        "indexParams": [{ "fieldName": "vector",
                          "indexName": "vector_idx",
                          "metricType": "IP",
                          "indexType": "FLAT" }] }'

# 加载进内存
curl -X POST "http://localhost:19530/v2/vectordb/collections/load" \
  -H "Content-Type: application/json" \
  -d '{ "collectionName": "level_knowledge" }'
```

### 3.4 本项目的做法：DDL 全部藏进 builder

`RagIndexService.java:110-121`：

```java
return MilvusEmbeddingStore.builder()
        .host(host).port(port)
        .collectionName(collectionName)
        .dimension(dimension)
        .metricType(MetricType.IP)     // 度量：内积
        .indexType(IndexType.FLAT)     // 索引：暴力检索
        .build();                       // ← 这一刻，LangChain4j 内部把上面 3 条 DDL 全做了
```

`.build()` 里 LangChain4j 干了三件事（对应三个原生调用）：
1. `hasCollection` 查集合在不在 → 不在就 `createCollection`（按固定 4 字段 schema）
2. `createIndex`（用你给的 `indexType` + `metricType`）
3. `loadCollection`（并轮询等待加载完成）

**所以本项目代码里看不到任何 DDL 语句**——不是不需要，是框架替你写了。

但有一处**绕开框架、直接用原生 SDK**，`RagIndexService.java:142-161`：

```java
MilvusServiceClient client = new MilvusServiceClient(
        ConnectParam.newBuilder().withHost(host).withPort(port).build());
R<GetCollectionStatisticsResponse> resp = client.getCollectionStatistics(
        GetCollectionStatisticsParam.newBuilder()
                .withCollectionName(collectionName).build());
// 从 statsList 里取 row_count
```

这是**「DDL 里的统计查询」**：LangChain4j 的 `EmbeddingStore` 接口没有「查行数」方法，所以只能自己 new 一个原生客户端来问。**这就是「框架盖不住时，回退到原生 API」的典型场景。**

---

## 四、DML：增 / 删 / 改 / 查

### 4.1 对照表

| 目的 | MySQL SQL | Milvus 原生 REST v2 | Milvus gRPC SDK（Java） | 本项目 LangChain4j |
|------|-----------|--------------------|------------------------|-------------------|
| 插入 | `INSERT INTO t VALUES (...)` | `POST /v2/vectordb/entities/insert` | `insert(param)` | `store.add()` / `store.addAll()` |
| 更新 | `UPDATE t SET ... WHERE ...` | **没有 UPDATE！** 用 upsert（主键存在则覆盖） | `upsert(param)` | `store.add(id, embedding, segment)`（同 id 覆盖） |
| 删除 | `DELETE FROM t WHERE ...` | `POST /v2/vectordb/entities/delete` | `delete(param)` | `store.remove(id)` / `removeAll(ids)` |
| **向量检索** | **无对应** | `POST /v2/vectordb/entities/search` | `search(param)` | `store.search(EmbeddingSearchRequest)` |
| 标量查询 | `SELECT ... WHERE` | `POST /v2/vectordb/entities/query` | `query(param)` | 只能通过 `search` 的 `filter` 间接用 |
| 按主键取 | `SELECT ... WHERE id IN (...)` | `POST /v2/vectordb/entities/get` | `get(param)` | 无（用原生） |

**关键差异：Milvus 没有 `UPDATE`。** 想改一条，就用同主键 `upsert`（存在即覆盖，不存在即插入）。也没有 `JOIN`、没有 `GROUP BY`、没有 `ORDER BY`（排序只能靠相似度或主键）。

### 4.2 原生插入（项目实际的数据形状）

LangChain4j 建集合时固定了 4 个字段，所以原生插入长这样：

```bash
curl -X POST "http://localhost:19530/v2/vectordb/entities/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "level_knowledge_1788959387323",
    "data": [{
      "id": "a1b2c3d4-0000-0000-0000-000000000001",
      "vector": [0.012, -0.34, 0.88, ...],       // 1536 个 float
      "text": "Redis 缓存穿透：查询一个不存在的 key...",
      "metadata": { "source": "java-backend.txt" }
    }]
  }'
```

对应本项目 `RagIndexService.java:123-127`：

```java
private void ingest(MilvusEmbeddingStore store) {
    List<TextSegment> segments = loadAndSplit();
    store.addAll(embeddingModel.embedAll(segments).content(), segments);
    //  ↑ 框架内部就是上面那个 insert 请求；vector 来自 embedAll，text 来自 TextSegment
}
```

**为什么是 4 个字段？** 这是 `MilvusEmbeddingStore` 的**固定默认 schema**（可用 builder 改名，但结构不变）：

| 字段名 | 类型 | 对应 SQL 里的 | 装什么 |
|--------|------|--------------|--------|
| `id` | `VarChar(36)` | 主键 | UUID |
| `vector` | `FloatVector(1536)` | —— | 向量 |
| `text` | `VarChar(65535)` | 文本列 | `TextSegment` 的原文 |
| `metadata` | `JSON` | JSON 列 | `Metadata` 键值对 |

> 这就是第一节说的：**框架给你定好了表结构**，你只能往里塞这 4 样。想加自定义字段？只能不用 LangChain4j 的 store，自己写原生 DDL + insert。

### 4.3 原生向量检索 vs `store.search()`

原生 REST：

```bash
curl -X POST "http://localhost:19530/v2/vectordb/entities/search" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "level_knowledge_1788959387323",
    "data": [[0.012, -0.34, 0.88, ...]],      // 查询向量
    "annsField": "vector",
    "limit": 3,
    "outputFields": ["text"],                  // 只回传原文，不回传向量（省流量）
    "searchParams": { "metricType": "IP" }
  }'
```

本项目的 `KnowledgeService.java:42-48`：

```java
EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(3)          // → 原生 limit / topK
        .minScore(0.2)          // → 框架在客户端过滤
        .build();
EmbeddingStore<TextSegment> store = ragIndexService.getEmbeddingStore();
EmbeddingSearchResult<TextSegment> result = store.search(request);
```

参数映射：

| LangChain4j | Milvus 原生 | 说明 |
|-------------|------------|------|
| `queryEmbedding` | `data` | 查询向量 |
| `maxResults(3)` | `limit: 3`（SDK 里叫 `topK`） | 取最像的前 3 条 |
| `minScore(0.2)` | **无直接对应** | Milvus 原生用 `radius`/`range search` 表达「距离阈值」；LangChain4j 是**拿到结果后在 Java 里按 score 过滤** |
| `filter(...)` | `expr: "..."` | 元数据过滤，由 `MilvusMetadataFilterMapper` 翻译成 Milvus 表达式 |
| —— | `outputFields: ["text"]` | 框架默认只取 `text`（`retrieveEmbeddingsOnSearch` 控制是否连向量一起取） |

> `minScore` 的语义随度量类型变：`IP`/`COSINE` 是「越大越像」（阈值是下限），`L2` 是「越小越像」（阈值是上限）。本项目用 `IP`，所以 `minScore(0.2)` = 「相似度必须 ≥ 0.2」。

### 4.4 标量查询（query）—— 框架的短板

原生 `query` 是可以写「WHERE」的：

```bash
curl -X POST "http://localhost:19530/v2/vectordb/entities/query" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "level_knowledge_1788959387323",
    "filter": "metadata[\"source\"] == \"java-backend.txt\"",
    "outputFields": ["text"]
  }'
```

LangChain4j 的 `EmbeddingStore` 接口**没有暴露独立的 query 方法**——它只把「过滤」作为 `search` 的一个可选参数。所以「不按向量、纯按条件查数据」这件事，框架做不了，得回退原生。

---

## 五、Milvus 的「WHERE」：表达式语言（expr）

Milvus 的过滤条件叫 **expr**，语法比 SQL 精简，但足够用：

| 场景 | 写法 |
|------|------|
| 相等 | `id == "abc"` |
| 不等 | `status != "deleted"` |
| 数值比较 | `score > 0.9` |
| 集合包含 | `id in ["a", "b", "c"]` |
| 模糊匹配（前缀） | `text like "Redis%"` |
| JSON 取字段 | `metadata["source"] == "java-backend.txt"` |
| 逻辑组合 | `a > 1 && (b == "x" \|\| c == "y")` |
| 数组包含 | `ARRAY_CONTAINS(tags, "java")` |
| 判空 | `EXISTS field_name` |

注意：**逻辑与是 `&&`、或是 `\|\|`**（不是 SQL 的 `AND`/`OR`），取 JSON 用下标 `metadata["k"]`。

---

## 六、DCL：用户 / 角色 / 权限

MySQL 的 `CREATE USER` / `GRANT` / `REVOKE`，Milvus 叫 **RBAC**（角色制，不直接给用户授权，而是用户→角色→权限）：

| 目的 | MySQL | Milvus 原生 REST v2 |
|------|-------|-------------------|
| 建用户 | `CREATE USER 'u'@'%' IDENTIFIED BY 'p'` | `POST /v2/vectordb/users/create` |
| 建角色 | `CREATE ROLE r` | `POST /v2/vectordb/roles/create` |
| 用户绑角色 | `GRANT r TO 'u'@'%'` | `POST /v2/vectordb/users/grant_role` |
| 角色授权限 | `GRANT SELECT ON db.* TO r` | `POST /v2/vectordb/roles/grant_privilege_v2` |
| 撤销 | `REVOKE ...` | `/users/revoke_role`、`/roles/revoke_privilege_v2` |

Milvus 的权限是「动词级」的，常见几个：`CreateCollection`、`DropCollection`、`Insert`、`Delete`、`Search`、`Query`、`Load`、`Flush`。

**本项目没配 DCL**——`application.yml:109-113` 只填了 host/port：

```yaml
milvus:
  host: localhost
  port: 19530
  collection-name: level_knowledge
  dimension: 1536
```

默认无鉴权。生产环境应改用 `MilvusEmbeddingStore` builder 的 `username()` / `password()` + 开启 Milvus 的 `authorizationEnabled`。

---

## 七、版本坑：LangChain4j 有两个 Milvus 模块

这是最容易踩的坑，单独说：

| 模块 | 底层 SDK | 项目里长什么样 |
|------|---------|--------------|
| `langchain4j-milvus`（**本项目用的**，老） | `milvus-sdk-java` **v1**，`io.milvus.client.MilvusServiceClient` | `RagIndexService` 里 import 的就是这个 |
| `langchain4j-milvus-v2`（新，官方推荐） | Milvus Java SDK **v2**，`io.milvus.v2.client.MilvusClientV2` | 类名 `MilvusV2EmbeddingStore`，支持 sparse/hybrid 检索 |

- 本项目 = 老模块 + SDK **2.5.9** + 服务端 Milvus **2.5.27**（版本必须对上，见 `15-Milvus向量库企业级改造.md` 踩坑 4）。
- 老模块 `MilvusEmbeddingStore` **不支持稀疏向量 / 混合检索**（只能稠密向量搜索）。
- 两者 API 名字很像（都叫 `Builder`），换模块时别只看 import。

---

## 八、同一件事，三种写法（建集合 → 插一条 → 搜一条）

**① 原生 REST（curl）**

```bash
# DDL
curl -X POST localhost:19530/v2/vectordb/collections/create \
  -d '{"collectionName":"demo","dimension":1536,"metricType":"IP"}'
curl -X POST localhost:19530/v2/vectordb/collections/load \
  -d '{"collectionName":"demo"}'
# DML
curl -X POST localhost:19530/v2/vectordb/entities/insert \
  -d '{"collectionName":"demo","data":[{"id":"1","vector":[...],"text":"hi","metadata":{}}]}'
curl -X POST localhost:19530/v2/vectordb/entities/search \
  -d '{"collectionName":"demo","data":[[...]],"limit":3,"outputFields":["text"]}'
```

**② pymilvus（Python，调试最方便）**

```python
from pymilvus import MilvusClient
c = MilvusClient(uri="http://localhost:19530")
c.create_collection("demo", dimension=1536, metric_type="IP")      # DDL
c.insert("demo", [{"id": "1", "vector": [...], "text": "hi"}])     # DML
c.search("demo", data=[[...]], limit=3, output_fields=["text"])    # DML
```

**③ LangChain4j（本项目）**

```java
// DDL：全在 build() 里自动完成
MilvusEmbeddingStore store = MilvusEmbeddingStore.builder()
        .host("localhost").port(19530)
        .collectionName("demo").dimension(1536)
        .metricType(MetricType.IP).indexType(IndexType.FLAT).build();
// DML
store.addAll(embeddingModel.embedAll(segments).content(), segments);
EmbeddingSearchResult<TextSegment> r = store.search(
        EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed("Java后端开发").content())
                .maxResults(3).minScore(0.2).build());
```

对照结论：**框架把 DDL + insert + search 三件事都简化成了一行 Java**，代价是你失去了对 schema、索引参数、标量查询的完全控制。

---

## 九、框架盖不住时怎么办：三层选择

| 需求 | 用哪一层 | 本项目实例 |
|------|---------|-----------|
| 存/取/搜向量、按 id 删 | **LangChain4j** | `store.addAll()` / `store.search()` |
| 改名/改 schema/加字段 | 原生 DDL（`MilvusServiceClient`） | 未用，改成「新集合名」规避 |
| 集合统计、load 状态、标量查询 | **原生 SDK** | `countRows()` 用 `getCollectionStatistics` |
| 人工看数据、临时排查 | **Attu / milvus_cli** | 未用 |

**判据**：`EmbeddingStore` 接口有的（add/search/remove）就用框架；接口没有的（统计、schema、query）就 new 一个 `MilvusServiceClient` 走原生。本项目 `RagIndexService` 两种都用了，是最好的例子。

---

## 十、速查：本项目调用点一览

| 操作 | 类别 | 代码位置 | 走的层 |
|------|------|---------|--------|
| 建集合 + 索引 + load | DDL | `RagIndexService.java:110-121` | LangChain4j 自动 |
| 查行数（判空） | DDL | `RagIndexService.java:142-161` | **原生 SDK** |
| 入库 | DML-insert | `RagIndexService.java:123-127` | LangChain4j `addAll` |
| 向量检索 | DML-search | `KnowledgeService.java:42-48` | LangChain4j `search` |
| 切集合引用（类 rebuild） | 应用层 | `RagIndexService.java:95-104` | 纯 Java |
| 建索引接口 | DDL 触发 | `RagIndexController.java:28-37` | LangChain4j 自动 |

---

## 一句话总结

> **Milvus 没有 SQL，但有 DDL/DML/DCL 三类操作，入口是 REST v2 / gRPC SDK / CLI / GUI。**
> **LangChain4j 的 `MilvusEmbeddingStore` = 一个「固定 4 字段 schema + 自动建索引 + add/search/remove」的高级封装**，相当于 MyBatis-Plus 之于 MySQL——常用操作一行搞定，但想改表结构、写复杂过滤、查统计时，就得绕过它用原生 `MilvusServiceClient`（本项目 `countRows` 就是这么干的）。

---

参考：`15-Milvus向量库企业级改造.md`（部署与踩坑）、`16-向量查询流程解析.md`（检索链路逐行）。
