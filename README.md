<div align="center">

# 🚀 salary-leap

### 程序员闯关加薪 —— 让 AI 给你出题、给你开薪

**注册送 10k 起薪，答对涨薪、答错降薪，凭实力登顶排行榜。**
题目、评分、点评全部由通义千问实时生成 —— 而且**优先基于 96 篇真实技术文档检索出题**，不瞎编。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.5-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.4.0--beta10-FF6F00?style=flat-square)](https://docs.langchain4j.dev/)
[![Milvus](https://img.shields.io/badge/Milvus-2.5.27-00A1EA?style=flat-square)](https://milvus.io/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=flat-square&logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](./LICENSE)

```
注册 → 起薪 10k → AI 出题（RAG 知识增强）→ 闯关作答 → AI 生成报告 → 薪资涨跌 → 排行榜
```

</div>

---

## 💡 为什么值得一看

| 它是什么 | 它解决了什么 |
|---------|------------|
| 🎮 **游戏化学习闭环** | 把「刷八股」变成一场薪资闯关，答得越准、薪资越高，有排行榜和成长记录 |
| 🧠 **AI 全链路驱动** | 出题、评分、点评、投递建议全由 `qwen3.7-max` 实时生成，题目永不重复 |
| 📚 **RAG 让 AI 有据可依** | 出题前先从 Milvus 检索真实技术文档，不编造；检索失败自动降级，主流程永不被拖垮 |
| 📊 **可量化的检索优化** | 30 条评估集 × 7 种检索变体，改完参数跑一次就知道「有没有变好」，不靠感觉 |

---

## ✨ 亮点速览

- 🎯 **三级兜底出题链** —— AI 生成 → 关卡池预置题 → 代码内置保底题，永远有题可出
- 🔍 **四步检索链路** —— 查询改写 → 混合召回（向量 + BM25）→ RRF 融合 → rerank 精排
- ⚡ **增量更新索引** —— 基于来源指纹差分，内容没变的文档一个 embedding 调用都不发
- 🎭 **幽默点评** —— 升职加薪 / 减薪 / 直接开除 / 公司给你，投递建议自动「谐音化名」规避真实公司
- 🏆 **实时排行榜** —— 按薪资降序，答题次数 + 平均分一页看尽
- 🔐 **安全加固** —— BCrypt 密码加密，Redis 分布式 Session，接口统一鉴权
- 📮 **双通道注册** —— 密码注册 / 邮箱验证码注册，策略模式优雅扩展
- 📚 **丝滑接口文档** —— Knife4j + SpringDoc 在线调试，`/api/doc.html` 一键直达

---

## 🔍 出题背后的检索链路

不是把方向丢给大模型就完事，这一层负责「把相关知识准确捞出来」：

```text
"Java后端开发"
  │
  ├─① 查询改写   LLM 把短标签扩展成 4 条具体主题查询（原查询始终排第一）
  │
  ├─② 混合召回   每条查询 × 2 路：
  │               · 向量检索 —— 查「语义相近」（Milvus，召回 20 条）
  │               · BM25   —— 查「字面命中关键词」（内存索引，召回 20 条）
  │
  ├─③ RRF 融合   score(d) = Σ 1/(60 + rank)，多路都靠前的片段自然浮上来 → 30 条候选
  │
  └─④ rerank 精排  用【原查询】打分，取 top 3；同一篇文章最多占 1 格（避免重叠片段刷屏）
```

**每一路失败都只降级、不中断** —— 检索是加分项，不是单点故障。

---

## 📊 改完怎么知道变好了？

肉眼比 top-3 来源不可靠，于是做了一套**离线评估**：30 条评估集 × 7 种变体，自动出对比表并与上次做差。

| 变体 | 量的是什么 |
|------|-----------|
| `all-on` | 生产基线，报告第一行就是线上真实水平 |
| `no-rewrite` / `no-hybrid` / `no-rerank` | 关掉某个环节，看它到底贡献了多少 |
| `vector-only` | 改造前的裸向量形态，量出整套优化总共值多少 |
| `no-diversity` / `topk-5` | 来源多样性是赚是亏 / 召回天花板在哪 |

指标：**Hit@K · MRR · Recall · Precision · Diversity · Latency p50/p95**，
未命中还会分类成「**不在库**」（补数据）/「**检索漏掉**」（调检索），一眼看出该往哪使劲。

---

## 🚀 快速开始

**环境**：JDK 21 · MySQL 8.0 · Redis 7 · Milvus 2.5.27（Docker）· 阿里云百炼 API Key

```bash
# 1. 建表
mysql -uroot -proot < sql/create_table.sql

# 2. 起向量库
docker compose up -d

# 3. 配置 API Key（Windows 用 setx 或 set）
export DASHSCOPE_API_KEY=sk-xxx

# 4. 启动（确保 JAVA_HOME 指向 JDK 21）
mvn spring-boot:run
```

> 首次启动会自动抓取网页 + 建索引；之后再启动复用已有集合，秒级启动。

| 资源 | 地址 |
|------|------|
| 服务端口 | `http://localhost:8101/api` |
| 接口文档 | `http://localhost:8101/api/doc.html` |

---

## 🔌 接口速查

| 模块 | 代表接口 |
|------|---------|
| 登录注册 | `POST /user/register` · `POST /user/login` · `GET /user/current` |
| 闯关 | `POST /level/generate?salary=10000&direction=Java后端开发` · `POST /level/submit` |
| 排行 & 记录 | `GET /rank` · `GET /record/summary` · `GET /record/list` |
| 知识库 | `POST /rag/rebuild`（增量）· `GET /rag/rebuild/status` |
| 离线评估 | `POST /rag/eval` · `GET /rag/eval/report/latest` |

> 统一响应 `{ "code": 0, "data": ..., "message": "ok" }`；所有 `Long` id 序列化为字符串防精度丢失。
> 完整文档见 [`frontDoc/README.md`](frontDoc/README.md)。

---

## 🧰 技术栈

`Spring Boot 3.5` · `Java 21` · `MyBatis-Plus` · `MySQL` · `Redis`
`LangChain4j 1.4` · `通义千问 qwen3.7-max` · `text-embedding-v2` · `gte-rerank-v2`
`Milvus 2.5` · `BM25` · `Caffeine` · `Jsoup` · `Knife4j` · `Hutool`

---

## 📁 目录一览

```
salary-leap
├── knowledge/     # 27 篇开发沉淀文档（模块演进全过程，从 0 到 1）
├── frontDoc/      # 前端接口文档
├── docs/          # 网页正文缓存 + 评估集与评估报告
├── sql/           # 建表脚本 + 预置题库
└── src/           # 源码（service/ 下含 RAG 检索、索引、评估三大子系统）
```

---

## 🗺️ 路线图

**业务**：~~登录注册~~ · ~~AI 生成关卡~~ · ~~作答报告 + 薪资更新~~ · ~~排行榜~~ · ~~答题记录~~ · ~~三级兜底~~
→ 🚧 关卡取登录用户薪资 · 报告流式输出 · 方向 + 难度组合筛选

**RAG**：~~向量检索~~ · ~~迁移 Milvus~~ · ~~降级链~~ · ~~混合检索~~ · ~~查询改写~~ · ~~来源多样性~~ · ~~增量更新~~ · ~~评估集与指标~~
→ 🚧 定时任务自动化重建索引

---

<div align="center">

**如果这个项目对你有启发，点个 ⭐ 就是最好的支持**

祝你闯关顺利，薪资翻倍 🚀

</div>
