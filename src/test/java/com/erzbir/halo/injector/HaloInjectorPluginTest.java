package com.erzbir.halo.injector;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.scheme.ResourceOrder;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HaloInjectorPluginTest {
    // why: Halo 会在 start() 失败时回滚调用 stop()；
    // stop() 必须容忍部分 scheme 尚未注册，否则原始启动失败会被二次 cleanup 错误覆盖。
    @Test
    void shouldIgnoreMissingSchemesWhenStoppingAfterPartialStart() {
        SchemeManager schemeManager = mock(SchemeManager.class);
        InjectionRuleManager ruleManager = mock(InjectionRuleManager.class);
        Scheme codeSnippetScheme = Scheme.buildFromType(CodeSnippet.class);
        Scheme injectionRuleScheme = Scheme.buildFromType(InjectionRule.class);

        when(schemeManager.fetch(GroupVersionKind.fromExtension(CodeSnippet.class)))
                .thenReturn(Optional.of(codeSnippetScheme));
        when(schemeManager.fetch(GroupVersionKind.fromExtension(InjectionRule.class)))
                .thenReturn(Optional.of(injectionRuleScheme));
        when(schemeManager.fetch(GroupVersionKind.fromExtension(ResourceOrder.class)))
                .thenReturn(Optional.empty());

        new HaloInjectorPlugin(schemeManager, ruleManager).stop();

        verify(schemeManager).unregister(codeSnippetScheme);
        verify(schemeManager).unregister(injectionRuleScheme);
        verify(schemeManager, times(2)).unregister(any(Scheme.class));
    }
}
