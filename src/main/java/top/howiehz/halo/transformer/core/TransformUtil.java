package top.howiehz.halo.transformer.core;

import org.jsoup.nodes.Element;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public class TransformUtil {
    public static void transformElement(Element element, String code,
        ITransformationRule.Position position) {
        switch (position) {
            case APPEND -> element.append(code);
            case PREPEND -> element.prepend(code);
            case BEFORE -> element.before(code);
            case AFTER -> element.after(code);
            case REPLACE -> {
                element.after(code);
                element.remove();
            }
            case REMOVE -> element.remove();
        }
    }
}
