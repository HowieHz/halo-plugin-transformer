package top.howiehz.halo.transformer.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import top.howiehz.halo.transformer.runtime.transform.HeadTransformer;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.runtime.RuntimeRuleResolver;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@Slf4j
@Component
public class TransformerHeadProcessor extends AbstractTemplateProcessor
    implements TemplateHeadProcessor {
    private final HeadTransformer headTransformer = new HeadTransformer();

    public TransformerHeadProcessor(RuntimeRuleResolver transformHelper) {
        super(transformHelper);
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
        IElementModelStructureHandler structureHandler) {
        return processInternal(context, model);
    }

    @Override
    protected TransformationRule.Mode mode() {
        return TransformationRule.Mode.HEAD;
    }

    @Override
    protected void doProcess(ITemplateContext context, IModel model, String code,
        boolean wrapMarker) {
        headTransformer.transform(context, model, code, wrapMarker);
    }
}
