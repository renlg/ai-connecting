# AI Connecting

OpenAI 协议中转站管理面板。支持多渠道池、加权负载均衡、断路器、模型管理、Token 鉴权、积分计费。

## 功能

### 核心转发

- **API 转发** — 兼容 OpenAI `/v1/chat/completions` 和 Claude `/v1/messages` 协议
- **渠道池 + 加权轮询** — 同一模型可配置多个渠道，按权重分发流量，避免单点过载
- **渠道容错重试** — 请求失败自动切换渠道，最多重试 3 次
- **协议自动转换** — Claude 请求自动转为 OpenAI 格式发送到不支持 Claude 的渠道
- **SSE 流式转发** — 支持 OpenAI 和 Claude 协议流式输出，实时透传

### 渠道健康管理

- **断路器** — 渠道 3 分钟内失败 3 次自动封禁 1 小时，避免持续请求无效渠道
- **权重动态调整** — 成功提升权重，失败降低权重，流量自动向稳定渠道倾斜
- **主动探测** — 定时探测被封禁的渠道，恢复后自动解封重新参与轮询
- **自动禁用** — 连续 5 次探测失败后自动禁用渠道并通知管理员

### 管理功能

- **仪表盘** — 实时查看请求量、Token 消耗、积分使用统计
- **渠道管理** — 管理 OpenAI、Azure、Claude 等上游 API 渠道，支持权重、优先级、限流
- **模型管理** — 配置模型名称、积分比例、开关控制
- **Token 管理** — 生成和管理 API Key，绑定模型权限、限流、积分，支持测试聊天
- **用户管理** — 用户注册、状态管理、密码重置、积分充值
- **积分券** — 生成兑换码，用户自助兑换积分

### 性能优化

- **数据缓存** — 渠道列表、模型名称、用户信息、Token 验证均带内存缓存，减少 DB 查询
- **异步健康追踪** — 失败记录异步处理，不阻塞请求线程
- **Redis 可选** — 限流和健康追踪支持 Redis（分布式）和纯内存两种模式

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3 + Spring Security + JPA |
| 数据库 | SQLite |
| 前端 | React 18 + Ant Design 5 + Vite |
| 认证 | JWT |
| 缓存 | Redis（可选，用于限流和分布式追踪） |

## 架构说明

```
请求 → Token 鉴权 → 模型权限检查 → 积分检查 → 限流检查
    → ChannelRouter（缓存渠道列表 → 加权轮询选渠道）
    → 转发请求到上游 → 成功/失败 → 更新健康状态
```

- `ChannelRouter` — 按模型缓存可用渠道列表，加权随机选择，自动跳过被封禁渠道
- `ChannelHealthTracker` — 跟踪失败次数，触发断路器封禁，记录权重变化
- `ChannelProbeTask` — 每小时探测被封禁渠道，恢复后自动解封

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Maven

### 本地运行

**方式一：完整构建运行**

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
JWT_SECRET=your-secret ADMIN_DEFAULT_PASSWORD=your-password java -jar target/ai-connecting-1.0.0.jar
```

**方式二：前后端分离开发**

```bash
# 终端1：启动后端
JWT_SECRET=your-secret ADMIN_DEFAULT_PASSWORD=your-password mvn spring-boot:run

# 终端2：启动前端开发服务器（支持热更新）
cd web && npm install && npm run dev
```

前端开发服务器默认运行在 `http://localhost:5173`，API 请求代理到后端 `http://localhost:8080`。

访问 `http://localhost:8080` 或 `http://localhost:5173`。

首次启动会自动创建 admin 用户，密码通过环境变量 `ADMIN_DEFAULT_PASSWORD` 设置。

### 环境变量

| 变量 | 说明 | 必填 |
|---|---|---|
| `JWT_SECRET` | JWT 签名密钥 | 是 |
| `ADMIN_DEFAULT_PASSWORD` | 初始管理员密码 | 是 |
| `REDIS_HOST` | Redis 地址 | 限流功能需要 |
| `REDIS_PASSWORD` | Redis 密码 | 否 |
