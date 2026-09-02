# 密码加密 BCrypt：流程与运行机制

> 本文梳理登录模块中密码从注册到登录的完整链路，回答三个核心问题：
> 1. 注册 / 登录分别做了什么；
> 2. Redis 里到底存了什么；
> 3. BCrypt 的盐值怎么来、为什么注册和登录能对上。

## 一、核心结论（TL;DR）

| 问题 | 结论 |
|---|---|
| 密码存哪 | MySQL `user` 表的 `password` 字段，存的是 **BCrypt 哈希串**，不是明文 |
| 盐值存哪 | **不单独存**，盐就内嵌在哈希串里 |
| 为什么注册登录一致 | 登录时从库里取出的哈希串中**反向解析出盐**，用同一个盐重算输入的密码再比对 |
| Redis 存什么 | 只存**邮箱验证码**（`email:code:{邮箱}`，5 分钟 TTL），不存密码、不存登录态 |
| 登录态存哪 | HTTP Session（`request.getSession()`），当前是内存态，`spring.session.store-type: redis` 尚未开启 |

## 二、BCrypt 哈希串长什么样

`BCryptPasswordEncoder.encode("12345678")` 每次输出都不同，例如：

```
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
```

固定 60 个字符，分段拆解：

| 片段 | 含义 | 本例取值 |
|---|---|---|
| `$2a` | 算法版本 | 2a |
| `$10` | cost（代价因子）= 2^10 = 1024 轮 Blowfish 迭代 | 10 |
| 中间 22 字符 | **盐值**（16 字节随机数的 base64 编码） | `N9qo8uLOickgx2ZMRZoMye` |
| 末尾 31 字符 | 密码哈希结果（base64） | `IjZAgcfl7p92ldGxad68LJZdL17lhWy` |

关键点：**盐和哈希被打包在同一个字符串里**，所以数据库只需要一个 `password` 字段，不需要额外的 `salt` 字段。

## 三、注册流程

入口：`UserController` → `UserServiceImpl.userRegister` → 按 `registerType` 走策略工厂 → 两个策略之一。

### 3.1 密码注册 `PasswordRegisterStrategy`

1. 校验：用户名 ≥ 4 位、密码 ≥ 8 位、两次密码一致、用户名不含特殊字符。
2. 查重：`username` 已存在则报错。
3. **加密**：`user.setPassword(passwordEncoder.encode(password))` —— 这里生成随机盐并算出哈希。
4. 落库：`userMapper.insert(user)`，此时 `password` 字段存的就是 60 字符哈希串。
5. 补默认头像并回写。

### 3.2 邮箱注册 `EmailRegisterStrategy`

1. 校验邮箱格式、密码长度。
2. **校验验证码**：从 Redis 读 `email:code:{邮箱}`，比对通过后 `delete` 删除该 key。
3. 查重：`email` 已存在则报错。
4. **加密**：同样 `passwordEncoder.encode(password)`。
5. 落库。

两种注册**最终都把 BCrypt 哈希写入 `user.password`**，差别只在验证码环节。

## 四、登录流程

`UserServiceImpl.userLogin`：

1. 参数校验（用户名 ≥ 4、密码 ≥ 8）。
2. **按用户名查库**（注意：只按 `username` 查，不再按密码查）：

   ```java
   User user = this.baseMapper.selectOne(new QueryWrapper<User>().eq("username", username));
   ```

3. **哈希比对**：

   ```java
   if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
       throw new RuntimeException("用户不存在或密码错误");
   }
   ```

4. 登录成功：`request.getSession().setAttribute(USER_LOGIN_STATE, user)`，写入 Session。
5. 返回 `LoginUserVO`（脱敏，不含密码）。

**为什么不能再按密码哈希查库**：BCrypt 每次 `encode` 盐都随机，同一个密码两次哈希结果不同，所以「算一个哈希再 `where password = ?`」这条路走不通，必须「先查出用户，再 `matches` 比对」。

## 五、Redis 里存了什么

Redis 在本模块中**只承担邮箱验证码存储**，与密码、登录态都无关：

| Key | Value | TTL | 写入时机 | 读取/删除时机 |
|---|---|---|---|---|
| `email:code:{邮箱}` | 6 位数字验证码字符串 | 5 分钟 | `sendRegisterCode`（发验证码） | `EmailRegisterStrategy` 校验后删除 |

- 验证码生成：`RandomUtil.randomInt(100000, 999999)`。
- 存储：`stringRedisTemplate.opsForValue().set(CacheKey.EMAIL_CODE.key(email), code, 5, TimeUnit.MINUTES)`。

**登录态不在 Redis**：`application.yml` 中 `spring.session.store-type: redis` 是注释状态（`# store-type: redis`），所以 `getSession()` 用的是默认内存 Session；密码哈希更不在 Redis，它只在 MySQL。

## 六、盐值怎么来、为什么注册登录能对上

### 6.1 盐的生成

`BCryptPasswordEncoder.encode()` 内部流程：

1. 用 `SecureRandom` 生成 16 字节（128 位）随机盐。
2. 以 cost（默认 10，即 2^10 次迭代）+ 盐 + 明文密码 跑 Blowfish 变体算法（EksBlowfishSetup）。
3. 把版本、cost、盐、哈希拼成 `$2a$10$<盐22><哈希31>` 返回。

### 6.2 校验时盐从哪来

`matches(rawPassword, encodedPassword)` 内部流程：

1. **解析**传入的 `encodedPassword`（库里存的哈希串），拆出 version、cost、盐。
2. 用**同一个盐** + 输入的明文密码，重新跑一遍同样的算法。
3. 把重算结果和库里哈希**常量时间比较**（防时序侧信道），一致则通过。

### 6.3 为什么一致

一句话：**盐不在别处，盐就在哈希串里**。

- 注册：生成一个新随机盐 → 算出哈希 → 盐和哈希一起存进 `password` 字段。
- 登录：从 `password` 字段解析出当初那个盐 → 用相同盐重算输入密码 → 比对。

只要输入的明文和注册时相同，同一盐 + 同一算法算出的结果必然逐字节相同，所以 `matches` 返回 true。**整个过程不需要额外查询或保存盐**。

## 七、改造历史：MD5 → BCrypt

### 7.1 原实现的问题

原用 `DigestUtil.md5Hex(SALT + password)` 存密码，两个缺陷：

1. **MD5 不安全**：现代 GPU 每秒数十亿次，纯暴力即破，彩虹表更快。
2. **固定全局盐 `SALT = "xiaoHua"`**：所有用户同一个盐 → 同密码必同哈希 → 彩虹表 / 跨站批量比对完全适用。

正确做法是**每用户随机盐**并随哈希入库，BCrypt 恰好内嵌了这一点。

### 7.2 改动清单

| 文件 | 改动 |
|---|---|
| `pom.xml` | 新增 `org.springframework.security:spring-security-crypto`（只引加密模块，不引完整 security starter，避免默认拦截所有接口） |
| `config/PasswordConfig.java` | 新增，注册 `BCryptPasswordEncoder` Bean |
| `strategy/impl/PasswordRegisterStrategy.java` | `md5Hex(SALT + password)` → `passwordEncoder.encode(password)` |
| `strategy/impl/EmailRegisterStrategy.java` | 同上 |
| `service/impl/UserServiceImpl.java` | 登录改为「先查用户再 matches」；删除 `SALT` 常量与 `DigestUtil` import |

### 7.3 老用户密码会失效

库里已有用户存的是 `md5Hex("xiaoHua" + password)`，BCrypt 无法校验，这些老用户会登录失败。

- 开发阶段无真实数据：重新注册即可。
- 有真实数据：登录时检测哈希格式（MD5 为 32 位 hex，bcrypt 以 `$2a$`/`$2b$` 开头），MD5 校验通过后立即用 bcrypt 重算回写，下次走 bcrypt。

## 八、密码哈希算法选型（延伸）

| 维度 | PBKDF2 | bcrypt | Argon2id |
|---|---|---|---|
| 抗 GPU/ASIC | 中 | 中 | **强**（内存硬） |
| 抗彩虹表（配随机盐） | 强 | 强 | 强 |
| FIPS 合规 | **是** | 否 | 否 |
| 依赖成本 | JDK 内置 | 极广 | 较新 |
| 一句话定位 | 合规保底 | 通用省心 | 最强安全 |

本项目选 **bcrypt**：Spring 生态开箱即用、改动最小、安全足够。
