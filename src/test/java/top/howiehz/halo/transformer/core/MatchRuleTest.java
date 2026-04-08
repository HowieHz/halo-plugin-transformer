package top.howiehz.halo.transformer.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MatchRuleTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: `isValid()` 只是运行期辅助判断，不应被序列化成 `valid`，否则会污染规则 JSON。
    @Test
    void shouldNotSerializeRuntimeValidFlag() throws Exception {
        MatchRule rule = MatchRule.defaultRule();

        String json = objectMapper.writeValueAsString(rule);

        assertFalse(json.contains("\"valid\""));
    }

    // why: 持久化形状必须只保留叶子节点真正支持的字段；
    // 否则服务端默认空集合会把 `children: []` 写回存储，再读出来就会把启用动作误判成非法写入。
    @Test
    void shouldNotSerializeEmptyLeafChildren() throws Exception {
        String json = objectMapper.writeValueAsString(MatchRule.defaultRule());

        assertTrue(json.contains("\"type\":\"PATH\""));
        assertFalse(json.contains("\"children\":[]"));
    }
}
