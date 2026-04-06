package com.erzbir.halo.injector.process;

import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.ContextUtil;
import com.erzbir.halo.injector.util.InjectHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import reactor.core.publisher.Mono;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@RequiredArgsConstructor
@Slf4j
public abstract class AbstractTemplateProcessor {

    protected final InjectHelper injectHelper;

    protected abstract InjectionRule.Mode mode();

    protected abstract void doProcess(ITemplateContext context, IModel model, String code, boolean wrapMarker);


    protected Mono<Void> processInternal(ITemplateContext context, IModel model) {
        String path = ContextUtil.getPath(context);
        String templateId = ContextUtil.exposeTemplateId(context);

        return injectHelper.getMatchedRules(path, templateId, mode())
                .collectList()
                .flatMap(injectHelper::resolveRuleCodes)
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .doOnNext(resolved -> {
                                    InjectionRule rule = resolved.rule();
                                    doProcess(context, model, resolved.code(), rule.getWrapMarker());
                                })
                .doOnNext(resolved -> log.debug("Injected rule: [{}] into [{}]",
                        resolved.rule().getId(),
                        path))
                .then();
    }
}
