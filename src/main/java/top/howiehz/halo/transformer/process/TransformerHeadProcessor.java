package top.howiehz.halo.transformer.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import top.howiehz.halo.transformer.core.HeadTransformer;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.util.TransformHelper;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@Slf4j
@Component
public class TransformerHeadProcessor extends AbstractTemplateProcessor
    implements TemplateHeadProcessor {
    private final HeadTransformer headTransformer;

    public TransformerHeadProcessor(TransformHelper transformHelper,
        HeadTransformer headTransformer) {
        super(transformHelper);
        this.headTransformer = headTransformer;
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
