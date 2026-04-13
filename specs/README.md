# 规范说明

这个目录存放仓库内自维护的契约规范。

- `specs/` 下的文件是生成产物的权威真源。
- 不要为了“修”契约不一致，直接修改生成出来的 helper 或 schema。
- 应先修改 spec，再在仓库根目录执行 `pnpm generate:spec-artifacts` 重新生成。

## `match-rule`

文件说明：

- `specs/match-rule/contract.spec.jsonc`
  - 共享契约真源，定义节点类型、允许字段、枚举值和 checklist
- `specs/match-rule/contract.cases.jsonc`
  - 语义样例，供 Java 和 TypeScript 契约测试直接消费

真源 -> 生成物 -> 消费者：

- `specs/match-rule/contract.spec.jsonc`
  - `ui/src/contract/generated/matchRuleContract.ts`
  - 由 `ui/src/views/composables/matchRule.ts`、`ui/src/views/composables/matchRuleValidation.ts` 和前端契约测试消费
- `specs/match-rule/contract.spec.jsonc`
  - `src/main/java/top/howiehz/halo/transformer/contract/generated/MatchRuleContractMessages.java`
  - 由 `src/main/java/top/howiehz/halo/transformer/rule/MatchRule.java` 和 Java 契约测试消费
- `specs/match-rule/contract.spec.jsonc`
  - `ui/public/generated/match-rule.schema.json`
  - `ui/public/generated/transformer.schema.json`
  - 由导入导出 schema 工具链和支持 schema 的编辑器消费
- `specs/match-rule/contract.cases.jsonc`
  - 不生成其他文件
  - 由 Java 和 TypeScript 契约测试直接消费

为什么这样做：

- 设计说明移到 spec 外部后，spec 本身可以保持小而纯粹，只表达数据契约
