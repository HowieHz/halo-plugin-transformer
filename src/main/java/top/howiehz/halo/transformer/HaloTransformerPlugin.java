package top.howiehz.halo.transformer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import top.howiehz.halo.transformer.config.TransformerControllers;
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.ResourceOrder;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@RequiredArgsConstructor
@Component
public class HaloTransformerPlugin extends BasePlugin {
    private final SchemeManager schemeManager;
    private final TransformationRuleRuntimeStore transformationRuleRuntimeStore;
    private final TransformerControllers transformerControllers;

    @Override
    public void start() {
        registerScheme();
        startControllers();
        transformationRuleRuntimeStore.startWatching();
    }

    @Override
    public void stop() {
        transformationRuleRuntimeStore.stopWatching();
        stopControllers();
        unregisterScheme();
    }

    private void registerScheme() {
        schemeManager.register(TransformationSnippet.class);
        schemeManager.register(TransformationRule.class);
        schemeManager.register(ResourceOrder.class);
    }

    private void unregisterScheme() {
        unregisterSchemeIfPresent(TransformationSnippet.class);
        unregisterSchemeIfPresent(TransformationRule.class);
        unregisterSchemeIfPresent(ResourceOrder.class);
    }

    /**
     * why: Halo controller 负责把 deleting 资源收敛到最终一致状态；
     * 插件启动时必须显式拉起这些后台协调器，避免 finalizer 卡住却无人处理。
     */
    private void startControllers() {
        transformerControllers.startAll();
    }

    /**
     * why: 停插件时要先停掉后台协调器，再卸载 scheme；
     * 否则 controller 线程仍可能在使用已卸载的扩展类型。
     */
    private void stopControllers() {
        transformerControllers.stopAll();
    }

    /**
     * why: Halo may call stop() while rolling back a failed start();
     * at that point later schemes might not have been registered yet, so cleanup must be
     * idempotent.
     */
    private void unregisterSchemeIfPresent(Class<? extends Extension> extensionType) {
        schemeManager.fetch(GroupVersionKind.fromExtension(extensionType))
            .ifPresent(schemeManager::unregister);
    }
}
