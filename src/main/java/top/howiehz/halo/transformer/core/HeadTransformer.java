package top.howiehz.halo.transformer.core;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@Component
public class HeadTransformer implements TemplateTransformer {

    @Override
    public void transform(ITemplateContext context, IModel model, String code, boolean wrapMarker) {
        model.add(context.getModelFactory().createText(processCode(code, wrapMarker)));
    }
}
