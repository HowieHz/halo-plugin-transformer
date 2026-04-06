package com.erzbir.halo.injector.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * @author Erzbir
 * @since 1.0.0
 */
public interface HTMLInjector extends Injector {
    boolean inject(Document document, String match, String code, IInjectionRule.Position position,
                   boolean wrapMarker);

    default String inject(String html, String match, String code, IInjectionRule.Position position,
                          boolean wrapMarker) {
        Document document = Jsoup.parse(html);
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        boolean modified = inject(document, match, code, position, wrapMarker);
        return modified ? document.html() : html;
    }
}
