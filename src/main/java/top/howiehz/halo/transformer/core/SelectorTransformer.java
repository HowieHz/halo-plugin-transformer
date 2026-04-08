package top.howiehz.halo.transformer.core;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@Component
public class SelectorTransformer implements HTMLTransformer {

    @Override
    public boolean transform(Document document, String match, String code, ITransformationRule.Position position,
                             boolean wrapMarker) {
        Elements elements = document.select(match);
        if (elements.isEmpty()) {
            return false;
        }

        for (Element element : elements) {
            TransformUtil.transformElement(element, processCode(code, wrapMarker), position);
        }
        return true;
    }
}
