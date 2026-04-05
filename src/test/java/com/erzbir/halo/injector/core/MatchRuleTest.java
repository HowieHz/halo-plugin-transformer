package com.erzbir.halo.injector.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MatchRuleTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: `isValid()` 只是运行期辅助判断，不应被序列化成 `valid`，否则会污染规则 JSON。
    @Test
    void shouldNotSerializeRuntimeValidFlag() throws Exception {
        MatchRule rule = MatchRule.defaultRule();

        String json = objectMapper.writeValueAsString(rule);

        assertFalse(json.contains("\"valid\""));
    }
}
