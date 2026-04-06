package com.erzbir.halo.injector.core;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@Component
public class ElementIDInjector implements HTMLInjector {

    @Override
    public boolean inject(Document document, String match, String code, IInjectionRule.Position position,
                          boolean wrapMarker) {
        Element element = document.getElementById(match);
        if (element == null) {
            return false;
        }

        InjectUtil.inject(element, processCode(code, wrapMarker), position);
        return true;
    }
}
