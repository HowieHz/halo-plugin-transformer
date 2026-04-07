package com.erzbir.halo.injector.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InjectionRuleTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: DOM 注入规则即使会退化成全站 HTML 处理，也应允许保存，由 UI 给出性能警告即可。
    @Test
    void shouldAllowUnsupportedDomRuleWhenConvertedToInjectionRule() {
        InjectionRule rule = assertDoesNotThrow(
                () -> objectMapper.convertValue(
                        Map.of(
                                "mode", "SELECTOR",
                                "match", "main",
                                "matchRule", Map.of(
                                        "type", "GROUP",
                                        "operator", "OR",
                                        "children", List.of(
                                                Map.of(
                                                        "type", "PATH",
                                                        "matcher", "ANT",
                                                        "value", "/posts/**"
                                                ),
                                                Map.of(
                                                        "type", "TEMPLATE_ID",
                                                        "matcher", "EXACT",
                                                        "value", "post"
                                                )
                                        )
                                )
                        ),
                        InjectionRule.class
                )
        );

        assertTrue(rule.isValid());
    }

    // why: `isValid()` 是后端运行期辅助状态，不应出现在 JSON 里污染前端回写 payload。
    @Test
    void shouldNotSerializeRuntimeValidFlag() throws Exception {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName("rule-a");
        rule.setMetadata(metadata);
        rule.setMode(InjectionRule.Mode.FOOTER);

        String json = objectMapper.writeValueAsString(rule);

        assertFalse(json.contains("\"valid\""));
    }

    // why: 删除独立 ID 模式后，历史规则仍可能以旧值存在；
    // 读取期必须把它无损迁成 SELECTOR，并把旧 id 值改写成 #id 选择器，避免启动时直接炸。
    @Test
    void shouldMigrateLegacyIdModeToSelectorWithHashPrefixedMatch() throws Exception {
        InjectionRule rule = objectMapper.readValue("""
                {
                  "metadata": {
                    "name": "rule-a"
                  },
                  "mode": "ID",
                  "match": "main-content"
                }
                """, InjectionRule.class);

        assertEquals(InjectionRule.Mode.SELECTOR, rule.getMode());
        assertEquals("#main-content", rule.getMatch());
    }

    // why: 迁移兼容只发生在读取旧数据时；重新序列化时必须只输出新语义，
    // 避免再把已经废弃的 ID 模式写回持久化层。
    @Test
    void shouldSerializeMigratedLegacyIdModeAsSelectorOnly() throws Exception {
        InjectionRule rule = objectMapper.readValue("""
                {
                  "metadata": {
                    "name": "rule-a"
                  },
                  "mode": "ID",
                  "match": "main-content"
                }
                """, InjectionRule.class);

        String json = objectMapper.writeValueAsString(rule);

        assertTrue(json.contains("\"mode\":\"SELECTOR\""));
        assertTrue(json.contains("\"match\":\"#main-content\""));
        assertFalse(json.contains("\"mode\":\"ID\""));
    }

    // why: `id` 是控制台读模型字段，不应再被反序列化进存储实体；
    // 这样新旧客户端混用时，也不会再因为把 `id` 写回而触发严格校验报错。
    @Test
    void shouldTreatIdAsReadOnlyResponseField() throws Exception {
        InjectionRule rule = objectMapper.readValue("""
                {
                  "metadata": {
                    "name": "rule-a"
                  },
                  "id": "rule-a",
                  "mode": "FOOTER"
                }
                """, InjectionRule.class);

        assertEquals("rule-a", rule.getId());
        assertTrue(rule.isValid());
    }
}
