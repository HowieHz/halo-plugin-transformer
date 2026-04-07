package com.erzbir.halo.injector;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.scheme.ResourceOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.controller.Controller;
import run.halo.app.plugin.BasePlugin;

import java.util.List;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@RequiredArgsConstructor
@Component
public class HaloInjectorPlugin extends BasePlugin {
    private final SchemeManager schemeManager;
    private final InjectionRuleManager injectionRuleManager;
    private final List<Controller> controllers;

    @Override
    public void start() {
        registerScheme();
        startControllers();
        injectionRuleManager.startWatching();
    }

    @Override
    public void stop() {
        injectionRuleManager.stopWatching();
        stopControllers();
        unregisterScheme();
    }

    private void registerScheme() {
        schemeManager.register(CodeSnippet.class);
        schemeManager.register(InjectionRule.class);
        schemeManager.register(ResourceOrder.class);
    }

    private void unregisterScheme() {
        unregisterSchemeIfPresent(CodeSnippet.class);
        unregisterSchemeIfPresent(InjectionRule.class);
        unregisterSchemeIfPresent(ResourceOrder.class);
    }

    /**
     * why: Halo controller 负责把 deleting 资源收敛到最终一致状态；
     * 插件启动时必须显式拉起这些后台协调器，避免 finalizer 卡住却无人处理。
     */
    private void startControllers() {
        controllers.forEach(Controller::start);
    }

    /**
     * why: 停插件时要先停掉后台协调器，再卸载 scheme；
     * 否则 controller 线程仍可能在使用已卸载的扩展类型。
     */
    private void stopControllers() {
        controllers.forEach(Controller::dispose);
    }

    /**
     * why: Halo may call stop() while rolling back a failed start();
     * at that point later schemes might not have been registered yet, so cleanup must be idempotent.
     */
    private void unregisterSchemeIfPresent(Class<? extends Extension> extensionType) {
        schemeManager.fetch(GroupVersionKind.fromExtension(extensionType))
                .ifPresent(schemeManager::unregister);
    }
}
