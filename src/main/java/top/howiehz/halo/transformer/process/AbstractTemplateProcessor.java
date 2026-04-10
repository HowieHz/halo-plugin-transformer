package top.howiehz.halo.transformer.process;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import reactor.core.publisher.Mono;
import top.howiehz.halo.transformer.core.RuntimeTransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.util.ContextUtil;
import top.howiehz.halo.transformer.util.TransformHelper;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@RequiredArgsConstructor
@Slf4j
public abstract class AbstractTemplateProcessor {

    protected final TransformHelper transformHelper;

    protected abstract TransformationRule.Mode mode();

    protected abstract void doProcess(ITemplateContext context, IModel model, String code,
        boolean wrapMarker);


    protected Mono<Void> processInternal(ITemplateContext context, IModel model) {
        String path = ContextUtil.getPath(context);
        String templateId = ContextUtil.exposeTemplateId(context);

        return transformHelper.getMatchedRules(path, templateId, mode())
            .collectList()
            .flatMap(transformHelper::resolveRuleCodes)
            .flatMapMany(reactor.core.publisher.Flux::fromIterable)
            .doOnNext(resolved -> {
                RuntimeTransformationRule rule = resolved.rule();
                doProcess(context, model, resolved.code(), rule.wrapMarker());
            })
            .doOnNext(resolved -> log.debug("Transformed rule: [{}] into [{}]",
                resolved.rule().resourceName(),
                path))
            .then();
    }
}
