# 三端认证骨架与业务身份传递

当前交付为框架：三端类型隔离、认证方法骨架和 Runtime 到上游 MCP 的身份传递已接入。Redis、钉钉及 API Key 的真实校验尚未实现，正式应用不能成功认证外部调用。JWT／demo 已退出当前方案。

## 入口与主体

地址继续使用 `/mcp/{groupId}`。管理员为每个能力组和授权记录填写 `clientType`，可选 `CONSUMER`、`EMPLOYEE`、`APPLICATION`。三端业务工具／API 分别组织，不因同属一个治理平台就合并业务接口。

Gateway 从目录取得分组类型及组织，再创建 AuthenticationRequest；不接受调用方自行声明的端类型。认证结果 Caller 包含业务主体 `subject`、组织 `tenant`、身份来源 `issuer`、角色 `roles` 和 `clientType`。所有直接主体授权及角色授权都匹配端类型，不同端的相同 ID 不共享权限。

`SubjectType.USER` 保留原枚举值，表示对单个业务主体的直接授权；主体是用户、员工还是应用，由 `clientType` 决定。`ROLE` 表示同一端、同一组织和来源下的角色授权。

| 接入端 | 请求头 | 认证骨架 | 后续实现位置 |
|---|---|---|---|
| CONSUMER | `token`、`channel` | 解析 `userId:expireTime:random[:appidCode]`，检查格式及毫秒有效期，构造 `AUTH:USERCENTER:userId:channel[:appidCode]` | `ConsumerIdentityProvider.validateLoginState` |
| EMPLOYEE | `Authorization: Bearer <credential>` | 验证企业登录凭证后映射业务员工 | `EmployeeIdentityProvider.verifyCredential`、`mapEmployee` |
| APPLICATION | `X-API-Key` | 验证 Key 并映射稳定第三方／应用主体 | `ApiKeyIdentityProvider.validateApiKey` |

不从查询串获取凭证，保留当前 MCP 入口不接受查询串的约束。缺失／格式错误／已过期的 C 端 Token 返回 `401 invalid_identity`。格式合法并到达未实现校验方法时，返回 `501 authentication_not_implemented`；不返回假用户，不因认证存储超时而放行。

AuthenticationRequest 只在网关认证阶段使用，不写入签名许可或上游请求；其 toString 隐去凭证。后续适配代码同样不得记录原始凭证或带凭证的底层异常。

## C 端参考与待办

骨架参考现有 `aomi-base-gateway` 的 ClientAuthenticationFilter，当前项目未修改该工程。用户 ID、channel 和 appidCode 必须是可用于键分段的值；随机段不能为空。这里只保留解析、有效期与 Redis 键构造，不连接 Redis。

- 实现 TokenData 读取及 `accessToken` 比对；已删除、失效或撤销的 Token 拒绝。
- Redis 连接／读取失败不能返回解析得到的用户 ID，应映射为不放行的依赖异常。
- 确认实际业务部署的组织归属和基础角色策略；目前成功分支使用分组组织、`aomi-usercenter` 来源和空角色。
- 完成与既有用户登录态的真实联调后，才能宣称 C 端认证已接入。

## 员工与应用待办

员工由钉钉等企业身份系统完成登录，网关负责验证凭证和账号映射。verifyCredential 只返回已验证的外部身份，mapEmployee 再生成业务员工 ID、组织和角色。企业外部账号 ID 不直接充当业务员工 ID；未完成映射不得放行。本次不建设登录页面、回调或授权码交换流程。

API Key 校验需要读取受控存储中的密钥摘要、有效期、撤销状态及应用启用状态。成功返回的 subject 为稳定的应用／第三方 ID，不是 Key 本身。Key 轮换沿用主体和授权，管理、持久化及发放流程后续实现。

默认 Gateway 装配 RoutingIdentityProvider。企业实现可在 Gateway 显式装配一个替代 IdentityProvider，组合三类骨架的实现；无需新增部署服务。测试替身仅在测试启动器中注册，正式应用无 demo 配置开关。

## 下游身份契约

Gateway 检查工具权限后，将 Caller 写入绑定请求的签名许可。Runtime 校验许可和快照版本，调用 `McpConnector.invoke(service, tool, arguments, caller)`。SDK 连接器每次调用创建独立请求配置，生成以下身份头：

| 端类型 | 业务身份头 | 类型头 |
|---|---|---|
| CONSUMER、EMPLOYEE | `X-AOMI-AUTH-UID: <业务用户或员工 ID>` | `X-Mcp-Client-Type: CONSUMER / EMPLOYEE` |
| APPLICATION | `x-aomi-third-appid: <业务第三方或应用 ID>` | `X-Mcp-Client-Type: APPLICATION` |

员工与消费者复用通用用户注解契约，第三方沿用 ThirdAppId 契约。身份头不是签名令牌：上游必须验证平台服务凭据，并限制绕过平台的直接调用，才可将其作为可信身份。当前连接器没有签发供 Bridge 独立验签的业务委托令牌。

客户端提供的 `X-AOMI-*`、`current-user-aomi-merchant`、`auth-user-id` 和类型头会在网关清理；入口 Authorization、token、channel、X-API-Key 也不转发。上游 Authorization 单独取自 CredentialProvider，该提供者不能覆盖业务身份头。发现连接不携带终端主体。

上游 Bridge 的来源验证、MCP 工具参数注解注入、异步上下文及 Feign 传递仍需其所属项目完成；这里不假定 Spring MVC 的参数解析器能够直接处理 MCP 工具方法。业务数据范围、对象归属和业务状态仍由业务端校验，身份头本身不会过滤数据。

## 旧目录迁移与验证边界

旧 JSON 缺少 clientType 时允许读取，管理页面显示“待配置”。新建或保存分组、授权必须明确填写类型；类型为空的分组请求返回 `503 group_identity_not_configured`，类型为空的旧授权不生效。不自动把旧数据归入 C 端，也不重写历史存储。

管理员先编辑分组选择正确端，再编辑对应授权选择相同端并保存当前工具修订。跨组授权同样只作用于匹配的端。一次升级同时更新四个启动器，不混用不带 clientType 的旧执行许可。

Maven 测试验证骨架拒绝放行、键构造、端隔离及旧数据读取。四进程脚本通过测试专用 GatewayFixture 注入身份，验证目录、授权和上游身份传递，并另启动正式 Gateway 验证三端 501。测试成功不代表真实认证或外部 Bridge 已接通。
