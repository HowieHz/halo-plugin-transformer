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

## 架构约定

本插件优先复用 Halo CMS 的原生资源模型与控制面能力，而不是在业务层重复发明平台机制。

- 并发写入优先使用 `metadata.version` 做乐观并发控制，而不是退回 silent last-write-wins
- 涉及“删除前先清理引用”的资源生命周期，优先使用 `metadata.deletionTimestamp + finalizers`
- 凡是异步收敛、失败可重试、事件驱动刷新这类后台流程，优先使用 `controller / reconciler / watch`
- 运行时缓存优先做成 watch 驱动的内存快照，而不是请求路径上的 TTL 回源
- 控制台读接口优先返回显式 projection / read model，而不是把存储实体直接暴露给 UI
- 资源查询遵循平台模型：能 `fetch(name)` 就不用 `list + filter(name)`；能 `fieldQuery` 就不做全量扫描
- `annotations / labels` 只用于轻量元信息、兼容标记与索引辅助，不承载结构化业务状态，也不替代独立资源建模
- 业务语义校验仍由插件自己负责，例如 `unknownFields`、match-rule contract、导入导出约束；这些属于插件领域规则，不属于 Halo 通用扩展层职责

若平台当前不提供对应索引、patch 或事务能力，就显式承认这个边界，并在单一 authoritative side 上保持最小、清晰、可恢复的写模型，而不是在前端或 endpoint 中堆叠补偿式分支逻辑。

## 已知问题（PE）

下面这些问题更接近 Halo 当前平台能力边界，或继续优化的收益/复杂度比暂时不划算，因此作为已知问题显式记录。

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
