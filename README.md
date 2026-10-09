# MCP Governance Platform

基于 Spring 的模块化 MCP 治理框架：**一个仓库、一个 Maven 父工程、四个独立启动器**。集中接入多个业务 MCP 服务，按能力分组，对不同用户返回不同工具列表，并对每次调用重新授权。

[用户诉求](REQUIREMENTS.md) · [项目架构](ARCHITECTURE.md) · [十一张架构 / UML / 交互图](docs/DIAGRAMS.md) · [扩展开发](docs/EXTENSIONS.md) · [接口与运行配置](docs/OPERATIONS.md) · [审查与待办](TODO.md)

![总体架构](docs/diagrams/architecture.svg)

## 先看哪些内容

- 根目录 **REQUIREMENTS.md**：用户诉求、已确认约束和首版范围。
- 根目录 **ARCHITECTURE.md**：模块职责、运行链路、认证与授权边界、一致性模型。
- **docs/DIAGRAMS.md**：总体架构、领域模型、工具状态，以及八个场景的时序图。SVG 可直接查看、下载和编辑。
- **apps/**：只包含应用装配、入口、安全过滤、HTTP 接口及管理页面。
- **modules/**：领域逻辑和扩展接口，可被启动器组合复用。

## 工程划分

| 启动器 | 默认地址 | 主要职责 |
|---|---|---|
| gateway-server | 127.0.0.1:8080 | Spring Cloud Gateway 入口，统一认证、列表过滤、调用授权 |
| discovery-server | 127.0.0.1:8082 | 连接业务 MCP，完整工具发现与增删改同步 |
| runtime-server | 127.0.0.1:8083 | 虚拟 MCP 能力组、用户说明、协议响应及后端转接 |
| admin-server | 127.0.0.1:8081 | 服务配置、目录审核、分组、授权、管理界面和持久化 |

四个启动器之间走受保护的 HTTP 接口，**没有启动器之间的 Maven 依赖**。公共库不会打包成独立进程。

| 库模块 | 使用方 | 说明 |
|---|---|---|
| platform-contracts | 全部 | 不可变领域契约、JSON、RPC 基础验证 |
| identity-spi | identity-adapters、网关 | IdentityProvider / IdentityMapper |
| identity-adapters | 网关 | 验证签名、有效期、来源和受众的 JWT 接入；受限 demo 身份 |
| authorization-core | 网关 | 显式工具授权、组织和来源隔离、资源范围接口 |
| catalog-core | 管理后台 | 工具生命周期、分组和授权，CatalogStore 持久化接口 |
| mcp-connector-spi | 发现、运行核心 | MCP 连接器与上游凭据接口 |
| mcp-connector-sdk | 发现、运行启动器 | 官方 MCP Java SDK 的 Streamable HTTP 连接器 |
| control-client | 网关、发现、运行 | 管理面客户端与带过期时间的版本化快照 |
| service-security | 四个启动器 | 内部接口令牌、请求绑定签名与重放校验 |
| audit-core | 四个启动器 | 脱敏审计接口与结构化日志实现 |
| discovery-core | 发现 | 全部分页成功后提交观察 |
| runtime-core | 运行 | 能力组映射、可见工具视图和调用转接 |

## 已实现的完整链路

1. 管理员登记业务 MCP 服务。发现服务初始化连接，读取所有工具分页后才提交目录。
2. 新增、修改或重新出现的工具进入待审核状态；审核发布后仍需授权对应修订。
3. 管理员跨服务组织能力组，配置工具对外别名，授予用户或角色组内 / 全组复用权限。
4. Agent 访问网关的 `/mcp/{groupId}`。认证组件生成统一调用者，网关使用当前快照判断可用工具。
5. Runtime 只接受网关签发的短期、请求绑定执行许可，生成该用户的工具列表和说明，或调用原始 MCP 工具。
6. 管理员撤权或停用工具后，新快照同时影响列表和真实调用。旧列表不构成授权依据。

业务 MCP 凭据独立配置，用户 Bearer 不透传。标记为资源敏感的工具会执行资源授权接口；默认实现拒绝。

## 本地构建与演示

要求 **JDK 21+、Maven 3.9+**；演示和集成验证另外需要 Python 3.10+，不需要第三方 Python 包。

~~~bash
mvn -B -ntp verify
python3 scripts/smoke.py
~~~

集成脚本会启动四个真实 JVM 进程以及测试用 MCP 服务，并使用官方 MCP Java 客户端连接网关，自动验证目录同步、发布、分组、不同用户列表、调用与拒绝、撤权、定义变更、同步失败保留目录、持久化重启、快照过期拒绝。

查看可操作的本地管理界面：

~~~bash
python3 scripts/smoke.py --demo
~~~

脚本使用随机空闲端口和临时凭据。启动后查看本地被 Git 忽略的 `run/demo-access.json`：其中包含管理页面地址，以及管理员、Alice、Bob 的演示令牌。打开管理页面，输入管理员令牌连接。Alice 可查询订单，Bob 的角色可使用退款工具。按 Ctrl+C 停止进程并清理演示数据；该演示环境不用于保存正式配置。

需要固定地址、JWT 接入或持久化数据时，参阅[运行配置](docs/OPERATIONS.md)。每个应用都生成单独的可执行 JAR，可独立启动。

## 认证方式如何扩展

认证解决“是谁”，授权解决“能做什么”。调用者以 **组织 + 身份来源 + 外部主体** 隔离，角色来自验证过的身份声明。

- 默认 JWT 适配器验证签名、issuer、audience 和有效期，适用于企业身份系统签发给本平台的访问凭证。
- 钉钉或其他企业登录在 `IdentityProvider / IdentityMapper` 接入。真实登录的授权码交换、应用密钥、回调和账号绑定需要企业配置，本项目没有冒充已接入。
- 外部权限系统可替换 `AuthorizationProvider`；资源级权限接在 `ResourceAccessChecker`。
- 上游服务凭据通过 `CredentialProvider` 获取，目录仅保存凭据引用。

这些变化不要求重写目录、发现、分组和 MCP 转接模块。详见[扩展示例及约束](docs/EXTENSIONS.md)。

## 一致性与安全边界

- 新发现 ≠ 已发布；加入组 ≠ 获得授权；能列出工具 ≠ 本次参数必然有资源权限。
- 工具语义、输入定义或来源配置改变后旧授权不会自动升级。工具定义只保留当前修订，不包含历史回滚系统。
- 网关和 Runtime 默认每 2 秒刷新一次完整快照，租约默认 15 秒。撤权存在传播窗口；刷新失败不会无限沿用旧权限。
- 许可与 Runtime 快照版本不一致返回 409，不执行上游调用；许可校验失败返回 401。
- 已经发往业务服务的操作不能追溯撤销。发生超时，不自动重试工具调用。
- 网关之外的业务 MCP 入口必须限制直连并验证平台凭据；网关无法保护仍然匿名暴露的旁路。
- 默认仅监听回环地址。跨机器部署需配置 TLS、服务入口访问控制、可信上游地址和企业密钥管理。

## 当前版本覆盖范围

| 能力 | 当前实现 |
|---|---|
| 管理后台 | 可操作的服务、目录、发布、分组、授权页面；初始管理员令牌模式 |
| 工具发现 | Streamable HTTP；定时与手动同步；完整分页；失败不误删 |
| 对外 MCP | 无会话 JSON 响应模式：initialize、initialized 通知、ping、tools/list、tools/call |
| MCP 工具返回 | 保留 content、structuredContent 和 isError 等上游 SDK 结果 |
| 列表变化通知 / SSE 恢复 | 未实现；不宣称 listChanged，客户端需重新获取列表 |
| resources / prompts / sampling / tasks | 未实现，不对外宣称这些能力 |
| OAuth 登录与元数据端点 | 未实现；接入已有身份系统的访问凭证，不冒充 OAuth 授权服务器 |
| 持久化 | 单管理进程写文件，原子替换；多副本需实现事务数据库 CatalogStore |
| 资源授权 | 扩展接口及默认拒绝；实际业务数据范围需要企业实现 |
| 重放防护 | Runtime 实例内短期 nonce；多副本需共享存储或等价机制 |
| 审计 | 脱敏结构化日志；持久收集、查询和保留策略由后续审计适配器实现 |

这是可运行的框架基线及验证场景，生产接入仍需落实上述企业相关实现。管理后台不包含独立用户目录；用户和角色标识由接入身份系统维护。

## 依赖与验证

基线使用 Java 21、Spring Boot 4.0.8、Spring Cloud 2025.1.3、官方 MCP Java SDK 2.0.1。父 POM 统一版本；JSON 契约隔离在共享模块，不依赖各启动器的 HTTP 序列化默认配置。

GitHub Actions 在每次推送和 Pull Request 执行 Maven 构建、核心单元测试和四进程集成验证，并保留测试报告及脱敏应用日志。验证状态以仓库最新工作流为准。

参考项目仅用于理解治理能力和实现边界：本项目没有直接复制一个完整网关产品，也没有引入额外注册中心。当前协议底座使用[官方 MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)，入口使用 [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)。
