## Contributing

感谢你为 `halo-plugin-injector` 做贡献。

`README.md` 面向插件使用者，重点讲“能做什么、怎么配置、有什么运行时表现”；这份文档面向贡献者，重点讲“怎么构建、哪些是规范源、架构边界在哪里、修改后该验证什么”。

## 开发环境

- Java 21+
- Node.js 18+
- pnpm

## 快速开始

```bash
# 构建插件
./gradlew build

# 前端开发
cd ui
pnpm install
pnpm dev
```

构建完成后，可在 `build/libs` 目录找到插件 jar。

## 文档分工

- `README.md`
    - 面向用户
    - 说明功能、配置方式、规则能力、运行期表现
- `CONTRIBUTING.md`
    - 面向贡献者
    - 说明构建链路、spec 生成链路、架构约定、平台边界与已知问题

## 前端数据流约定

前端资源状态按三层区分：

- `ReadModel`
    - 接口读取结果，只用于列表与已保存快照
- `EditorDraft`
    - 编辑器草稿，只用于右侧表单与导入态
- `WritePayload`
    - 提交给后端的最小持久化字段集合

这样可以避免把 `id`、临时编辑状态、JSON 草稿等前端态字段混进写接口。

导入 / 导出同样遵循这条边界：

- transfer 只承载可移植的编辑内容
- 不承载 `id`、排序、系统 metadata
- 规则 transfer 也不承载 `snippetIds`；跨环境关系迁移若以后要支持，应单独设计显式协议，而不是偷偷把关系字段塞回当前 transfer

## Match-rule specs

为了避免“README 很长，但 contract fixture 很薄”，`match-rule` 现在拆成两类规范源：

- 共享 contract
    - 规范源：`specs/match-rule/contract.spec.jsonc`
    - case 源：`specs/match-rule/contract.cases.jsonc`
    - 允许字段集合、错误文本模板与 schema 都从这里生成
- 前端专属行为
    - 仍记录在 spec/cases 中，但不强制要求后端共享同一语义
    - 典型例子包括模式切换确认、JSON 行高亮、导入后退回 `JSON_DRAFT`

### 生成链路

- `pnpm generate:spec-artifacts`
    - 从仓库根的 `specs/match-rule/contract.spec.jsonc` 刷新 generated artifacts
- `pnpm verify:spec-artifacts`
    - 只校验 generated artifacts 是否与 spec 一致
- `./gradlew verifyMatchRuleSpecArtifacts`
    - 后端构建链路上的同义校验，避免只跑 Gradle 时漏掉 spec 一致性检查

### JSON Schema

- `ui/public/injector.schema.json`
    - 负责 transfer envelope
    - 描述顶层 `version`、`resourceType`、`data`
- `ui/public/generated/match-rule.schema.json`
    - 从 `specs/match-rule/contract.spec.jsonc` 生成
    - 负责 `match-rule` 领域结构
- `ui/public/injector.schema.json` 会通过 `$ref` 引用 `match-rule` generated schema，而不是在 envelope 中手写一遍规则树结构

### Runtime boolean simplification

运行时和分析期会先对 `match-rule` 做一轮“收缩表达式 / 减少操作数”方向的布尔最小化；这一步只影响运行期分析与匹配，不会改写控制台草稿，也不会改写持久化存储的原始规则树。

当前已覆盖的规则包括：

- 恒等消去：`AND(true, x) -> x`
- 常量折叠：`AND(false, x) -> false`
- 补元律：`AND(x, NOT(x)) -> false`
- 幂等律：`AND(x, x) -> x`
- 双重否定消去：`NOT(NOT(x)) -> x`
- 吸收律：`AND(x, OR(x, y)) -> x`
- 反向分配律（因式分解）：`OR(AND(x, y), AND(x, z)) -> AND(x, OR(y, z))`
- 德摩根变换：`OR(NOT(x), NOT(y)) -> NOT(AND(x, y))`
- 德摩根变换：`AND(NOT(x), NOT(y)) -> NOT(OR(x, y))`

这份清单应与 `specs/match-rule/contract.spec.jsonc` / `specs/match-rule/contract.cases.jsonc` 保持一致；如果后续新增或删除规则，请同时更新 spec、测试和这里的说明。

## 架构约定

本插件优先复用 Halo CMS 的原生资源模型与控制面能力，而不是在业务层重复发明平台机制。

- 并发写入优先使用 `metadata.version` 做乐观并发控制，而不是退回 silent last-write-wins
- 涉及“删除前先清理引用”的资源生命周期，优先使用 `metadata.deletionTimestamp + finalizers`
- 凡是异步收敛、失败可重试、事件驱动刷新这类后台流程，优先使用 `controller / reconciler / watch`
- 运行时缓存优先做成 watch 驱动的内存快照，而不是请求路径上的 TTL 回源
- 控制台读接口优先返回显式 projection / read model，而不是把存储实体直接暴露给 UI
- 运行时执行链路优先消费独立 runtime projection，而不是把 extension 存储实体继续透传到 filter / processor
- 资源查询遵循平台模型：能 `fetch(name)` 就不用 `list + filter(name)`；能 `fieldQuery` 就不做全量扫描
- `annotations / labels` 只用于轻量元信息、兼容标记与索引辅助，不承载结构化业务状态，也不替代独立资源建模
- 业务语义校验仍由插件自己负责，例如 `unknownFields`、match-rule contract、导入导出约束；这些属于插件领域规则，不属于 Halo 通用扩展层职责

若平台当前不提供对应索引、patch 或事务能力，就显式承认这个边界，并在单一 authoritative side 上保持最小、清晰、可恢复的写模型，而不是在前端或 endpoint 中堆叠补偿式分支逻辑。

## 已知问题（PE）

下面这些问题更接近 Halo 当前平台能力边界，或继续优化的收益/复杂度比暂时不划算，因此作为已知问题显式记录。

这些问题的共同原则是：

- 先承认平台边界，不伪造“看起来像事务 / 像索引”的业务层补偿
- 保持单一 authoritative source，不为了绕过限制重新引入双真源
- 如果 Halo 后续补齐原生能力，再顺势切换到平台能力，而不是在插件里长期堆过渡方案

### 删除代码块时，反向引用查询仍需全表扫规则

- 当前删除协调器需要找出所有引用某个代码块的 `InjectionRule`
- 关系真源已经收敛到 `InjectionRule.snippetIds`
- 但 Halo 当前还没有直接提供“集合成员反向索引 / membership fieldQuery”这类查询能力
- 因此这一步仍然需要基于规则列表做过滤，而不是按 `snippetId` 直接反查

这不是当前插件建模错误，而是平台查询能力的已知边界。

如果 Halo 后续支持：

- 针对集合成员的反向索引查询
- 更细粒度的 `fieldQuery`

那么这里就应该切换到平台原生索引能力，而不是继续保留全表扫描。

在平台能力出现之前，不建议回退到：

- 前端自己维护 snippet -> rules 的镜像关系
- endpoint 同步补写另一侧资源
- annotations / labels 承载结构化关系真源

### 删除代码块是最终一致，不是跨资源原子事务

- 删除代码块当前走 Halo `finalizer + reconciler` 生命周期
- 删除请求本身只负责把 `CodeSnippet` 送入 deleting 状态
- 后端协调器随后摘掉所有引用它的 `InjectionRule.snippetIds`
- 全部清理完成后，才移除 finalizer，交还 Halo 完成真正删除

这条链路已经是当前阶段的推荐实现，但它属于**最终一致**，不是跨多个 extension 资源的一笔原子事务。

原因不是插件继续“没收口”，而是 Halo 当前并没有直接提供：

- 跨多个 extension 资源的单事务原子提交
- 字段级 partial update / merge patch

因此这里的设计目标不是伪造事务语义，而是在 Halo 原生能力内做到“现在最好”：

- 关系真源只保留在 `InjectionRule.snippetIds`
- 删除收敛交给 `finalizer + reconciler`
- 每次都基于最新资源读取后再更新
- 失败时保留 finalizer，让平台后续继续重试

如果 Halo 未来提供更细粒度 patch 或更强资源事务能力，这条链路再进一步收紧到“更小 patch 面 / 更强一致”会更自然。

在此之前，不建议为了“看起来原子”而回退到：

- 前端先扫引用再串行删除
- endpoint 内部拼同步补偿事务
- 人工维护额外删除状态字段来模拟跨资源事务

### 模板 ID 匹配依赖页面正确暴露 `_templateId`

- `TEMPLATE_ID` 匹配的真源来自 Halo 模板上下文里的 `_templateId`
- 对 Halo 自带页面或正确接入该上下文的插件页面，这条链路可以稳定工作
- 但如果第三方页面没有暴露 `_templateId`，插件就拿不到可靠的模板身份

这不是 Injector 自己还能“再猜一次”就能稳定补齐的问题，而是页面集成是否正确暴露模板上下文的边界。

因此当前推荐做法是：

- 把这条限制显式写进 README，按用户语义说明清楚
- 运行时在拿不到 `_templateId` 时保守降级，只依赖路径等其它可用条件
- 不在插件层发明脆弱的“按 URL / 主题名 / 页面 HTML 猜模板 ID”补偿逻辑

如果 Halo 未来为更多页面稳定提供 `_templateId`，或提供更统一的模板身份暴露能力，这条限制就会自然缩小。

## 提交前检查

建议至少运行下面这些检查：

```bash
# spec 一致性
pnpm verify:spec-artifacts

# 前端
pnpm --dir ui lint
pnpm --dir ui type-check
pnpm --dir ui test:unit

# 后端
./gradlew build
```

如果改动涉及 spec、schema、contract helper 或导入导出结构，先运行 `pnpm generate:spec-artifacts`，再提交生成物。
