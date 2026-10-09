# 架构与 UML 图集

以下为可直接查看、下载和编辑的 SVG 源图。图集区分目标设计与当前实现，避免把待办当作已经上线的能力。

## 三类接入与能力视图（目标设计）

运营／员工、应用／第三方、C 端共用治理内核；分组入口与统一入口按场景选择。统一入口、正式 Key 适配及相关扩展尚待实现，见 [设计说明](ACCESS-MODEL.md)。

![三类接入与能力视图（目标设计）](diagrams/access-model.svg)

## 总体架构（当前实现）

以下十一张图描述当前分组入口基线，虚线刷新表示服务端有界最终一致性，不代表客户端自动刷新。

![总体架构](diagrams/architecture.svg)

## UML 领域模型

![领域模型](diagrams/domain-model.svg)

## 工具生命周期

![工具状态](diagrams/tool-lifecycle.svg)

## 01 · 服务接入

![01 · 服务接入](diagrams/service-registration.svg)

## 02 · 服务代码更新与目录同步

![02 · 服务代码更新与目录同步](diagrams/tool-update.svg)

## 03 · 跨服务能力分组

![03 · 跨服务能力分组](diagrams/capability-grouping.svg)

## 04 · 授权与撤权

![04 · 授权与撤权](diagrams/grant-revoke.svg)

## 05 · 用户连接与发现能力

![05 · 用户连接与发现能力](diagrams/user-discovery.svg)

## 06 · 正常工具调用

![06 · 正常工具调用](diagrams/normal-call.svg)

## 07 · 越权与绕过拦截

![07 · 越权与绕过拦截](diagrams/access-denial.svg)

## 08 · 异常与变更生效

![08 · 异常与变更生效](diagrams/exceptions.svg)

