# MCP Governance Platform

基于 Spring 的模块化 MCP 治理框架：**一个仓库、一个 Maven 父工程、四个独立启动器**。集中接入多个业务 MCP 服务，按能力分组，依据调用主体提供工具列表，并对每次调用重新授权。目标同时覆盖运营／员工、应用／第三方和 C 端，工具范围可以固定，也可以随授权调整。

[用户诉求](REQUIREMENTS.md) · [项目架构](ARCHITECTURE.md) · [接入场景与能力视图](docs/ACCESS-MODEL.md) · [架构 / UML / 交互图](docs/DIAGRAMS.md) · [扩展开发](docs/EXTENSIONS.md) · [接口与运行配置](docs/OPERATIONS.md) · [审查与待办](TODO.md)

![总体架构](docs/diagrams/architecture.svg)

## 先看哪些内容

- 根目录 **REQUIREMENTS.md**：用户诉求、已确认约束和首版范围。
- 根目录 **ARCHITECTURE.md**：模块职责、运行链路、认证与授权边界、一致性模型。
- **docs/DIAGRAMS.md**：总体架构、领域模型、工具状态，以及八个场景的时序图。SVG 可直接查看、下载和编辑。
- **apps/**：只包含应用装配、入口、安全过滤、HTTP 接口及管理页面。
- **modules/**：领域逻辑和扩展接口，可被启动器组合复用。

## 接入场景与设计状态

| 场景 | 目标策略 |
|---|---|
| 运营／公司员工 | 按岗位、角色及业务授权提供工具 |
| 应用／第三方 Key | 应用绑定能力组或套餐，默认稳定，仍可变更及撤销 |
| C 端用户 | 同类用户共用基础工具集；每次调用检查数据归属及业务条件 |

固定工具目录与用户数据隔离可以同时成立。三类场景复用同一套认证、授权、目录与转接内核，不按权限变化频率拆服务。

**入口为 `/mcp/{groupId}`，分组指定 CONSUMER／EMPLOYEE／APPLICATION。** 三端认证骨架及身份传递已接入；Redis 登录态、钉钉等员工凭证、API Key 的真实校验方法保留 TODO，当前正式应用会返回 501，不提供假身份或 demo 放行。三端业务 API 分别组织，下游复用用户／第三方注解契约。见[三端身份接入](docs/IDENTITY.md)。跨组 `/mcp`、完整 Doc、客户端自动刷新仍待实现。

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
| identity-spi | identity-adapters、网关 | IdentityProvider / AuthenticationRequest |
| identity-adapters | 网关 | C 端 Token、企业员工、API Key 三端认证骨架及路由 |
| authorization-core | 网关 | 显式工具授权、组织和来源隔离、资源范围接口 |
| catalog-core | 管理后台 | 工具生命周期、分组和授权，CatalogStore 持久化接口 |
| mcp-connector-spi | 发现、运行核心 | MCP 连接器与上游凭据接口 |
| mcp-connector-sdk | 发现、运行启动器 | 官方 MCP Java SDK 的 Streamable HTTP 连接器 |
| control-client | 网关、发现、运行 | 管理面客户端与带过期时间的版本化快照 |
| service-security | 四个启动器 | 内部接口令牌、请求绑定签名与重放校验 |
| audit-core | 四个启动器 | 脱敏审计接口与结构化日志实现 |
| discovery-core | 发现 | 全部分页成功后提交观察 |
| runtime-core | 运行 | 能力组映射、可见工具视图和调用转接 |

## 治理链路（成功调用需补完认证）

1. 管理员登记业务 MCP 服务。发现服务初始化连接，读取所有工具分页后才提交目录。
2. 新增、修改或重新出现的工具进入待审核状态；审核发布后仍需授权对应修订。
3. 管理员跨服务组织能力组，配置工具对外别名，授予用户或角色组内 / 全组复用权限。
4. Agent 访问网关的 `/mcp/{groupId}`。认证组件生成统一调用者，网关使用当前快照判断可用工具。
5. Runtime 只接受网关签发的短期、请求绑定执行许可，生成该用户的工具列表和“组标题＋可见工具名”摘要，或调用原始 MCP 工具；完整组 description 当前未返回。
6. 管理员撤权或停用工具后，新快照同时影响列表和真实调用。旧列表不构成授权依据。

业务 MCP 凭据独立配置，入口凭证不透传。Caller 经签名许可到达 Runtime，再由连接器生成业务身份头；Discovery 不携带终端身份。外部 Bridge 的来源验证、注解和 Feign 适配尚待联调。标记为资源敏感的工具会执行资源授权接口；默认实现拒绝，该接口不自动注入查询范围。

## 本地构建与验证

要求 **JDK 21+、Maven 3.9+**；集成验证另外需要 Python 3.10+，不需要第三方 Python 包。

~~~bash
mvn -B -ntp verify
python3 scripts/smoke.py
~~~

集成脚本会启动四个真实 JVM 进程以及测试用 MCP 服务，并使用官方 MCP Java 客户端连接网关，自动验证目录同步、发布、分组、不同用户列表、调用与拒绝、撤权、定义变更、同步失败保留目录、持久化重启、快照过期拒绝。

正式应用与测试装配分开。集成脚本使用 `target/test-classes` 中的认证替身，随后启动正式 Gateway 验证三端均不放行未校验凭证。测试替身不进入可执行 JAR，也没有生产 demo 开关。脚本同时检查上游收到的身份头、伪造身份隔离及连续调用不串身份。

管理界面随 Admin 提供。启动配置见[运行配置](docs/OPERATIONS.md)；四个应用可独立启动，MCP 成功业务调用需先补完认证适配。

## 认证方式如何扩展

认证解决“是谁”，授权解决“能做什么”。调用主体以 **接入端 + 组织 + 身份来源 + 业务主体 ID** 隔离。

- C 端骨架复用现有网关的 token/channel、有效期及 Redis 键构造，`validateLoginState` 留待接入。
- 员工骨架预留企业凭证验证及业务员工映射；第三方骨架预留 Key 校验及稳定应用映射。
- 扩展 `IdentityProvider.authenticate(AuthenticationRequest)`，成功后才创建带 clientType 的 Caller。
- 工具权限和资源权限继续通过 `AuthorizationProvider`、`ResourceAccessChecker` 扩展。
- 上游凭据经 `CredentialProvider` 配置，业务身份头由已认证 Caller 生成，不接受凭据配置覆盖身份。

参阅[三端身份与后续 TODO](docs/IDENTITY.md)和[扩展开发](docs/EXTENSIONS.md)。

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
| 三类接入策略 | 三端主体、分组及授权隔离已实现；三类真实凭证校验留 TODO |
| 入口范围 | 已知组 `/mcp/{groupId}` 已实现；统一跨组 `/mcp` 待实现 |
| 能力说明 | 初始化仅返回组标题与可见工具名；完整 Doc 交付与说明可见策略待实现 |
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

