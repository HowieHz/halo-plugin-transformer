package top.howiehz.halo.transformer.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateFooterProcessor;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.runtime.RuntimeRuleResolver;
import top.howiehz.halo.transformer.runtime.transform.FooterTransformer;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@Slf4j
@Component
public class TransformerFooterProcessor extends AbstractTemplateProcessor
    implements TemplateFooterProcessor {
    private final FooterTransformer footerTransformer = new FooterTransformer();

    public TransformerFooterProcessor(RuntimeRuleResolver transformHelper) {
        super(transformHelper);
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IProcessableElementTag tag,
        IElementTagStructureHandler structureHandler, IModel model) {
        return processInternal(context, model);
    }

    @Override
    protected TransformationRule.Mode mode() {
        return TransformationRule.Mode.FOOTER;
    }

    @Override
    protected void doProcess(ITemplateContext context, IModel model, String code,
        boolean wrapMarker) {
        footerTransformer.transform(context, model, code, wrapMarker);
    }
}
