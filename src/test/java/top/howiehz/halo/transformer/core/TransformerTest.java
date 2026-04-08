package top.howiehz.halo.transformer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TransformerTest {
    private final Transformer transformer = new Transformer() {
    };

    // why: 默认应保留注释标记，避免升级后无配置的旧规则行为发生变化。
    @Test
    void shouldWrapCodeWithMarkersByDefault() {
        assertEquals(
            "<!-- PluginTransformer start --><script></script><!-- PluginTransformer end -->",
            transformer.processCode("<script></script>")
        );
    }

    // why: 用户显式关闭注释标记时，输出应保持原样，避免额外注释污染最终 HTML。
    @Test
    void shouldReturnOriginalCodeWhenMarkerDisabled() {
        assertEquals("<script></script>", transformer.processCode("<script></script>", false));
    }
}
