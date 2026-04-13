# 发布策略

## 发布前检查清单

准备发布前，请先确认以下事项：

1. 计划合并到本次版本的分支或 PR 都已完成合并。
2. `CHANGELOG.md` 的 `Unreleased` 已整理完成，包含本次版本的变更记录。

## 正式版发布方法

1. 创建一个面向 `main` 的发版 PR。
   - 在 PR 中更新 `gradle.properties` 的 `version`，并补齐 `CHANGELOG.md` 的 `Unreleased`。
2. 给这个 PR 添加 `release` 标签。
3. 等持续集成（CI）检查通过后合并 PR。

合并后，机器人（GitHub Actions）会自动执行以下动作：

- 校验 `gradle.properties` 中的版本号是否为新的稳定语义化版本。
- 将 `CHANGELOG.md` 的 `Unreleased` 条目提升为正式版本，并直接提交回 `main`。
- 自动创建标签为 `vX.Y.Z`、标题为 `X.Y.Z` 的 GitHub 发布页（Release）。
- 在发布页发布事件触发后，调用 Halo 的插件 CD 工作流构建产物并同步应用商店。

## 保护规则

- 普通 PR 不允许修改 `gradle.properties` 中的 `version`。
- 只有带 `release` 标签的 PR 才允许修改版本号。
- 带 `release` 标签的 PR 必须同时修改版本号，否则持续集成（CI）会失败。

## 发布说明

自动创建的 GitHub 发布说明使用固定模板：

```markdown
详情请参阅 [CHANGELOG.md](https://github.com/HowieHz/halo-plugin-transformer/blob/main/CHANGELOG.md)。
```
