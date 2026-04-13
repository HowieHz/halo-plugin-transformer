package top.howiehz.halo.transformer.filter;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import top.howiehz.halo.transformer.core.HTMLTransformer;
import top.howiehz.halo.transformer.core.ITransformationRule;
import top.howiehz.halo.transformer.core.TransformUtil;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public class SelectorTransformer implements HTMLTransformer {

    @Override
    public boolean transform(Document document, String match, String code,
        ITransformationRule.Position position,
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
