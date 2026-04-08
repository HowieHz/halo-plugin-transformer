package top.howiehz.halo.transformer.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransformUtilTest {
    // why: REMOVE 语义必须是“整节点移除”，避免实现成清空内容或隐藏元素。
    @Test
    void shouldRemoveElementWhenPositionIsRemove() {
        Document document = Jsoup.parse("<div><span id='target'>hello</span><p>world</p></div>");
        Element target = document.getElementById("target");

        TransformUtil.transformElement(target, "<em>ignored</em>", ITransformationRule.Position.REMOVE);

        assertNull(document.getElementById("target"));
        Element paragraph = document.body().selectFirst("div > p");
        assertNotNull(paragraph);
        assertEquals("world", paragraph.text());
    }
}
