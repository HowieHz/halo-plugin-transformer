package top.howiehz.halo.transformer.core;

/**
 * @author HowieHz
 * @since 1.0.0
 */
public interface Transformer {
    String START_MARK = "<!-- PluginTransformer start -->";
    String END_MARK = "<!-- PluginTransformer end -->";

    default String processCode(String code) {
        return processCode(code, true);
    }

    default String processCode(String code, boolean wrapMarker) {
        if (!wrapMarker) {
            return code;
        }
        return START_MARK + code + END_MARK;
    }
}
