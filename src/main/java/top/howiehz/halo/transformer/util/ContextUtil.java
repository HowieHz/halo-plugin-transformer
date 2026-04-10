package top.howiehz.halo.transformer.util;

import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.context.Contexts;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.web.IWebRequest;
import run.halo.app.theme.router.ModelConst;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public class ContextUtil {
    public static final String TEMPLATE_ID_ATTRIBUTE = ContextUtil.class.getName() + ".templateId";

    public static String getPath(ITemplateContext context) {
        try {
            if (!Contexts.isWebContext(context)) {
                return "";
            }
            IWebRequest request = Contexts.asWebContext(context).getExchange().getRequest();
            return request.getRequestPath();
        } catch (Exception e) {
            return "";
        }
    }

    public static String getTemplateId(ITemplateContext context) {
        Object templateId = context.getVariable(ModelConst.TEMPLATE_ID);
        return templateId == null ? "" : templateId.toString();
    }

    public static String exposeTemplateId(ITemplateContext context) {
        String templateId = getTemplateId(context);
        try {
            if (Contexts.isWebContext(context)) {
                Contexts.asWebContext(context)
                    .getExchange()
                    .setAttributeValue(TEMPLATE_ID_ATTRIBUTE,
                        templateId.isBlank() ? null : templateId);
            }
        } catch (Exception ignored) {
        }
        return templateId;
    }

    public static String getTemplateId(ServerWebExchange exchange) {
        Object templateId = exchange.getAttribute(TEMPLATE_ID_ATTRIBUTE);
        return templateId == null ? "" : templateId.toString();
    }
}
