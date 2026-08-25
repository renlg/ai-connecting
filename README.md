# AI Connecting

OpenAI 协议中转站管理面板。支持多渠道池、加权负载均衡、断路器、协议自动转换（OpenAI / Claude / Gemini）、Token 鉴权、积分计费。

## 功能

### 核心转发

- **多协议兼容** — 支持 OpenAI `/v1/chat/completions`、Claude `/v1/messages`、Gemini `/v1/models/*:generateContent`
- **协议自动转换** — 请求在不同 AI 提供商之间自动转换格式（OpenAI ↔ Claude ↔ Gemini 互转）
- **渠道池 + 加权轮询** — 同一模型可配置多个渠道，按权重分发流量
- **渠道容错重试** — 请求失败自动切换渠道，最多重试 3 次
- **SSE 流式转发** — 流式输出实时透传，支持所有上游协议

### 渠道健康管理

- **断路器** — 渠道 3 分钟内失败 3 次自动封禁 1 小时
- **权重动态调整** — 成功提升权重，失败降低权重，流量自动向稳定渠道倾斜
- **主动探测** — 定时探测被封禁的渠道，恢复后自动解封
- **自动禁用** — 连续 5 次探测失败后自动禁用渠道并通知管理员

### 管理功能

- **仪表盘** — 实时查看请求量、Token 消耗、积分使用统计
- **渠道管理** — 管理 OpenAI、Azure、Claude、Gemini 等上游 API 渠道，支持权重、优先级、限流
- **模型管理** — 配置模型名称、积分比例、开关控制
- **Token 管理** — 生成和管理 API Key，绑定模型权限、限流、积分，支持测试聊天
- **用户管理** — 用户注册、状态管理、密码重置、积分充值
- **积分券** — 生成兑换码，用户自助兑换积分
- **操作审计** — 管理员所有写操作自动记录日志，可追溯

### 媒体计费

- **多模型类型计费** — 模型分 `text` / `image` / `video` / `audio` 四类，图片按分辨率档位（1k/2k/4k）、视频按分辨率档位（480p/720p/1080p/4k）、音频按标准/高清档位分别计价
- **预扣模式** — 媒体类请求在调用上游前原子预扣积分，避免上游耗时任务期间的透支；实际计费与预扣金额有差异时按差额结算/退款
- **视频任务对账** — 视频生成为异步任务，通过 `settled`/`processingLeaseUntil` 等字段做乐观锁式条件 UPDATE（`WHERE ... AND settled = false`），保证客户端轮询与后台对账任务并发访问同一任务时不会重复扣款/退款
- **渠道密钥加密** — 渠道 API Key 落库前经 AES-256-GCM 加密（`enc:v1:` 前缀），密钥通过 `CHANNEL_ENCRYPTION_KEY` 环境变量提供，历史明文密钥启动时自动迁移加密

### 性能与可观测

- **数据缓存** — 渠道列表、模型名称、用户信息、Token 验证均带内存缓存（60s TTL）
- **SQL 聚合查询** — Token 统计等聚合操作直接在数据库层完成，避免全量加载；仪表盘统计每 15 分钟聚合一次写入独立的 `usage_stats` 表，避免仪表盘查询扫描明细表
- **有界缓存** — 仪表盘等内存缓存采用 LRU 限制条目数上限，避免长期运行下的内存无界增长
- **全链路追踪** — 集成 Zipkin，请求链路可追踪，traceId 写入响应头便于排查
- **健康检查** — `/health` 端点（公开），用于部署时的启动探活和服务监控
- **Redis 可选** — 限流、健康追踪、登录失败锁定、分布式锁、跨实例缓存失效广播支持 Redis（可跨进程共享）和纯内存两种模式，通过 `REDIS_ENABLED` 独立控制（`RATE_LIMIT_ENABLED` 只控制限流逻辑本身，见「多端部署」一节）

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2 + Spring Security + JPA + Hibernate |
| 数据库 | SQLite（默认，单实例部署，通过 Hibernate 社区方言），HikariCP 连接池（WAL 模式，`busy_timeout=5000`，最大连接数 4）；MySQL 为可选配置（`SPRING_PROFILES_ACTIVE=mysql`），供未来多实例部署使用 |
| 前端 | React 18 + Ant Design 5 + Vite |
| 认证 | JWT + API Key 双因子鉴权 |
| 缓存 | Redis（可选，通过 `REDIS_ENABLED` 独立控制；限流 / 渠道健康追踪 / 登录失败锁定 / 分布式锁 / 跨实例缓存失效广播共用） |
| 对象存储 | 阿里云 OSS（图片/视频等媒体产物转存为匿名直链，`OSS_ENABLED` 可关闭） |
| 追踪 | Zipkin |
| 部署 | 单机 systemd 服务，`deploy/deploy.sh` 一键构建 + 上传 + 重启 |

## 认证体系

```
用户面板 → JWT 登录（用户名+密码）→ 管理操作
API 转发 → API Key 鉴权（Authorization: Bearer <token>）→ 转发请求
```

- **管理面板**：用户名密码登录，JWT 会话
- **API 转发**：通过自动生成的 API Key（Token）鉴权，支持模型权限绑定
- **API Key 验证带缓存**：验证结果缓存 60s，禁用/过期后最多 60s 生效

## 请求流程

```
请求 → API Key 鉴权 → 模型权限检查 → 积分检查 → 限流检查（可选）
    → ChannelRouter（缓存渠道列表 → 加权轮询选渠道）
    → 协议转换（如需要）→ 转发到上游 → 成功/失败 → 更新健康状态
```

## 架构说明

- `ChannelRouter` — 按模型缓存可用渠道列表，加权随机选择，自动跳过被封禁渠道
- `ChannelHealthTracker` — 异步跟踪失败次数，触发断路器封禁，记录权重变化
- `ChannelProbeTask` — 每小时探测被封禁渠道，恢复后自动解封
- `ProtocolConverter` — OpenAI / Claude / Gemini 协议互转
- `RelayService` — 主流 SSE/非SSE 请求转发，多协议格式转换

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Maven

### 本地运行

**完整构建运行**

```bash
# 构建前端
cd web && npm install && npm run build && cd ..

# 拷贝前端产物到后端静态目录
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cp -r web/dist/* src/main/resources/static/

# 构建后端
mvn clean package -DskipTests

# 启动（需设置环境变量）
JWT_SECRET=your-secret ADMIN_DEFAULT_PASSWORD=your-password \
  java -jar target/ai-connecting-1.0.0.jar
```

**前后端分离开发**

```bash
# 终端1：启动后端
JWT_SECRET=your-secret ADMIN_DEFAULT_PASSWORD=your-password mvn spring-boot:run

# 终端2：启动前端开发服务器（支持热更新）
cd web && npm install && npm run dev
```

前端开发服务器默认运行在 `http://localhost:5173`，API 请求代理到后端 `http://localhost:8080`。

首次启动会自动创建 admin 用户，密码通过环境变量 `ADMIN_DEFAULT_PASSWORD` 设置。

### 环境变量

| 变量 | 说明 | 必填 |
|---|---|---|
| `JWT_SECRET` | JWT 签名密钥 | 是 |
| `ADMIN_DEFAULT_PASSWORD` | 初始管理员密码 | 是 |
| `ADMIN_RESET_PASSWORD` | 重置管理员密码（非空时触发） | 否 |
| `CHANNEL_ENCRYPTION_KEY` | 渠道 API Key 加密密钥（base64 编码的 32 字节 AES-256-GCM 密钥） | 是 |
| `SPRING_PROFILES_ACTIVE` | Spring profile：留空/不设置为默认 SQLite；设为 `mysql` 切换到 MySQL（可选，供多实例部署使用） | 否 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | MySQL 连接参数（`SPRING_PROFILES_ACTIVE=mysql` 时必填，`DB_HOST` 默认 `127.0.0.1`，`DB_PORT` 默认 3306，`DB_NAME` 默认 `ai_connecting`） | 仅 MySQL 模式需要 |
| `DB_POOL_SIZE` | 数据库连接池最大连接数（默认按 profile 区分：SQLite 默认 4，MySQL 默认 15） | 否 |
| `REDIS_HOST` | Redis 地址 | 限流 / 渠道健康分布式模式 / 登录失败锁定需要 |
| `REDIS_PORT` | Redis 端口（默认 6379） | 否 |
| `REDIS_PASSWORD` | Redis 密码 | 否 |
| `REDIS_DATABASE` | Redis 数据库编号（默认 0） | 否 |
| `REDIS_USERNAME` | Redis 用户名 | 否 |
| `REDIS_ENABLED` | Redis 装配开关（默认 false）；独立控制 `RedisConfig`（连接、分布式锁、缓存失效广播等 Bean），不再与 `RATE_LIMIT_ENABLED` 绑定 | 多实例部署需要 |
| `CLUSTER_ENABLED` | 多实例部署开关（默认 false）；开启后若 `REDIS_ENABLED=false` 或 Redis 不可用，启动时直接 FAIL FAST | 多实例部署建议开启 |
| `RATE_LIMIT_ENABLED` | 限流功能开关（默认 false，需同时设置 `REDIS_ENABLED=true`） | 否 |
| `APP_ENV` | Redis 锁命名空间（默认 `default`）；同一部署的所有实例必须一致，共享 Redis 的不同环境必须不同（如 `dev`/`test`/`prod`） | 否 |
| `CORS_ALLOWED_ORIGINS` | 允许的跨域源（逗号分隔，默认允许所有） | 否 |
| `TRUSTED_PROXIES` | 信任的代理 IP（逗号分隔，默认 127.0.0.1,::1） | 否 |
| `ZIPKIN_ENDPOINT` | Zipkin 服务地址 | 否 |
| `XAI_IMAGE_PROXY_BASE_URL` | xAI 图片地址反代基址（默认 `http://207.57.184.239`）；仅支持 `scheme://host[:port]`，不能包含 path | 否 |
| `MODEL_GROUP_PRUNING_ENABLED` | 长期全渠道熔断模型自动剔除开关（默认 true） | 否 |
| `MODEL_GROUP_PRUNING_INTERVAL_MS` | 长期全渠道熔断模型自动剔除任务周期，单位毫秒（默认 600000，即 10 分钟） | 否 |
| `OSS_ENABLED` | 是否将媒体产物（图片/视频）转存至阿里云 OSS（默认 true，关闭则按上游原样透传） | 否 |
| `OSS_ACCESS_KEY_ID` | 阿里云 OSS AccessKey ID | OSS 功能需要 |
| `OSS_ACCESS_KEY_SECRET` | 阿里云 OSS AccessKey Secret | OSS 功能需要 |
| `OSS_BUCKET` | OSS Bucket 名称（默认 renlg） | 否 |
| `OSS_ENDPOINT` | OSS Endpoint host，不含协议前缀（默认 oss-cn-hangzhou.aliyuncs.com） | 否 |
| `OSS_PREFIX` | OSS 对象 key 前缀（默认 ai-connect） | 否 |

> **注意**：转存服务会将上传对象的 ACL 显式设为 `public-read` 并直接返回匿名 HTTPS 直链（不使用签名 URL）。
> 因此 `OSS_BUCKET` 必须允许公共读（Bucket 策略/权限不能阻止对象级 `public-read` ACL 生效），
> 否则客户端访问返回的媒体地址会得到 403。

## 生产部署

当前为单机部署（阿里云 ECS），通过 `deploy/deploy.sh` 一键构建发布，详见 `deploy/` 目录。

```bash
bash deploy/deploy.sh
```

部署流程：构建前端 → 构建后端 JAR → 单元测试 → SCP 上传 JAR 与 systemd 单元文件到服务器 → `systemctl restart` 重启服务 → 健康检查（`systemctl is-active`）。

注意：这是单实例原地重启，重启期间会有短暂服务中断（`Restart=on-failure` 由 systemd 保证进程崩溃后自动拉起，但不是零停机的蓝绿切换）；仓库中未包含 Nginx 双端口切换脚本。

服务器环境文件 `/opt/ai-connecting/.env` 需提前配置所有环境变量，且需要在服务器上手动准备（不随部署脚本同步）。

## 多端部署 (Multi-instance Deployment)

项目支持在负载均衡后面部署多个应用节点。以下描述当前已实现的多实例能力，以及启用方式：

- **数据库**：默认仍是应用本地的 SQLite 单一文件（`./data/ai-connecting.db`，单实例部署行为不变），多实例部署需通过 `SPRING_PROFILES_ACTIVE=mysql` 切换到 MySQL；MySQL 支持真正的多连接并发写和跨主机访问，是多实例部署的前提。切换后 schema 由 `schema-mysql.sql` 在启动时建表（`CREATE TABLE IF NOT EXISTS`），`ddl-auto` 收敛为 `validate`。
- **启动期迁移逻辑**：`ModelTypeMigrationRunner`、`DashboardIndexMigrationRunner`、`VideoTaskMigrationRunner`、`UsageStatsIndexMigrationRunner` 均通过 `DataSource` 元数据探测当前方言（SQLite 用 `PRAGMA table_info(...)`，MySQL 用 `information_schema`），且对 `ALTER TABLE ADD COLUMN` / `CREATE INDEX` 等 DDL 做了并发容错：多个节点同时在存量库上启动时，后完成的一方收到 MySQL 1060（列已存在）/1061（索引已存在）/1091（索引已删除）等幂等冲突错误会直接忽略并继续启动，不会因为竞态互相打断。
- **`UserService.initAdmin`（admin 账号初始化）**：多个节点在全新数据库上同时启动时，只会有一个节点成功插入 `admin` 用户，另一个节点捕获 `username` 唯一约束冲突（`DataIntegrityViolationException`）后按"已被其他实例创建"处理，正常继续启动，不会导致容器崩溃。
- **`usage_stats` 聚合窗口去重**：`(start_time, end_time)` 现有唯一索引 `uk_usage_stats_window`（`schema-mysql.sql` 建表语句 + `UsageStats` 实体 `@Table(indexes = ...)`，SQLite ddl-auto 同步生效），在应用层去重（分布式锁失效/竞态）之外再加一层数据库约束防重复聚合行。存量库可能已有历史重复窗口，`UsageStatsIndexMigrationRunner` 启动时会先探测重复：干净则建唯一索引，存在重复则只记录 WARN 并跳过建索引，保证启动不因历史脏数据崩溃。
- **定时任务分布式锁**：`StatsAggregationService`（每 15 分钟聚合、每日清理旧数据）、`ChannelProbeTask`（每小时探测被封禁渠道）、`OpenAiRelayService` 的视频任务对账、`UserService` 启动时补齐邀请码（`ensureAllUsersHaveInviteCode`）均已通过 `RedisDistributedLock`（基于 Redis `SETNX` + Lua 原子释放）保证多节点下同一时刻只有一个节点执行。锁获取失败（其他实例持有，或 Redis 短暂异常）时**FAIL-CLOSED**——本轮直接跳过、不在本地降级执行，避免 Redis 抖动时多个节点同时误判"抢到锁"而重复跑；等待下一轮调度（如 15 分钟后）重试即可，不需要人工干预。
- **锁续约（watchdog）**：持锁期间 `RedisDistributedLock` 会在后台每 TTL/3 秒对锁执行一次原子续约（Lua CAS + `PEXPIRE`，仅当锁仍属于本实例才续约），任务运行多久锁就续多久；进程崩溃后无法再续约，锁最迟在 TTL 内自然释放。这使得各任务的 TTL 不必再覆盖"理论最坏耗时"，可以设置得更短：视频任务对账锁 TTL 由原先 4 小时收窄为 15 分钟，聚合/清理/渠道探测等锁 TTL 也相应收窄至 3～10 分钟，大幅降低进程崩溃后重复执行/对账延迟的窗口。
- **进程内缓存跨实例失效广播**：渠道列表、模型配置、用户信息、Token 校验结果等 `ConcurrentHashMap` 本地缓存，写操作后通过 `CacheInvalidationService` 基于 Redis pub/sub 广播失效消息，其他节点收到后立即清除本地缓存副本，不需要等待 TTL 过期。
- **Redis 现在是独立可插拔的基础设施，不再与限流开关绑定**：`app.redis.enabled` / `REDIS_ENABLED`（默认 `false`）单独控制 `RedisConfig` 是否装配（连接、分布式锁 `StringRedisTemplate`、缓存失效发布订阅等全部 Bean），`app.rate-limit.enabled` / `RATE_LIMIT_ENABLED` 只控制限流逻辑本身是否生效，二者不再互相牵连（此前的问题：限流默认关闭会连带整个 Redis 配置都不装配，导致分布式锁/缓存失效广播在默认配置下静默失效）。
- **集群模式显式声明 + 启动期 Fail Fast**：新增 `app.cluster.enabled` / `CLUSTER_ENABLED`（默认 `false`）声明"这是一次多实例部署"。开启集群模式时 `ClusterConfigValidator` 不仅检查 `StringRedisTemplate` bean 是否装配，还会实际获取一个 `RedisConnectionFactory` 连接并执行 PING——因为 Lettuce 连接是懒连接，仅有 bean 不代表 Redis 真正可达；PING 失败（host/port/密码配置错误等）时在启动期直接抛出异常拒绝启动，而不是让分布式锁/缓存失效静默退化为单机行为、把不一致问题留到运行时才被发现；未开启集群模式时也会打印明确的 WARN 提示当前处于降级的单机协调模式。
- **积分/额度写路径本身具备良好的原子性基础**：`UsageLogService.recordUsageAndQuotas` 内的余额与额度更新（`UserRepository.tryDeductCredits`、`ChannelRepository.addUsedQuota`、`TokenRepository.addUsedQuota`）都是条件 `UPDATE ... WHERE` 语句而非"读出改回写"，视频任务结算也用 `WHERE settled = false` 的条件 UPDATE 做乐观锁；这一模式在 MySQL/PostgreSQL 下并发语义不变，多节点部署无需改造。

**多实例部署最小配置**：`SPRING_PROFILES_ACTIVE=mysql`（真正支持多连接并发写）+ `REDIS_ENABLED=true`（分布式锁/缓存失效广播）+ `CLUSTER_ENABLED=true`（触发启动期一致性校验，Redis 缺失时直接拒绝启动而不是静默退化）+ 所有节点共享同一个 `APP_ENV`（分布式锁 key 命名空间前缀，必须在同一部署内保持一致，见 `RedisDistributedLock`）。

## 项目结构

```
src/main/java/com/aiconnecting/
├── AiConnectingApplication.java    # 应用入口
├── common/                         # 通用工具
│   ├── ApiResponse.java
│   ├── BusinessException.java
│   ├── GlobalExceptionHandler.java
│   ├── ProtocolConverter.java      # 多协议转换（OpenAI/Claude/Gemini）
│   └── SseUtils.java
├── config/                         # 配置类
│   ├── RedisConfig.java            # Redis 连接 + 限流 Lua 脚本
│   ├── SecurityConfig.java         # Spring Security + CORS
│   ├── TracingConfig.java          # Zipkin 全链路追踪
│   └── WebConfig.java
├── controller/                     # API 控制器
│   ├── AdminController.java        # 管理接口
│   ├── AuthController.java         # 登录/注册
│   ├── ChannelController.java
│   ├── HealthController.java       # 健康检查
│   ├── ModelConfigController.java
│   ├── PublicController.java
│   ├── RelayController.java        # AI API 转发
│   ├── TokenController.java
│   └── UserController.java
├── dto/                            # 数据传输对象
├── entity/                         # JPA 实体
│   ├── Announcement.java
│   ├── Channel.java
│   ├── Coupon.java
│   ├── CouponRedemptionLog.java
│   ├── ModelConfig.java
│   ├── OperationLog.java           # 操作审计日志
│   ├── Token.java
│   ├── UsageLog.java
│   └── User.java
├── repository/                     # 数据访问层
├── security/                       # JWT 鉴权
├── service/                        # 业务逻辑
│   ├── ChannelHealthTracker.java   # 渠道健康追踪
│   ├── ChannelProbeTask.java       # 定时探测任务
│   ├── ChannelRouter.java          # 加权路由
│   ├── CouponService.java
│   ├── DashboardService.java
│   ├── ModelConfigService.java
│   ├── OperationLogService.java    # 操作审计
│   ├── RateLimitService.java       # 限流
│   ├── RelayService.java           # 请求转发核心
│   ├── TokenService.java
│   ├── UsageLogService.java
│   └── UserService.java
└── web/                            # 前端（React + Ant Design）
    ├── src/pages/                  # 页面组件
    └── src/api/                    # API 调用
```
