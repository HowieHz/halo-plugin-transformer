package top.howiehz.halo.transformer.runtime.transform;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import top.howiehz.halo.transformer.extension.TransformationRule;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public interface HTMLTransformer extends Transformer {
    boolean transform(Document document, String match, String code,
        TransformationRule.Position position,
        boolean wrapMarker);

    default String transform(String html, String match, String code,
        TransformationRule.Position position,
        boolean wrapMarker) {
        Document document = Jsoup.parse(html);
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        boolean modified = transform(document, match, code, position, wrapMarker);
        return modified ? document.html() : html;
    }
}
