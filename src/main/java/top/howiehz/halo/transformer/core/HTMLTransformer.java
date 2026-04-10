package top.howiehz.halo.transformer.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public interface HTMLTransformer extends Transformer {
    boolean transform(Document document, String match, String code,
        ITransformationRule.Position position,
        boolean wrapMarker);

    default String transform(String html, String match, String code,
        ITransformationRule.Position position,
        boolean wrapMarker) {
        Document document = Jsoup.parse(html);
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        boolean modified = transform(document, match, code, position, wrapMarker);
        return modified ? document.html() : html;
    }
}
