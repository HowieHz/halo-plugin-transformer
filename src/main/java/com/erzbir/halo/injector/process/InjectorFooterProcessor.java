package com.erzbir.halo.injector.process;

import com.erzbir.halo.injector.core.FooterInjector;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.InjectHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateFooterProcessor;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@Slf4j
@Component
public class InjectorFooterProcessor extends AbstractTemplateProcessor
        implements TemplateFooterProcessor {
    private final FooterInjector footerInjector;

    public InjectorFooterProcessor(InjectHelper injectHelper, FooterInjector footerInjector) {
        super(injectHelper);
        this.footerInjector = footerInjector;
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IProcessableElementTag tag,
                              IElementTagStructureHandler structureHandler, IModel model) {
        return processInternal(context, model);
    }

    @Override
    protected InjectionRule.Mode mode() {
        return InjectionRule.Mode.FOOTER;
    }

    @Override
    protected void doProcess(ITemplateContext context, IModel model, String code, boolean wrapMarker) {
        footerInjector.inject(context, model, code, wrapMarker);
    }
}
