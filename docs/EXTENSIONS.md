# 扩展开发

## 模块依赖原则

启动器负责装配，领域模块只依赖契约和接口。新增企业适配器应作为 Maven 库子模块，通过需要它的启动器引入。不能让库模块依赖某个可执行应用，也不需要为每个扩展单独建立服务。

典型依赖：企业身份适配器 → identity-spi → platform-contracts。发现与运行核心 → mcp-connector-spi → platform-contracts。管理后台 → catalog-core → platform-contracts。

Spring 装配默认 Bean 使用 ConditionalOnMissingBean。自定义配置需要进入相应启动器的组件扫描范围，或在启动器显式 Import；仅把类放进 Maven 依赖不会自动完成 Bean 装配。

## 三类场景的统一扩展原则

运营／员工、应用／第三方和 C 端共用本节扩展接口；场景默认策略与完整目标见 [接入场景与能力视图设计](ACCESS-MODEL.md)。当前只提供 JWT／demo、用户／角色授权和单组入口，后续接口设计不能写成已存在的 Bean 或配置。

Key 属于凭证形式，应用属于主体类型。正式 Key 适配需验证有效性与撤销状态，映射到稳定应用主体；凭证轮换沿用主体授权。应用主体应有明确类型或可信命名空间，避免与同名员工／消费者混淆。正式应用模型、Key 适配器和专用模板管理尚待实现；当前 ROLE 授权可复用为同类用户的基础能力。

C 端基础工具集可通过用户类型等模板统一授权，不需要逐人配置相同工具。工具模板也受当前修订和发布规则控制；用户订单范围与业务状态检查在调用链处理。固定策略是授权实现的一种，不绕过公共鉴权。

## 身份接入：IdentityProvider 和 IdentityMapper

IdentityProvider 只接收入口 Authorization 值，成功返回 Caller；验证失败抛出 PlatformException(401, ...)：

~~~java
public interface IdentityProvider {
    Caller authenticate(String authorizationHeader);
}
~~~

Caller 包含 subject、tenant、issuer 和 roles。subject 不单独作为全局用户键，授权匹配同时检查 tenant 与 issuer。外部邮箱相同不代表平台身份相同。

默认 JWT 模式：

- Nimbus 验证签名，且要求配置的 issuer、audience、有效期匹配。
- subject 来自已验证 token 的 sub。
- tenant 来自部署可信配置 JWT_TENANT。
- roles 读取已验证 claims 的指定数组，默认为 roles。
- IdentityMapper 可以接入企业组织 / 用户映射库，将外部账号映射为平台身份。
- 此适配器不接受前端自报的组织请求头，不通过解析未验证 JWT 直接信任身份。

企业采用钉钉时，可以由企业登录服务完成授权码交换、应用验证与账号绑定，再签发本平台受众的 JWT，继续使用默认适配器；也可以增加企业专用 IdentityProvider。真实接入需要企业应用凭据、授权范围、回调和账号映射配置，不能用固定假用户代替。

同一部署接入多个企业时，创建可信来源注册表：先选择允许的身份来源，再用该来源的验证器验证；不能把 token 里的任意 issuer 自动当作可访问的 JWKS 地址。统一结果再交给现有授权模块。默认单 issuer 适配器不声称已经支持动态多企业登录。

## 授权：AuthorizationProvider

~~~java
public interface AuthorizationProvider {
    Set<String> visibleToolIds(Caller caller, CapabilityGroup group, Snapshot snapshot);
    ToolEntry authorizeCall(Caller caller, CapabilityGroup group,
                            String alias, Map<String, Object> arguments, Snapshot snapshot);
}
~~~

两种操作必须基于相同的权限语义；资源级权限可以使某些参数的调用被拒绝，即使工具本身可见。不要让列表走企业权限、调用却只看组成员关系。

替换外部权限适配器时，仍须维护组织隔离、当前发布状态、服务可用性、组内工具映射和工具修订等平台不变量。默认实现包含这些检查。外部策略服务失败应拒绝；缓存策略也应有明确期限。

单独接入业务资源范围时优先只替换 ResourceAccessChecker：

~~~java
@Configuration
class CompanyResourceAuthorization {
    @Bean
    ResourceAccessChecker resourceAccessChecker(CompanyAccessService company) {
        return (caller, tool, arguments) ->
            company.mayOperate(caller.tenant(), caller.issuer(), caller.subject(),
                               tool.id(), arguments);
    }
}
~~~

CompanyAccessService 是企业需要实现的服务，不是仓库中的默认 Bean。资源归属应查询可信业务数据，不能直接相信用户参数中声称的 owner 或 tenant。

管理员发布时标记 resourceProtected。默认 ResourceAccessChecker 对这些工具拒绝调用；企业实现配置正确后才允许真实数据操作。改变这个标记会增加工具修订，旧授权不会自动继承。

## 能力说明与统一入口扩展（待实现）

继续保留已知组入口；需要单地址跨组发现时由 Runtime 生成标准工具数组与稳定绑定，Gateway 对入口范围和实际组授权。当前单组 InvocationPermit、Controller 和 Facade 需一起扩展，不能只放宽 URL 匹配。

组 Doc 的可见策略独立于工具调用权限。管理备注默认内部；已批准公开的业务手册可以覆盖完整流程，同时清楚表达当前调用范围。保密文档按文档或受控章节权限过滤，不能自动公开旧 group.description，也不应为每种权限组合生成独立手册。

初始化说明使用短摘要，完整 Doc 后续通过受控查询工具交付。文档引用解析为最终对外名称；保留已审核上游说明时处理跨服务冲突与别名，客户端是否注入 instructions 单独验证。

## 上游协议与服务凭据

McpConnector 将两个业务动作隔离：完整工具发现 discover，以及一次工具调用 invoke。增加其他协议传输时新增连接器实现，保留完整发现与不自动重试写操作的语义。

CredentialProvider 根据 credentialRef 获取服务凭据。初始实现只接受 MCP_UPSTREAM_ 开头的环境变量引用，环境变量值为完整 Authorization 值，例如 Bearer 后接企业服务令牌。目录中只存引用，网关的用户访问令牌不会被送到业务 MCP。

Runtime 和 Discovery 均可替换 CredentialProvider / McpConnector Bean。凭据轮换、密钥管理系统和服务身份应放在这些适配器内，而不是在工具定义中添加明文密钥。这里的 MCP_UPSTREAM_* 是业务上游凭据，不是对外应用 Key 的已实现认证方案。

当前 invoke(service, tool, arguments) 不携带 Caller，平台级工具可使用该模型。面向用户数据的工具需增加可信执行上下文／受控数据范围注入扩展，明确应用主体、终端用户及业务身份映射，由上游验证受众、有效期和来源。用户入口 Token 和 Gateway → Runtime 许可不直接转发到业务服务；不得把 Agent 提交的 userId 当作可信身份。前置 ResourceAccessChecker 的布尔结果不能自动限制列表查询的返回范围。

discover 当前只返回工具定义，忽略上游 initialize 的说明。说明采集需扩展发现结果契约并进入管理审核，不能在转接层无审核注入模型上下文。

## 持久化与并发

CatalogStore 提供：

~~~java
Snapshot load();
void save(long expectedVersion, Snapshot next);
~~~

默认 FileCatalogStore 适用于单管理实例：同进程同步写入，校验预期版本，临时文件原子替换。不能把多个管理实例简单指向同一个文件。

数据库实现需要事务与乐观锁，并以 expectedVersion 为条件提交。在增加多个写者时，CatalogService 当前持有的快照也需在冲突后重新加载，或改为每事务读写；单独替换存储而不处理过期领域状态不足以实现多写者。

初始 ToolEntry 保存当前定义与修订计数；完整版本存档、回滚和发布审批可在管理领域增加独立实体，不应让回滚绕过“授权绑定修订”的规则。

## 管理登录与审计

首版 Admin 使用独立管理员令牌；它与目录只读令牌、发现写入令牌互不通用。管理页面不保存令牌到浏览器持久存储。

企业化管理登录应增加管理操作者身份、管理动作授权和操作者审计。可复用 identity-spi，替换 AdminAccessFilter 的启动器层接入逻辑。当前 UI 的管理员身份固定为 bootstrap-admin，不能声称已有真实用户操作追踪。

AuditSink 默认写结构化日志，可替换为持久事件收集器。不要记录用户凭证、内部执行许可、上游秘密、原始参数或业务返回正文。日志审计失败是否阻断业务，应依据组织要求设计；初始实现没有持久投递保障。

## 进一步拆分部署的条件

- 目录规模增大：分页快照、变化订阅及版本屏障，保留完整一致视图。
- 管理服务高可用：事务数据库与冲突重载，不能共享文件冒充多写者。
- Runtime 多副本：共享 nonce 或等价请求重放控制；保持请求内容绑定。
- 更严格撤权：缩短租约 / 引入集中策略决策和撤权版本门槛；客户端旧列表仍无授权效力。
- 完整 MCP 能力：在协议层增加资源、提示、任务或通知；只声明已经实现并验证的能力。
- 客户端视图刷新：将服务端快照更新与客户端工具／Doc 更新分别建模。按是否要求会话内自动变化启用对应协议通知；固定接入可使用重连或显式刷新，服务端调用鉴权始终保留。

这些是清晰的演进接口和剩余工作，不是当前已经实现的功能。

