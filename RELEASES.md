# 发布策略

## 发布前检查清单

准备发布前，请先确认以下事项：

1. 计划合并到本次版本的分支或 PR 都已完成合并。
2. `CHANGELOG.md` 的 `Unreleased` 已整理完成，包含本次版本的变更记录。

## 正式版发布方法

正式版发布改为单 PR 流程，不再手工创建 GitHub Release：

1. 创建一个面向 `main` 的发版 PR。
2. 在 PR 中更新 `gradle.properties` 的 `version`，并补齐 `CHANGELOG.md` 的 `Unreleased`。
3. 给这个 PR 添加 `release` label。
4. 等 `CI` 通过后合并 PR。

合并后，机器人（GitHub Actions）会自动执行以下动作：

- 校验 `gradle.properties` 中的版本号是否为新的稳定语义化版本。
- 将 `CHANGELOG.md` 的 `Unreleased` 条目提升为正式版本，并直接提交回 `main`。
- 自动创建标签为 `vX.Y.Z`、标题为 `X.Y.Z` 的 GitHub Release。
- 在 Release 发布事件触发后，调用 Halo 的插件 CD 工作流构建产物并同步应用商店。

## 保护规则

- 普通 PR 不允许修改 `gradle.properties` 中的 `version`。
- 只有带 `release` label 的 PR 才允许修改版本号。
- 带 `release` label 的 PR 必须同时修改版本号，否则 `CI` 会失败。

## Secrets 要求

自动发版依赖仓库中的 `HALO_PAT`：

- 需要具备 `contents: write`，用于回推 changelog commit 和创建 GitHub Release。
- 需要能触发后续的 `release.published` 工作流事件。
- 如果 `main` 受保护且禁止直接推送，则该 token 还需要具备对应的分支规则绕过权限；否则自动提交 changelog 到 `main` 会失败。

## Release Notes

自动创建的 GitHub Release 说明使用固定模板：

```markdown
详情请参阅 [CHANGELOG.md](https://github.com/HowieHz/halo-plugin-transformer/blob/main/CHANGELOG.md)。
```

## 失败恢复

- 如果发版 PR 已合并，但自动发版中断，优先检查 `HALO_PAT` 权限和 `main` 分支保护规则。
- 如果 changelog commit 已经推送成功，release workflow 支持再次运行，不会重复提升同一个版本的 changelog 条目。
