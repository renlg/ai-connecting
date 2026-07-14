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

### 性能与可观测

- **数据缓存** — 渠道列表、模型名称、用户信息、Token 验证均带内存缓存（60s TTL）
- **SQL 聚合查询** — Token 统计等聚合操作直接在数据库层完成，避免全量加载
- **全链路追踪** — 集成 Zipkin，请求链路可追踪，traceId 写入响应头便于排查
- **健康检查** — `/health` 端点（公开），用于蓝绿部署和服务监控
- **Redis 可选** — 限流和健康追踪支持 Redis（分布式）和纯内存两种模式

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2 + Spring Security + JPA + Hibernate |
| 数据库 | SQLite（通过 Hibernate 社区方言） |
| 前端 | React 18 + Ant Design 5 + Vite |
| 认证 | JWT + API Key 双因子鉴权 |
| 缓存 | Redis（可选，通过 `RATE_LIMIT_ENABLED` 控制，全局限流需要） |
| 追踪 | Zipkin |
| 部署 | 蓝绿部署（8080 / 8081 端口交替） |

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
| `REDIS_HOST` | Redis 地址 | 限流功能需要 |
| `REDIS_PORT` | Redis 端口（默认 6379） | 否 |
| `REDIS_PASSWORD` | Redis 密码 | 否 |
| `REDIS_DATABASE` | Redis 数据库编号（默认 0） | 否 |
| `REDIS_USERNAME` | Redis 用户名 | 否 |
| `RATE_LIMIT_ENABLED` | 限流功能开关（默认 false，需 Redis） | 否 |
| `CORS_ALLOWED_ORIGINS` | 允许的跨域源（逗号分隔，默认允许所有） | 否 |
| `TRUSTED_PROXIES` | 信任的代理 IP（逗号分隔，默认 127.0.0.1,::1） | 否 |
| `ZIPKIN_ENDPOINT` | Zipkin 服务地址 | 否 |

## 生产部署

项目使用蓝绿部署策略，详见 `deploy/` 目录。

```bash
bash deploy/deploy.sh
```

部署流程：构建前端 → 构建后端 JAR → SCP 上传到服务器 → 启动新端口 → 健康检查 → 切换 Nginx upstream → 关闭旧服务。

服务器环境文件 `/opt/ai-connecting/.env` 需提前配置所有环境变量。

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
