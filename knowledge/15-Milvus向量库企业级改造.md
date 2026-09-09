# Milvus 向量库企业级改造

> 把 RAG 的向量存储从「内存版 InMemoryEmbeddingStore」升级到「落盘的 Milvus 向量库」，并解决企业级要面对的三个问题：**数据不丢、幂等入库、可重建索引**。

## 一、为什么换 Milvus

`14-RAG检索增强出题改造` 用的是 `InMemoryEmbeddingStore`：

- 向量存在 JVM 内存里，**重启即丢**
- 每次启动都要重新切分 + 向量化（浪费 embedding 调用）
- 知识量大、并发高时撑不住

企业级需要**落盘的向量数据库**。三个主流选型：pgvector（公司已有 Postgres 顺手加）、Milvus（专用向量库、国内 AI 岗最常点名）、Elasticsearch（已有 ES 顺手加）。本项目选 **Milvus**，因为它最「像专门的向量数据库」，学习含金量最高。

## 二、依赖与版本（关键）

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-milvus</artifactId>
    <version>1.4.0-beta10</version>
</dependency>
```

**版本对应关系（踩坑重灾区）：**

| 组件 | 版本 |
|------|------|
| langchain4j-milvus | 1.4.0-beta10 |
| 自带 milvus-sdk-java | **2.5.9** |
| 服务端 Milvus | **2.5.27**（Docker） |

> 铁律：**服务端版本必须和 SDK 版本对上**。之前用 Milvus Lite v2.2.16 配 SDK 2.5.9，`loadCollection` 直接崩（见踩坑 4）。

## 三、配置（application.yml）

```yaml
milvus:
  host: localhost
  port: 19530
  collection-name: level_knowledge   # 集合名前缀，实际会拼时间戳后缀
  dimension: 1536                     # text-embedding-v2 固定 1536 维
```

## 四、核心 API

| 类 | 关键方法 | 作用 |
|----|---------|------|
| `QwenEmbeddingModel` | `.builder().apiKey().modelName("text-embedding-v2")` | 向量化模型 |
| `MilvusEmbeddingStore` | `.builder().host().port().collectionName().dimension().metricType().indexType()` | 建 store，集合不存在自动建 |
| `MilvusEmbeddingStore` | `addAll(向量列表, 片段列表)` | 入库 |
| `EmbeddingStore.search(EmbeddingSearchRequest)` | 检索 top-K | 相似度检索 |
| `MilvusServiceClient` | `getCollectionStatistics(...)` | 查集合 row_count（判空用） |

**维度 1536 是硬约束**：`text-embedding-v2` 固定输出 1536 维，建集合的 `dimension` 必须一致，否则 insert 报 dimension mismatch。

## 五、关键设计（三个）

### 1. 幂等入库：连接和入库分离

Milvus 落盘，重启后数据还在。所以**不能每次启动都入库**（会重复写）。做法：

- `RagConfig` 只建 embedding 模型 bean
- `RagIndexService.init()` 启动时先查集合 `row_count`，**> 0 就跳过**，= 0 才入库

### 2. rebuild 用「新集合名 + 切引用」，不 drop 同名

改了知识库要刷新向量。最初的方案是「drop 旧集合 → 重建同名集合」，结果在 Milvus 上不稳定（踩坑 5）。

最终方案：**每次 rebuild 新建一个带时间戳的集合**，入库后把 store 引用切过去：

```
rebuild():
  新集合名 = level_knowledge_<时间戳>
  → buildStore(新名)   // 自动建集合+索引+load
  → ingest(新集合)
  → 切引用（volatile 保证可见）
```

旧集合保留当「历史快照」，不做同名 drop，彻底绕开崩溃点。

### 3. 当前集合名持久化到本地文件

集合名是动态的，重启后要能找回上次用的集合（否则又重复入库）。把当前集合名写进 `milvus-current-collection.txt`（项目根目录）：

- 启动：读文件 → 有则复用，无则新建
- rebuild：写新名

## 六、踩坑记录（最值钱的部分）

1. **度量类型 COSINE 不支持**（Milvus Lite 2.2.x 只支持 L2/IP）
   → 改用 `.metricType(IP)`。因为 text-embedding-v2 向量已归一化，IP 等价余弦相似度。新版 Milvus 2.5 其实支持 COSINE，但 IP 已经够用，不必改回。

2. **维度必须对**：`dimension` 填错会 insert 失败。

3. **改了 schema 不会自动重建**：Milvus 集合一旦建好就持久化，改了 dimension/metric 要手动清集合（删数据目录或 drop），否则报 `index doesn't exist` / `dimension mismatch`。

4. **版本不匹配会崩**（最大的坑）：`milvus-sdk-java 2.5.9` 连 `Milvus Lite 2.2.16`，调 `loadCollection` 轮询 `showCollections` 时老 Lite 直接崩，报 `UNAVAILABLE: Keepalive failed. The connection is likely gone`。**解法：服务端换 Docker Milvus 2.5.27**。

5. **同名 drop + 重建容易崩**：删一个已 load 的集合 + 立刻重建同名，在 Lite 上会崩（且 drop 是异步清理）。**解法：新集合名方案（见设计 2）**。

6. **中文 URL 要编码**：curl 发 `direction=Java后端开发` 会 400，用 `--data-urlencode` 或 Knife4j。

## 七、Docker 部署

`docker-compose.yml`（三容器：etcd + minio + standalone）：

```bash
docker compose up -d   # 起
docker compose ps      # 看状态
```

镜像：`milvusdb/milvus:v2.5.27`、`quay.io/coreos/etcd:v3.5.18`、`minio/minio:RELEASE.2024-05-28...`。端口 19530。

> 国内拉镜像慢时，去 Docker Desktop → Settings → Docker Engine 配 registry-mirrors 加速器。

## 八、文件清单

| 文件 | 作用 |
|------|------|
| `config/RagConfig.java` | embedding 模型 bean |
| `service/RagIndexService.java` | 向量库生命周期：建库、首次入库、rebuild |
| `service/KnowledgeService.java` | 检索（从 RagIndexService 取 store） |
| `controller/RagIndexController.java` | `POST /rag/rebuild` 重建接口 |
| `docker-compose.yml` | Milvus 部署 |
| `resources/knowledge/*.txt` | 知识库文档 |

## 九、接口

```
POST /api/rag/rebuild          # 重建索引（改知识库后调用）
POST /api/level/generate?salary=10000&direction=Java后端开发   # RAG 出题
```
