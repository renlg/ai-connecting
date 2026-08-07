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
- **Redis 可选** — 限流、健康追踪、登录失败锁定支持 Redis（可跨进程共享）和纯内存两种模式，通过 `RATE_LIMIT_ENABLED` 控制

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2 + Spring Security + JPA + Hibernate |
| 数据库 | SQLite（通过 Hibernate 社区方言），HikariCP 连接池（WAL 模式，`busy_timeout=5000`，最大连接数 4） |
| 前端 | React 18 + Ant Design 5 + Vite |
| 认证 | JWT + API Key 双因子鉴权 |
| 缓存 | Redis（可选，通过 `RATE_LIMIT_ENABLED` 控制；限流 / 渠道健康追踪 / 登录失败锁定共用） |
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
| `REDIS_HOST` | Redis 地址 | 限流 / 渠道健康分布式模式 / 登录失败锁定需要 |
| `REDIS_PORT` | Redis 端口（默认 6379） | 否 |
| `REDIS_PASSWORD` | Redis 密码 | 否 |
| `REDIS_DATABASE` | Redis 数据库编号（默认 0） | 否 |
| `REDIS_USERNAME` | Redis 用户名 | 否 |
| `RATE_LIMIT_ENABLED` | 限流功能开关（默认 false，需 Redis） | 否 |
| `CORS_ALLOWED_ORIGINS` | 允许的跨域源（逗号分隔，默认允许所有） | 否 |
| `TRUSTED_PROXIES` | 信任的代理 IP（逗号分隔，默认 127.0.0.1,::1） | 否 |
| `ZIPKIN_ENDPOINT` | Zipkin 服务地址 | 否 |
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

> 以下为架构设计分析笔记，描述当前**尚未支持**、未来若要多实例横向扩展需要解决的问题，不代表已实现的功能。

项目当前按单实例设计和部署。若要在负载均衡后面部署多个应用节点并保持数据一致，现有实现存在以下阻塞点：

- **SQLite 单写者语义**：数据库是应用本地的单一文件（`./data/ai-connecting.db`），每个节点各自持有一份文件即数据互相独立、不共享；若改为通过 NFS/云盘挂载同一份文件，WAL 模式在网络文件系统上的锁语义没有可靠保证，存在损坏风险，SQLite 本质上不是为多进程跨主机并发写设计的。
- **`ddl-auto: update` 竞态**：多个节点同时启动会并发对同一份 schema 做变更，缺少分布式迁移锁，存在建表/加列竞态。
- **启动期迁移逻辑依赖 SQLite 方言**：`ApiKeyMigrationRunner`、`ModelTypeMigrationRunner`、`DashboardIndexMigrationRunner`、`VideoTaskMigrationRunner` 均通过 `PRAGMA table_info(...)` 等 SQLite 专有语法探测列是否存在，迁移到其他数据库需要重写。
- **定时任务重复执行**：`StatsAggregationService`（每 15 分钟聚合、每日 03:30 清理旧数据）、`ChannelProbeTask`（每小时探测被封禁渠道）、`OpenAiRelayService` 中的视频任务对账任务均为 `@Scheduled`，多节点部署下会在每个节点各跑一份。聚合任务内部靠 JVM 内 `synchronized` 锁 + 事务内二次校验防重复写入，这只在单进程内有效，多进程下需要换成 Redis 分布式锁（当前项目里**没有**现成的分布式锁工具类，只有限流用的滑动窗口 Lua 脚本）。
- **进程内缓存不同步**：渠道列表、模型信息、用户信息、Token 校验结果等使用 `ConcurrentHashMap` 做本地内存缓存（60s TTL），多节点下各节点缓存互不感知，写操作后其他节点最多 60s 内可能读到旧数据。
- **Redis 现状是可选依赖而非强依赖**：`RATE_LIMIT_ENABLED=false` 时限流、登录失败锁定完全不生效（相关 Bean 通过 `@ConditionalOnProperty` 直接不装配），渠道健康追踪退化为纯内存模式；多节点部署下必须强制启用 Redis，否则限流/防爆破/健康状态在节点间完全不一致。
- **积分/额度写路径本身具备较好的原子性基础**：`UsageLogService.recordUsageAndQuotas` 内的余额与额度更新（`UserRepository.tryDeductCredits`、`ChannelRepository.addUsedQuota`、`TokenRepository.addUsedQuota`）都是条件 `UPDATE ... WHERE` 语句而非"读出改回写"，视频任务结算也用 `WHERE settled = false` 的条件 UPDATE 做乐观锁；这一模式可以直接迁移到 MySQL/PostgreSQL 且并发语义不变，是多节点改造中不需要重写的部分。

**推荐方向**：数据库迁移到 MySQL 或 PostgreSQL（真正支持多连接并发写、行级锁、跨主机访问），迁移期间将四个迁移 Runner 中的 SQLite 专有 SQL 替换为目标数据库方言的等价写法，`ddl-auto` 收敛为 `validate`（配合独立的建表 SQL/迁移工具管理 schema），并为所有 `@Scheduled` 任务加上基于 Redis 的分布式锁（此时 Redis 需从可选依赖提升为强制依赖）。这是一次涉及数据层和部分服务层的改造，暂无实施计划。

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
