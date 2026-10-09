# 接口与运行配置

## 依赖和构建

使用 JDK 21+、Maven 3.9+。父 POM 统一 Spring Boot、Spring Cloud 与 MCP SDK 版本。只构建某个启动器时带上 Maven 的 -am，使依赖库同时构建：

~~~bash
mvn -B -ntp verify
mvn -B -ntp -pl apps/gateway-server -am package
~~~

第二条生成 Gateway 及依赖模块；正常完整链路需要四个应用都启动。首次启动前配置下表中的凭据。未配置密钥时启动失败或内部调用失败，不使用仓库里的默认密码。

## 配置项

应用 YAML 提供统一命名；各进程只需要其职责相关的配置。

| 环境变量 | 使用方 | 含义 |
|---|---|---|
| BIND_ADDRESS | 全部 | 默认 127.0.0.1 |
| SERVER_PORT | 全部 | 各启动器端口，可覆盖 |
| ADMIN_TOKEN | Admin | 初始管理员令牌，至少 32 字符 |
| CONTROL_READ_TOKEN | 全部 | 读取目录快照的内部令牌，至少 32 字符 |
| DISCOVERY_TOKEN | Admin / Discovery | 提交发现观察及触发同步的令牌，至少 32 字符 |
| RUNTIME_SIGNING_SECRET | Gateway / Runtime | 内部执行许可 HMAC 密钥，至少 32 字符 |
| CONTROL_URL | Gateway / Discovery / Runtime | 管理服务地址，默认 http://127.0.0.1:8081 |
| RUNTIME_URL | Gateway | 转接服务地址，默认 http://127.0.0.1:8083 |
| CATALOG_FILE | Admin | 快照文件，默认 ./data/catalog.json；持久环境需独立受控目录 |
| ALLOWED_UPSTREAM_HOSTS | Admin | MCP 地址主机允许列表，逗号分隔；默认仅 localhost、127.0.0.1 |
| DISCOVERY_INTERVAL_MS | Discovery | 同步周期，默认 10000 |
| SNAPSHOT_REFRESH_MS | Gateway / Runtime | 快照刷新周期，默认 2000 |
| SNAPSHOT_MAX_AGE_MS | Gateway / Runtime | 快照租约，默认 15000，不得小于刷新周期 |
| IDENTITY_MODE | Gateway | 默认 jwt；demo 仅供本地演示 |
| JWT_ISSUER | Gateway | 可信 token 签发来源 |
| JWT_JWKS_URI | Gateway | 管理员配置的可信公钥集地址 |
| JWT_AUDIENCE | Gateway | 本平台预期受众 |
| JWT_TENANT | Gateway | 默认适配器映射到的组织 |
| JWT_ROLES_CLAIM | Gateway | 角色数组 claim 名，默认 roles |
| MCP_ALLOWED_ORIGINS | Gateway | 带 Origin 的客户端请求必须匹配此允许列表 |
| MCP_UPSTREAM_* | Discovery / Runtime | 上游专用 Authorization 值；由服务的 credentialRef 指定名称 |

ADMIN_TOKEN、CONTROL_READ_TOKEN、DISCOVERY_TOKEN 必须互不相同。为不同用途生成独立随机值并通过部署密钥系统注入；不要写入仓库。

默认上游限制按主机名，禁止 URL 中携带用户密码、查询和片段，连接器不跟随重定向。生产中还应限制网络出口及可信 DNS，不能把字符串主机允许列表当作完整网络隔离。

设置凭据与 JWT 配置后，在四个终端分别启动：

~~~bash
java -jar apps/admin-server/target/admin-server-0.1.0-SNAPSHOT.jar
java -jar apps/discovery-server/target/discovery-server-0.1.0-SNAPSHOT.jar
java -jar apps/runtime-server/target/runtime-server-0.1.0-SNAPSHOT.jar
java -jar apps/gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar
~~~

初次目录尚未加载时用户请求返回 503。Actuator health 只表示应用进程基本健康，不代表有可用目录或所有上游健康。正式就绪判断需要结合目录租约和上游状态监控。

## 管理与内部 API

所有表内接口使用 Authorization: Bearer 凭据；三种管理侧凭据按用途隔离。成功的目录变更返回完整 Snapshot。

| 方法与路径 | 所在应用 / 凭据 | 用途 |
|---|---|---|
| GET / | Admin / 无 | 静态控制台；不含目录数据 |
| GET /api/snapshot | Admin / ADMIN_TOKEN | 查看全部目录、分组和授权 |
| POST /api/services | Admin / ADMIN_TOKEN | 新增或更新服务注册 |
| POST /api/groups | Admin / ADMIN_TOKEN | 新增或更新能力组 |
| POST /api/tools/{id}/publish | Admin / ADMIN_TOKEN | 审核发布，正文指定 resourceProtected |
| POST /api/tools/{id}/disable | Admin / ADMIN_TOKEN | 停用工具 |
| POST /api/grants | Admin / ADMIN_TOKEN | 创建或替换授权 |
| DELETE /api/grants/{id} | Admin / ADMIN_TOKEN | 撤权 |
| GET /internal/snapshot | Admin / CONTROL_READ_TOKEN | 供网关、发现、运行读取完整快照 |
| POST /internal/discovery | Admin / DISCOVERY_TOKEN | 提交全量发现观察 |
| POST /internal/sync | Discovery / DISCOVERY_TOKEN | 手动同步，正文可指定 serviceId；空对象同步全部 |
| GET /internal/status | Discovery / DISCOVERY_TOKEN | 最近同步成功或失败及时间 |

服务注册示例：

~~~json
{
  "id": "orders",
  "tenant": "acme",
  "endpoint": "https://orders.example/mcp",
  "transport": "streamable-http",
  "credentialRef": "MCP_UPSTREAM_ORDERS",
  "enabled": true
}
~~~

先将真实主机加入 ALLOWED_UPSTREAM_HOSTS，配置相应上游服务凭据。发现成功后，从目录读取真实工具 id，再提交组：

~~~json
{
  "id": "order-operations",
  "tenant": "acme",
  "title": "订单运营",
  "description": "供管理员维护的完整能力说明",
  "tools": {
    "find_order": "目录中的查询工具标识",
    "refund_order": "目录中的退款工具标识"
  }
}
~~~

组可引用多个服务的工具，但必须属于同一组织。title 是面向用户的统一名称；description 是完整管理说明。对外 instructions 只根据统一名称和可见工具名生成，工具描述来自实际可见定义，不把完整组说明直接泄露给权限较少的用户。

发布正文例如 {"resourceProtected": true}。发布后，授权必须携带目录中的当前 toolRevision：

~~~json
{
  "id": "alice-query",
  "tenant": "acme",
  "subjectType": "USER",
  "subject": "alice",
  "issuer": "https://identity.example",
  "groupId": "order-operations",
  "toolId": "目录中的查询工具标识",
  "toolRevision": 1
}
~~~

subjectType 可为 USER 或 ROLE。groupId 省略或为 null 表示授权可在所有引用该工具的组中使用；指定组时不能跨组复用权限。新增组本身不产生任何 Grant。

工具标识、修订和定义由发现服务管理，不应人工制造。修改服务地址、凭据引用或启用状态时，旧定义先不可用，必须对新配置重新发现。

## Agent 的 MCP 接入

每个能力组的统一入口是网关 /mcp/{groupId}。使用企业身份系统签发给平台的访问令牌；demo 令牌只能在显式 demo profile 与回环监听下使用。

对外支持无会话、JSON 响应的 Streamable HTTP 工具子集：

1. POST initialize，携带 protocolVersion、capabilities、clientInfo。
2. POST notifications/initialized，无 id，返回 202。
3. POST tools/list、tools/call 或 ping，携带 JSON-RPC id。
4. GET 不建立 SSE 流，返回 405；没有会话 ID，也不提供 SSE 重连恢复。
5. 列表一次返回全部可见工具，不接受分页 cursor。上游发现仍支持分页。
6. 初始化不宣称 resources、prompts、sampling、tasks 或工具变化通知能力。

请求 Content-Type 应为 application/json。主体最大 256 KiB；过大请求被拒绝。内部签名许可也有大小上限，超大能力组需要拆分或实现分页 / 外部许可存储。

JSON-RPC 成功示例：

~~~json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
~~~

~~~json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_order","arguments":{"orderId":"123"}}}
~~~

工具定义的输入 schema 在转发前校验，仅允许本地 JSON Schema 引用。业务工具返回的 isError 会保留；传输、认证、配置和网关准入错误采用下表的 HTTP 错误响应。当前实现没有声称完整 JSON-RPC / MCP 错误一致性。

| 状态 | 典型含义 |
|---|---|
| 400 | RPC 格式、参数或 schema 不合法；不支持的方法 / cursor |
| 401 | 用户身份、内部凭据或执行许可无效 |
| 403 | 工具 / 资源 / 组织 / 组作用域未授权，或 Origin 被拒绝 |
| 409 | 网关许可与 Runtime 快照版本不同；未调用上游 |
| 413 | 请求体或内部许可过大 |
| 415 | 非 JSON 内容类型 |
| 502 | 上游发现或调用失败 |
| 503 | 目录尚未加载、租约失效或控制面不可用 |

配置版本变化时可以重新获取工具列表。对有副作用的业务调用不要自动重试；超时并不能证明上游没有执行。企业业务需要端到端幂等时，应显式设计业务幂等键和去重，不应在通用转接器里盲目重试。

## 验证和已知限制

- Maven 单元测试覆盖目录状态、失败不删除、旧修订授权、跨组织和跨组隔离、资源权限、签名篡改 / 过期 / 重放、快照租约，以及真实 JWT 签名和声明验证。
- scripts/smoke.py 启动四个真实应用，通过官方 Java SDK 连接业务 MCP 测试服务，并完成 HTTP 端到端链路。
- 初始目录存储不提供多进程写锁或事务数据库。不要运行两个 Admin 写同一文件。
- 发现进程初始实现串行同步服务，适用于框架基线；大规模同步需增加有界并发、调度隔离和限流。
- Runtime 使用每次调用建立的 SDK 客户端，优先避免会话串用户；高吞吐连接池、会话复用和容量治理是后续扩展。
- 服务配置只支持 streamable-http，未提供 stdio / 旧版 HTTP+SSE 适配。
