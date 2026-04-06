package com.erzbir.halo.injector.core;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@Component
public class SelectorInjector implements HTMLInjector {

    @Override
    public boolean inject(Document document, String match, String code, IInjectionRule.Position position,
                          boolean wrapMarker) {
        Elements elements = document.select(match);
        if (elements.isEmpty()) {
            return false;
        }

        for (Element element : elements) {
            InjectUtil.inject(element, processCode(code, wrapMarker), position);
        }
        return true;
    }
}
