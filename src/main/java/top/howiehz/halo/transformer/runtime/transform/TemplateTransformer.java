package top.howiehz.halo.transformer.runtime.transform;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public interface TemplateTransformer extends Transformer {
    void transform(ITemplateContext context, IModel model, String code, boolean wrapMarker);
}
