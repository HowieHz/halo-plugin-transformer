package com.erzbir.halo.injector;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.scheme.ResourceOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
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

    @Override
    public void start() {
        registerScheme();
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
        Scheme codeSnippetScheme = schemeManager.get(CodeSnippet.class);
        Scheme injectionRuleScheme = schemeManager.get(InjectionRule.class);
        Scheme resourceOrderScheme = schemeManager.get(ResourceOrder.class);
        schemeManager.unregister(codeSnippetScheme);
        schemeManager.unregister(injectionRuleScheme);
        schemeManager.unregister(resourceOrderScheme);
    }
}
