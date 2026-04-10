package top.howiehz.halo.transformer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.controller.Controller;
import top.howiehz.halo.transformer.config.TransformerControllers;
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.manager.TransformationSnippetRuntimeStore;
import top.howiehz.halo.transformer.scheme.ResourceOrder;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

class HaloTransformerPluginTest {
    // why: 删除协调器等后台 controller 会依赖 watch-driven runtime store；
    // 插件启动时必须先让缓存开始接管权威快照，再放开 controller 消费它。
    @Test
    void shouldStartRuntimeStoresBeforeControllers() {
        SchemeManager schemeManager = mock(SchemeManager.class);
        TransformationRuleRuntimeStore ruleRuntimeStore =
            mock(TransformationRuleRuntimeStore.class);
        TransformationSnippetRuntimeStore snippetRuntimeStore =
            mock(TransformationSnippetRuntimeStore.class);
        Controller controller = mock(Controller.class);

        new HaloTransformerPlugin(
            schemeManager,
            ruleRuntimeStore,
            snippetRuntimeStore,
            new TransformerControllers(List.of(controller))
        ).start();

        verify(schemeManager, times(3)).register(anyExtensionClass());
        var inOrder = inOrder(ruleRuntimeStore, snippetRuntimeStore, controller);
        inOrder.verify(ruleRuntimeStore).startWatching();
        inOrder.verify(snippetRuntimeStore).startWatching();
        inOrder.verify(controller).start();
    }

    // why: Halo 会在 start() 失败时回滚调用 stop()；
    // stop() 必须容忍部分 scheme 尚未注册，否则原始启动失败会被二次 cleanup 错误覆盖。
    @Test
    void shouldIgnoreMissingSchemesWhenStoppingAfterPartialStart() {
        SchemeManager schemeManager = mock(SchemeManager.class);
        TransformationRuleRuntimeStore ruleRuntimeStore =
            mock(TransformationRuleRuntimeStore.class);
        TransformationSnippetRuntimeStore snippetRuntimeStore =
            mock(TransformationSnippetRuntimeStore.class);
        Controller controller = mock(Controller.class);
        Scheme transformationSnippetScheme = Scheme.buildFromType(TransformationSnippet.class);
        Scheme transformationRuleScheme = Scheme.buildFromType(TransformationRule.class);

        when(schemeManager.fetch(GroupVersionKind.fromExtension(TransformationSnippet.class)))
            .thenReturn(Optional.of(transformationSnippetScheme));
        when(schemeManager.fetch(GroupVersionKind.fromExtension(TransformationRule.class)))
            .thenReturn(Optional.of(transformationRuleScheme));
        when(schemeManager.fetch(GroupVersionKind.fromExtension(ResourceOrder.class)))
            .thenReturn(Optional.empty());

        new HaloTransformerPlugin(
            schemeManager,
            ruleRuntimeStore,
            snippetRuntimeStore,
            new TransformerControllers(List.of(controller))
        ).stop();

        verify(controller).dispose();
        verify(snippetRuntimeStore).stopWatching();
        verify(ruleRuntimeStore).stopWatching();
        verify(schemeManager).unregister(transformationSnippetScheme);
        verify(schemeManager).unregister(transformationRuleScheme);
        verify(schemeManager, times(2)).unregister(any(Scheme.class));
        verifyNoMoreInteractions(snippetRuntimeStore);
        verifyNoMoreInteractions(ruleRuntimeStore);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Extension> anyExtensionClass() {
        return any(Class.class);
    }
}
