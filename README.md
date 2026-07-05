# AI Connecting

OpenAI 协议中转站管理面板。支持多渠道管理、模型配置、Token 鉴权、积分计费。

## 功能

- **仪表盘** — 实时查看请求量、Token 消耗、积分使用统计
- **渠道管理** — 管理 OpenAI、Azure、Claude 等上游 API 渠道，支持优先级和限流
- **模型管理** — 配置模型名称、积分比例、开关控制
- **Token 管理** — 生成和管理 API Key，绑定模型权限、限流、积分
- **用户管理** — 用户注册、状态管理、密码重置、积分充值
- **积分券** — 生成兑换码，用户自助兑换积分
- **API 转发** — 兼容 OpenAI `/v1/chat/completions` 协议，自动路由到配置的渠道

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3 + Spring Security + JPA |
| 数据库 | SQLite |
| 前端 | React 18 + Ant Design 5 + Vite |
| 认证 | JWT |
| 缓存 | Redis（可选，用于限流） |

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Maven

### 本地运行

```bash
# 构建前端
cd web && npm install && npm run build && cd ..

# 拷贝前端产物
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cp -r web/dist/* src/main/resources/static/

# 构建后端
mvn clean package -DskipTests

# 启动
java -jar target/ai-connecting-1.0.0.jar
```

访问 `http://localhost:8080`

首次启动会自动创建 admin 用户，密码通过环境变量 `ADMIN_DEFAULT_PASSWORD` 设置。

### 环境变量

| 变量 | 说明 | 必填 |
|---|---|---|
| `JWT_SECRET` | JWT 签名密钥 | 是 |
| `ADMIN_DEFAULT_PASSWORD` | 初始管理员密码 | 是 |
| `REDIS_HOST` | Redis 地址 | 限流功能需要 |
| `REDIS_PASSWORD` | Redis 密码 | 否 |

## 部署

参考 `deploy/` 目录下的脚本自行配置部署流程。
