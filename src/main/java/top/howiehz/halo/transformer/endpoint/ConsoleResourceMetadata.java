package top.howiehz.halo.transformer.endpoint;

/**
 * why: 控制台读模型只应暴露 UI 真正需要的元数据；
 * 这样存储层的 finalizers / labels / annotations 等平台字段就不会顺手漏到前端状态里。
 */
public record ConsoleResourceMetadata(
        String name,
        Long version
) {
}
