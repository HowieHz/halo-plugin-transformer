# 自动生成文件

这个目录存放自动生成的 TypeScript 契约 helper。

- 不要手动修改这个目录下的文件。
- 如需调整，请修改权威 spec 或生成器。
- 修改后请在仓库根目录执行 `pnpm generate:spec-artifacts` 重新生成。

为什么这样做：

- 这些 helper 让 UI 和后端共享同一份允许字段、枚举值与错误消息
- 把它们和手写页面 composable 隔离开，可以明确归属并避免契约悄悄漂移
