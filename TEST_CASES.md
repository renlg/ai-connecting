# AI-Connecting 功能测试用例

## 一、认证与注册模块

### 1.1 用户登录 `POST /api/auth/login`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| AUTH-001 | 正常登录 | username=admin, password=正确密码 | 200, 返回 JWT token、用户信息 |
| AUTH-002 | 密码错误 | username=admin, password=错误密码 | 400, "用户名或密码错误" |
| AUTH-003 | 用户不存在 | username=不存在用户 | 400, "用户名或密码错误" |
| AUTH-004 | 账号已禁用 | username=禁用用户 | 400, "账号已被禁用" |
| AUTH-005 | 缺少用户名 | password=xxx | 400, 参数校验失败 |
| AUTH-006 | 缺少密码 | username=xxx | 400, 参数校验失败 |
| AUTH-007 | 空请求体 | {} | 400, 参数校验失败 |

### 1.2 用户注册 `POST /api/auth/register`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| AUTH-008 | 正常注册 | username, password, email | 200, 返回用户信息(不含密码) |
| AUTH-009 | 用户名重复 | 已存在的 username | 400, "用户名已存在" |
| AUTH-010 | 缺少用户名 | 无 username | 400, 参数校验失败 |
| AUTH-011 | 缺少密码 | 无 password | 400, 参数校验失败 |
| AUTH-012 | 缺少邮箱 | 无 email | 400, 参数校验失败 |
| AUTH-013 | 邮箱格式错误 | email=not-an-email | 400, 参数校验失败 |

---

## 二、用户管理模块

### 2.1 个人信息 `GET/PUT /api/user/profile`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| USER-001 | 获取个人信息 | 已登录用户 | 200, 返回用户信息(不含密码) |
| USER-002 | 用户不存在 | 已删除用户 token | 400, "用户不存在" |
| USER-003 | 更新昵称和邮箱 | nickname, email | 200, 返回更新后的信息 |
| USER-004 | 部分更新(仅昵称) | nickname=xxx | 200, 仅昵称更新 |

### 2.2 修改密码 `PUT /api/user/password`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| USER-005 | 正常修改密码 | oldPassword=正确, newPassword=新密码 | 200 |
| USER-006 | 原密码错误 | oldPassword=错误 | 400, "原密码错误" |

### 2.3 兑换积分券 `POST /api/user/coupons/redeem`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| USER-007 | 正常兑换 | code=有效兑换码 | 200, 返回获得积分数 |
| USER-008 | 兑换码不存在 | code=无效码 | 400, "兑换码不存在" |
| USER-009 | 兑换码已过期 | code=过期码 | 400, "该兑换码已过期" |
| USER-010 | 达到使用上限 | code=已满码 | 400, "该兑换码已达到使用次数上限" |
| USER-011 | 兑换码已禁用 | code=禁用码 | 400, "该兑换码已被禁用" |

---

## 三、管理员模块

### 3.1 仪表盘 `GET /api/admin/dashboard`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| ADMIN-001 | 管理员查看仪表盘 | admin 角色 | 200, 返回全局统计数据(渠道数、用户数、请求数等) |
| ADMIN-002 | 普通用户查看仪表盘 | user 角色 | 200, 仅返回自己的 Token 和使用数据, 含 myCredits |

### 3.2 用户管理

| 用例编号 | 测试场景 | 接口 | 预期结果 |
|---------|---------|------|---------|
| ADMIN-003 | 搜索用户列表 | GET /api/admin/users?search=keyword | 200, 返回匹配用户列表 |
| ADMIN-004 | 获取全部用户 | GET /api/admin/users | 200, 返回全部用户 |
| ADMIN-005 | 更新用户状态 | PUT /api/admin/users/{id}/status | 200, 状态更新成功 |
| ADMIN-006 | 更新不存在用户状态 | PUT /api/admin/users/99/status | 400, "用户不存在" |
| ADMIN-007 | 重置用户密码 | PUT /api/admin/users/{id}/reset-password | 200, 密码重置为默认值 |
| ADMIN-008 | 更新用户积分 | PUT /api/admin/users/{id}/credits | 200, 积分更新成功 |

### 3.3 使用日志 `GET /api/admin/logs`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| ADMIN-009 | 分页查询日志 | page=0, size=20 | 200, 返回分页日志数据 |

### 3.4 积分券管理

| 用例编号 | 测试场景 | 接口 | 预期结果 |
|---------|---------|------|---------|
| ADMIN-010 | 生成积分券 | POST /api/admin/coupons | 200, 返回生成的积分券(含 code) |
| ADMIN-011 | 查看积分券列表 | GET /api/admin/coupons | 200, 返回积分券列表 |
| ADMIN-012 | 查看使用记录 | GET /api/admin/coupons/{id}/redemptions | 200, 返回兑换记录 |
| ADMIN-013 | 禁用/启用积分券 | PUT /api/admin/coupons/{id}/status | 200, 状态更新成功 |

---

## 四、渠道管理模块

### 4.1 渠道 CRUD

| 用例编号 | 测试场景 | 接口 | 预期结果 |
|---------|---------|------|---------|
| CH-001 | 获取渠道列表 | GET /api/admin/channels | 200, 返回全部渠道 |
| CH-002 | 空列表 | GET /api/admin/channels | 200, 返回空数组 |
| CH-003 | 获取单个渠道 | GET /api/admin/channels/{id} | 200, 返回渠道详情 |
| CH-004 | 获取不存在渠道 | GET /api/admin/channels/99 | 400, "渠道不存在" |
| CH-005 | 创建渠道 | POST /api/admin/channels | 200, 返回创建的渠道 |
| CH-006 | 更新渠道 | PUT /api/admin/channels/{id} | 200, 返回更新后的渠道 |
| CH-007 | 更新不存在渠道 | PUT /api/admin/channels/99 | 400, "渠道不存在" |
| CH-008 | 删除渠道 | DELETE /api/admin/channels/{id} | 200 |
| CH-009 | 删除不存在渠道 | DELETE /api/admin/channels/99 | 400, "渠道不存在" |
| CH-010 | 更新渠道状态 | PUT /api/admin/channels/{id}/status | 200, 状态更新成功 |

### 4.2 渠道连通性测试 `POST /api/admin/channels/{id}/test`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| CH-011 | 测试成功 | 有效渠道 ID | 200, data=true |
| CH-012 | 测试失败 | 无效配置渠道 ID | 200, data=false |
| CH-013 | 渠道不存在 | ID=99 | 400, "渠道不存在" |

### 4.3 获取上游模型列表 `POST /api/admin/channels/fetch-models`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| CH-014 | 正常获取模型 | baseUrl, apiKey, type=openai | 200, 返回上游模型 ID 列表 |
| CH-015 | Claude 类型获取 | baseUrl, apiKey, type=claude | 200, 使用 x-api-key 认证 |
| CH-016 | 缺少 baseUrl | apiKey, type | 400, "请先填写 Base URL 和 API Key" |
| CH-017 | 缺少 apiKey | baseUrl, type | 400, "请先填写 Base URL 和 API Key" |
| CH-018 | 上游接口异常 | 无效 baseUrl | 400, "连接上游失败: ..." |

### 4.4 渠道聊天测试 `POST /api/admin/channels/test-chat`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| CH-019 | OpenAI 协议测试 | type=openai, model, message | 200, 返回 success/content/duration |
| CH-020 | Claude 协议测试 | type=claude, model, message | 200, 返回 success/content/duration/usage |
| CH-021 | 缺少 baseUrl | 无 baseUrl | 400, "请先填写 Base URL 和 API Key" |
| CH-022 | 缺少模型 | 无 model | 400, "请选择模型" |
| CH-023 | 上游返回错误 | 无效 apiKey | 200, success=false, 含 error 信息 |

---

## 五、模型配置模块

### 5.1 模型 CRUD

| 用例编号 | 测试场景 | 接口 | 预期结果 |
|---------|---------|------|---------|
| MC-001 | 获取全部模型(管理员) | GET /api/admin/models (admin) | 200, 返回全部模型(含 adminOnly) |
| MC-002 | 获取模型(普通用户) | GET /api/admin/models (user) | 200, 仅返回 adminOnly=false |
| MC-003 | 空模型列表 | GET /api/admin/models | 200, 返回空数组 |
| MC-004 | 获取启用模型 | GET /api/admin/models/enabled | 200, 仅返回 status=1 |
| MC-005 | 创建模型 | POST /api/admin/models | 200, 返回创建的模型 |
| MC-006 | 创建模型-缺少名称 | POST /api/admin/models (无 name) | 400, "模型名称不能为空" |
| MC-007 | 创建模型-空白名称 | POST /api/admin/models (name="  ") | 400, "模型名称不能为空" |
| MC-008 | 创建模型-默认比例 | POST /api/admin/models (无 rate) | 200, inputCreditRate=0, outputCreditRate=0 |
| MC-009 | 创建模型-含 displayName | POST /api/admin/models (含 displayName) | 200, displayName 正确保存 |
| MC-010 | 更新模型 | PUT /api/admin/models/{id} | 200, 返回更新后的模型 |
| MC-011 | 更新不存在模型 | PUT /api/admin/models/99 | 400, "模型不存在" |
| MC-012 | 删除模型 | DELETE /api/admin/models/{id} | 200 |
| MC-013 | 删除不存在模型 | DELETE /api/admin/models/99 | 400, "模型不存在" |
| MC-014 | 更新模型状态 | PUT /api/admin/models/{id}/status | 200 |
| MC-015 | 更新不存在模型状态 | PUT /api/admin/models/99/status | 400, "模型不存在" |

### 5.2 批量创建 `POST /api/admin/models/batch`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| MC-016 | 批量创建 | names=["gpt-4o", "gpt-4o-mini"] | 200, 返回 2 个模型 |
| MC-017 | 空列表 | names=[] | 400, "模型名称列表不能为空" |
| MC-018 | null 列表 | {} | 400, "模型名称列表不能为空" |
| MC-019 | 过滤空白名称 | names=["gpt-4o", "", "  "] | 200, 仅返回 1 个模型 |

---

## 六、Token 管理模块

### 6.1 Token CRUD

| 用例编号 | 测试场景 | 接口 | 预期结果 |
|---------|---------|------|---------|
| TK-001 | 管理员查看 Token 列表 | GET /api/tokens (admin) | 200, 返回全部 Token, 含 ownerName |
| TK-002 | 普通用户查看 Token | GET /api/tokens (user) | 200, 仅返回自己的 Token |
| TK-003 | 按账号搜索 Token | GET /api/tokens?search=keyword | 200, 返回匹配的 Token |
| TK-004 | 空 Token 列表 | GET /api/tokens | 200, 返回空数组 |
| TK-005 | 获取单个 Token | GET /api/tokens/{id} | 200, 返回 Token 详情 |
| TK-006 | 获取不存在 Token | GET /api/tokens/99 | 400, "Token 不存在" |
| TK-007 | 创建 Token | POST /api/tokens | 200, 返回 Token(含 tokenKey) |
| TK-008 | 更新 Token | PUT /api/tokens/{id} | 200, 返回更新后的 Token |
| TK-009 | 更新不存在 Token | PUT /api/tokens/99 | 400, "Token 不存在" |
| TK-010 | 删除 Token | DELETE /api/tokens/{id} | 200 |
| TK-011 | 删除不存在 Token | DELETE /api/tokens/99 | 400, "Token 不存在" |
| TK-012 | 更新 Token 状态 | PUT /api/tokens/{id}/status | 200 |
| TK-013 | 非所有者操作 Token | PUT /api/tokens/{其他用户id} | 403, "无权操作该 Token" |

### 6.2 Token 消耗历史 `GET /api/tokens/{id}/credit-history`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| TK-014 | 查看消耗历史 | 自己的 Token ID | 200, 返回每日消耗列表 |
| TK-015 | 无权查看 | 其他用户的 Token | 403, "无权查看该 Token 的消耗记录" |

### 6.3 Token 聊天测试 `POST /api/tokens/test-chat`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| TK-016 | OpenAI 协议测试 | protocol=openai, tokenKey, model, message | 200, success=true, content, duration, protocol=openai |
| TK-017 | Claude 协议测试 | protocol=claude, tokenKey, model, message | 200, success=true, content, duration, protocol=claude, usage |
| TK-018 | 缺少 Token Key | 无 tokenKey | 400, "缺少 Token Key" |
| TK-019 | 缺少模型 | 无 model | 400, "请选择模型" |
| TK-020 | displayName 转换 | model=displayName | 200, 正确解析为实际模型名后转发 |
| TK-021 | 请求失败 | 无效 tokenKey | 200, success=false, 含 error 信息 |
| TK-022 | 默认消息 | message=null | 200, 使用默认消息 "hi" |

---

## 七、OpenAI 兼容 API 模块

### 7.1 Chat Completions `POST /v1/chat/completions`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| API-001 | 正常聊天 | Authorization: Bearer sk-xxx, model, messages | 200, 返回 OpenAI 格式响应 |
| API-002 | 流式响应 | stream=true | SSE 流式返回 |
| API-003 | displayName 转换 | model=显示名称 | 自动转换为实际模型名转发 |
| API-004 | 缺少认证 | 无 Authorization header | 401, "缺少 Authorization header" |
| API-005 | 无效 Token | Bearer 无效key | 401, "无效的 Token" |
| API-006 | Token 已禁用 | Bearer 禁用key | 403, "Token 已被禁用" |
| API-007 | Token 额度用完 | quota 已用完 | 429, "Token 额度已用完" |
| API-008 | 无可用渠道 | 模型无对应渠道 | 503, "没有可用的渠道支持模型" |
| API-009 | 渠道容错 | 第一个渠道失败 | 自动切换到下一个渠道 |

### 7.2 Completions `POST /v1/completions`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| API-010 | 正常请求 | Authorization, model, prompt | 200, 返回响应 |

### 7.3 Embeddings `POST /v1/embeddings`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| API-011 | 正常请求 | Authorization, model, input | 200, 返回 embedding 向量 |

### 7.4 Images `POST /v1/images/generations`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| API-012 | 图片生成 | Authorization, prompt | 200, 返回图片 URL |

### 7.5 Audio `POST /v1/audio/transcriptions`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| API-013 | 语音转写 | Authorization, file | 200, 返回转写文本 |

### 7.6 Models List `GET /v1/models`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| API-014 | 管理员查看模型 | admin Token | 200, 返回全部启用模型(含 adminOnly), 与渠道交集 |
| API-015 | 普通用户查看模型 | user Token | 200, 仅返回 adminOnly=false 且渠道支持的模型 |
| API-016 | 模型返回 displayName | 有 displayName 的模型 | id 字段返回 displayName |
| API-017 | 渠道交集过滤 | 渠道仅支持部分模型 | 仅返回渠道支持的模型 |

---

## 八、Claude Code 协议模块

### 8.1 Messages API `POST /v1/messages`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| CC-001 | x-api-key 认证 | x-api-key: sk-xxx | 200, 正常转发并返回 |
| CC-002 | Bearer 认证 | Authorization: Bearer sk-xxx | 200, 正常转发并返回 |
| CC-003 | 无认证信息 | 无 x-api-key 和 Authorization | 401, "缺少认证信息" |
| CC-004 | 非流式请求 | stream=false | 200, 返回完整 JSON 响应 |
| CC-005 | 流式请求 | stream=true | SSE 流式返回 |
| CC-006 | displayName 转换 | model=显示名称 | 自动转换为实际模型名 |
| CC-007 | 渠道类型自适应认证 | claude 类型渠道 | 使用 x-api-key 认证头 |
| CC-008 | 渠道类型自适应认证 | openai 类型渠道 | 使用 Bearer 认证头 |

### 8.2 Token Counting `POST /v1/messages/count_tokens`

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| CC-009 | Token 计数 | 有效请求 | 200, 返回 token 计数结果 |

---

## 九、模型名称转换机制

### 9.1 displayName ↔ name 双向转换

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| MN-001 | name 直接匹配 | 输入实际 name | resolveModelName 直接返回 |
| MN-002 | displayName 转 name | 输入 displayName | resolveModelName 返回实际 name |
| MN-003 | 未找到匹配 | 输入未知名称 | 保持原值返回 |
| MN-004 | name 转 displayName | 输入 name | resolveToChannelName 返回 displayName |
| MN-005 | 无 displayName | name 存在但 displayName 为 null | resolveToChannelName 返回 name |
| MN-006 | 空值处理 | null 或空字符串 | 直接返回原值 |

---

## 十、积分计费模块

### 10.1 积分计算

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| CR-001 | 正常计费 | model=gpt-4, prompt=1000, completion=500 | inputRate*1 + outputRate*0.5 |
| CR-002 | 零 token | prompt=0, completion=0 | 0.0 |
| CR-003 | 模型不存在 | model=未知模型 | 0.0 |
| CR-004 | 小数精度 | 大量 token | 浮点精确计算 |

---

## 十一、渠道容错与限流

### 11.1 渠道容错

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| FO-001 | 首个渠道 500 错误 | 第一渠道返回 500 | 自动切换到下一渠道 |
| FO-002 | 首个渠道 502 错误 | 第一渠道返回 502 | 自动切换到下一渠道 |
| FO-003 | 首个渠道 400 错误 | 第一渠道返回 400 | 不重试，直接返回错误 |
| FO-004 | 所有渠道失败 | 全部渠道不可用 | 503, "所有渠道均不可用" |
| FO-005 | 网络异常 | 渠道连接超时 | 自动切换到下一渠道 |

### 11.2 限流

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| RL-001 | Token 限流 | 超过 Token rateLimit | 拒绝请求 |
| RL-002 | 渠道限流 | 超过渠道 rateLimit | 跳过该渠道，尝试下一个 |
| RL-003 | 无限流配置 | rateLimit=0 | 不限制 |

---

## 十二、安全与权限

| 用例编号 | 测试场景 | 输入 | 预期结果 |
|---------|---------|------|---------|
| SEC-001 | 未认证访问受保护接口 | 无 JWT | 401 |
| SEC-002 | 无效 JWT | 伪造 token | 401 |
| SEC-003 | 普通用户访问 admin 接口 | user 角色 | 403 |
| SEC-004 | API 接口公开访问 | /v1/** 接口 | 无需 JWT, 使用 Bearer Token 认证 |
| SEC-005 | Token 模型权限 | Token 设置 allowedModels | 无权模型返回 403 |
| SEC-006 | Token 所有者校验 | 非所有者操作 Token | 403, "无权操作该 Token" |
