# 发布策略

## 发布前检查清单

准备发布前，请先确认以下事项：

1. 计划合并到本次版本的分支或 PR 都已完成合并。
2. CHANGELOG.md 的 `Unreleased` 已整理完成，包含本次版本的变更记录。

## 正式版发布方法

在 GitHub 上创建一个新的 Release：
- 标签格式：`vX.Y.Z`，其中 X、Y、Z 分别代表主版本号、次版本号和修订号。
- 标题格式：`X.Y.Z`
- 内容：
  ```markdown
  详情请参阅[更新日志](https://github.com/HowieHz/halo-plugin-transformer/blob/main/CHANGELOG.md)。
  ```

发布后，机器人（GitHub Actions）会自动执行以下动作：

- 先将 `CHANGELOG.md` 的 `Unreleased` 条目提升为正式版本，并更新版本比较链接。
- 自动构建产物并上传到 GitHub Release 页面。
- 同步更新应用商店。
