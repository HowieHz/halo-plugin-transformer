package top.howiehz.halo.transformer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import top.howiehz.halo.transformer.service.TransformationSnippetDeletionReconciler;

@Configuration
public class TransformerControllerConfiguration {
    /**
     * why: Halo 提供了 controller / finalizer 这套原语，但具体资源的删除收敛流程仍需插件自己声明；
     * 这里把代码片段删除协调器注册成独立 controller，保持删除生命周期与普通写接口解耦。
     */
    @Bean
    Controller transformationSnippetDeletionController(
        TransformationSnippetDeletionReconciler reconciler,
        ExtensionClient client) {
        return reconciler.setupWith(new ControllerBuilder(reconciler, client));
    }

    /**
     * why: 插件主生命周期不该直接拿全局 `Controller` 列表；
     * 这里显式聚合本插件自己的 controllers，避免未来误启动/误停止其它模块的后台流程。
     */
    @Bean
    TransformerControllers transformerControllers(Controller transformationSnippetDeletionController) {
        return new TransformerControllers(java.util.List.of(transformationSnippetDeletionController));
    }
}
