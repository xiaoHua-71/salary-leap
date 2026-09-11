-- 预设题库种子数据（AI 失败时的兜底题）
-- 说明：source='PRESET' 标识人工预设题；direction='通用' 是兜底池，任何标签查不到时用它
USE `salary_leap`;

INSERT INTO `level` (`levelName`, `levelDesc`, `options`, `difficulty`, `targetSalary`, `direction`, `priority`, `source`, `standardAnswer`)
VALUES
-- ========== Java 后端开发 ==========
('缓存穿透保卫战',
 '你负责的电商详情页接口被恶意刷量，大量不存在的商品 ID 请求直接穿透 Redis 打到 MySQL，数据库 CPU 飙升。请给出一套防护方案。',
 '[{"optionName":"用布隆过滤器提前拦截不存在的 key","trueAnswer":true},{"optionName":"对不存在的 key 缓存空值并设置较短过期时间","trueAnswer":true},{"optionName":"用互斥锁保证同一时刻只有一个请求回源数据库","trueAnswer":false},{"optionName":"给缓存 key 设置随机过期时间","trueAnswer":false},{"optionName":"引入多级缓存（本地加 Redis）","trueAnswer":false},{"optionName":"用消息队列异步落库","trueAnswer":false},{"optionName":"给查询字段加联合索引","trueAnswer":false},{"optionName":"对接口并发数做限流","trueAnswer":false}]',
 '中等', 15000, 'Java后端开发', 999, 'PRESET',
 '缓存穿透的核心是查询不存在的数据。主流方案：一是布隆过滤器，把存在的 key 放进位图，不存在的直接拦截；二是缓存空值，把 null 也缓存一小段时间。互斥锁和随机过期时间分别是缓存击穿、缓存雪崩的解法，不要混淆。'),

('订单系统削峰',
 '秒杀活动瞬时流量是平时的 50 倍，直接写库会打垮订单库。架构评审上，你需要说明引入消息队列能解决什么问题、不能解决什么。',
 '[{"optionName":"削峰填谷，把瞬时流量缓冲成平稳消费","trueAnswer":true},{"optionName":"业务解耦，订单系统和下游系统通过 MQ 通信","trueAnswer":true},{"optionName":"异步处理非核心链路，缩短接口响应时间","trueAnswer":true},{"optionName":"保证跨服务强一致性事务","trueAnswer":false},{"optionName":"降低系统整体复杂度","trueAnswer":false},{"optionName":"替代数据库做持久化存储","trueAnswer":false},{"optionName":"彻底消除消息丢失的可能","trueAnswer":false}]',
 '中等', 18000, 'Java后端开发', 999, 'PRESET',
 '消息队列的三大作用是削峰、解耦、异步。它不保证强一致（那是分布式事务范畴），反而因异步引入了新的复杂度（消息丢失、重复、顺序问题），也不能替代数据库做持久化。'),

-- ========== 前端开发 ==========
('响应式原理拷问',
 '面试官让你手写一个 mini 的响应式系统，并解释 Vue3 为什么用 Proxy 替换了 Vue2 的 Object.defineProperty。',
 '[{"optionName":"用 Proxy 拦截对象的 get/set，读取时收集依赖、修改时派发更新","trueAnswer":true},{"optionName":"Proxy 能监听对象新增属性和数组索引的变化","trueAnswer":true},{"optionName":"Object.defineProperty 无法监听数组下标赋值","trueAnswer":true},{"optionName":"Proxy 的性能一定比 Object.defineProperty 高","trueAnswer":false},{"optionName":"Proxy 可以做到零成本的全量响应式","trueAnswer":false},{"optionName":"响应式依赖收集只在首次渲染时发生一次","trueAnswer":false}]',
 '中等', 15000, '前端开发', 999, 'PRESET',
 'Vue3 用 Proxy 重新实现了响应式，核心是 get 时收集依赖（track）、set 时触发更新（trigger）。相比 defineProperty，Proxy 的优势是能拦截新增属性和数组索引变化，不需要 Vue2 那样专门重写数组方法。'),

('首屏加载慢的锅',
 '后台管理系统首屏加载 5 秒，产品经理要你优化到 2 秒内。请列出你会动手的地方。',
 '[{"optionName":"路由懒加载加代码分割，按需加载 chunk","trueAnswer":true},{"optionName":"图片懒加载，进入视口再加载","trueAnswer":true},{"optionName":"静态资源上 CDN","trueAnswer":true},{"optionName":"用防抖节流优化高频事件","trueAnswer":true},{"optionName":"把所有组件改成同步 import 减少请求数","trueAnswer":false},{"optionName":"关闭 gzip 压缩以降低 CPU 开销","trueAnswer":false},{"optionName":"用 Web Worker 处理所有 DOM 渲染","trueAnswer":false}]',
 '简单', 12000, '前端开发', 999, 'PRESET',
 '首屏优化主要抓：路由懒加载、代码分割、图片懒加载、CDN、资源压缩（gzip/brotli）、防抖节流。注意 Web Worker 不能操作 DOM，同步 import 反而增大首包体积。'),

-- ========== Go 开发 ==========
('Go 并发初探',
 '你需要用 Go 并发抓取 100 个页面并把结果汇总，请讲清楚 goroutine、channel、WaitGroup 各自的作用。',
 '[{"optionName":"goroutine 是 Go 运行时调度的轻量级线程","trueAnswer":true},{"optionName":"channel 用于 goroutine 之间安全地传递数据","trueAnswer":true},{"optionName":"sync.WaitGroup 用于等待一组 goroutine 完成","trueAnswer":true},{"optionName":"goroutine 和操作系统线程一一对应","trueAnswer":false},{"optionName":"channel 读写默认是非阻塞的","trueAnswer":false},{"optionName":"goroutine 之间可以直接共享内存而不需要同步","trueAnswer":false}]',
 '中等', 18000, 'Go开发', 999, 'PRESET',
 'goroutine 是用户态调度的协程，多个 goroutine 复用少量 OS 线程（GMP 模型）。channel 是类型安全的通信管道，默认阻塞。共享内存并发访问需要 mutex 或 channel 同步，否则有 data race。'),

('Channel 的坑',
 '团队 code review 时发现一段用 channel 做同步的代码偶发死锁，请指出可能的原因和正确写法。',
 '[{"optionName":"向无缓冲 channel 发送数据时若无人接收会阻塞","trueAnswer":true},{"optionName":"向已关闭的 channel 发送数据会 panic","trueAnswer":true},{"optionName":"用 select 加 timeout 可以避免永久阻塞","trueAnswer":true},{"optionName":"从已关闭的 channel 接收会 panic","trueAnswer":false},{"optionName":"channel 可以无限缓冲，不会阻塞","trueAnswer":false},{"optionName":"nil channel 的收发操作会立即返回","trueAnswer":false}]',
 '中等', 18000, 'Go开发', 999, 'PRESET',
 '从已关闭的 channel 接收不会 panic，会立即返回零值和 ok=false；只有向已关闭 channel 发送才 panic。无缓冲 channel 收发必须配对，否则阻塞；nil channel 收发会永久阻塞。'),

-- ========== Agent 开发 ==========
('给大模型接知识库',
 '公司要让 AI 客服回答产品文档里的问题，但直接问大模型只会瞎编。请设计一套 RAG 方案并说明各环节作用。',
 '[{"optionName":"用 embedding 模型把文档切片向量化后存入向量库","trueAnswer":true},{"optionName":"用户提问时先向量化，再在向量库做相似度检索","trueAnswer":true},{"optionName":"把检索到的片段拼进提示词，让模型据此回答","trueAnswer":true},{"optionName":"把整本产品文档每次全量塞进提示词","trueAnswer":false},{"optionName":"RAG 可以完全消除大模型的幻觉","trueAnswer":false},{"optionName":"检索和生成必须使用同一个向量维度","trueAnswer":true}]',
 '中等', 20000, 'Agent开发', 999, 'PRESET',
 'RAG 的核心链路是：切分、向量化、入库、检索、拼提示词、生成。全量塞文档会超长且昂贵；RAG 能大幅降低但不能完全消除幻觉；查询和知识库必须用同一 embedding 模型（同维度、同空间）才能比相似度。'),

('让模型会用工具',
 '你要做一个能查天气、能算数的 Agent，请说明 Function Calling / Tool 是怎么回事。',
 '[{"optionName":"把工具的函数签名和说明告诉模型，让模型决定何时调用","trueAnswer":true},{"optionName":"模型返回工具名和入参，由你的代码真正执行并回传结果","trueAnswer":true},{"optionName":"工具执行结果是模型自己算出来的","trueAnswer":false},{"optionName":"一个 Agent 只能注册一个工具","trueAnswer":false},{"optionName":"Function Calling 需要模型具备该能力，不是所有模型都支持","trueAnswer":true}]',
 '中等', 20000, 'Agent开发', 999, 'PRESET',
 'Function Calling / Tool 的本质是：你向模型声明可用工具（名字、参数、说明），模型根据用户意图返回要调用哪个工具、参数是什么，真正执行的是你的代码，结果再回传给模型继续推理。'),

-- ========== 通用兜底池 ==========
('重复下单事故',
 '用户手抖连点两次，产生了两个订单。作为后端，你怎么保证下单接口幂等？',
 '[{"optionName":"前端按钮防抖加提交后置灰","trueAnswer":true},{"optionName":"用唯一业务号（如订单号或请求号）做幂等键","trueAnswer":true},{"optionName":"数据库对业务号加唯一索引，重复插入直接失败","trueAnswer":true},{"optionName":"用 Redis 对请求号 setnx 做去重","trueAnswer":true},{"optionName":"只靠数据库事务就能保证幂等","trueAnswer":false},{"optionName":"给接口加限流就能防止重复下单","trueAnswer":false}]',
 '中等', 15000, '通用', 999, 'PRESET',
 '幂等的核心是给每次业务操作一个唯一标识，重复请求直接返回上次结果。常用手段：业务唯一号加唯一索引、Redis setnx、状态机。事务保证的是原子性不是幂等；限流只减少并发，不能防重复。'),

('跨库一致性难题',
 '一个下单操作要同时写订单库和库存库，两者是两个独立数据库，如何保证一致性？',
 '[{"optionName":"用本地消息表加定时补偿实现最终一致","trueAnswer":true},{"optionName":"用 TCC 模式，为每个服务实现 try-confirm-cancel","trueAnswer":true},{"optionName":"用 Seata 的 AT 模式做自动补偿","trueAnswer":true},{"optionName":"用一个大事务跨两个库做 XA 强一致，且性能无损","trueAnswer":false},{"optionName":"最终一致性方案可以保证任何时刻数据都一致","trueAnswer":false},{"optionName":"分布式事务不需要考虑补偿和重试","trueAnswer":false}]',
 '困难', 25000, '通用', 999, 'PRESET',
 '跨库一致性属于分布式事务。CAP 下多数选择最终一致：本地消息表、TCC、Seata AT、可靠消息。XA/2PC 强一致但性能差、有协调者单点。最终一致是过一段时间一致，不是任意时刻一致，必须有补偿和幂等重试。');
