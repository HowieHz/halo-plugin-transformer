package top.howiehz.halo.transformer;

import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.ResourceOrder;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.controller.Controller;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class HaloTransformerPluginTest {
    // why: Halo 会在 start() 失败时回滚调用 stop()；
    // stop() 必须容忍部分 scheme 尚未注册，否则原始启动失败会被二次 cleanup 错误覆盖。
    @Test
    void shouldIgnoreMissingSchemesWhenStoppingAfterPartialStart() {
        SchemeManager schemeManager = mock(SchemeManager.class);
        TransformationRuleRuntimeStore ruleRuntimeStore = mock(TransformationRuleRuntimeStore.class);
        Controller controller = mock(Controller.class);
        Scheme transformationSnippetScheme = Scheme.buildFromType(TransformationSnippet.class);
        Scheme transformationRuleScheme = Scheme.buildFromType(TransformationRule.class);

        when(schemeManager.fetch(GroupVersionKind.fromExtension(TransformationSnippet.class)))
                .thenReturn(Optional.of(transformationSnippetScheme));
        when(schemeManager.fetch(GroupVersionKind.fromExtension(TransformationRule.class)))
                .thenReturn(Optional.of(transformationRuleScheme));
        when(schemeManager.fetch(GroupVersionKind.fromExtension(ResourceOrder.class)))
                .thenReturn(Optional.empty());

        new HaloTransformerPlugin(schemeManager, ruleRuntimeStore, List.of(controller)).stop();

        verify(controller).dispose();
        verify(ruleRuntimeStore).stopWatching();
        verify(schemeManager).unregister(transformationSnippetScheme);
        verify(schemeManager).unregister(transformationRuleScheme);
        verify(schemeManager, times(2)).unregister(any(Scheme.class));
        verifyNoMoreInteractions(ruleRuntimeStore);
    }
}
