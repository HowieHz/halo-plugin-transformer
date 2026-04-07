package com.erzbir.halo.injector.config;

import com.erzbir.halo.injector.service.CodeSnippetDeletionReconciler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;

@Configuration
public class InjectorControllerConfiguration {
    /**
     * why: Halo 提供了 controller/finalizer 原语，但具体资源的删除收敛流程仍需插件自己声明；
     * 这里把代码块删除协调器注册成独立 controller，保持删除生命周期与普通写接口解耦。
     */
    @Bean
    Controller codeSnippetDeletionController(CodeSnippetDeletionReconciler reconciler,
                                             ExtensionClient client) {
        return reconciler.setupWith(new ControllerBuilder(reconciler, client));
    }
}
