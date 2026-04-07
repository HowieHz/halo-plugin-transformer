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
import run.halo.app.plugin.BasePlugin;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@RequiredArgsConstructor
@Component
public class HaloInjectorPlugin extends BasePlugin {
    private final SchemeManager schemeManager;
    private final InjectionRuleManager injectionRuleManager;

    @Override
    public void start() {
        registerScheme();
        injectionRuleManager.warmUpCacheAsync();
    }

    @Override
    public void stop() {
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
     * why: Halo may call stop() while rolling back a failed start();
     * at that point later schemes might not have been registered yet, so cleanup must be idempotent.
     */
    private void unregisterSchemeIfPresent(Class<? extends Extension> extensionType) {
        schemeManager.fetch(GroupVersionKind.fromExtension(extensionType))
                .ifPresent(schemeManager::unregister);
    }
}
