package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebInputException;
import run.halo.app.extension.Metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceOrderEndpointTest {
    private final ResourceOrderEndpoint endpoint = new ResourceOrderEndpoint(null);

    // why: 排序映射里 0 代表“未显式排序”，入库时应直接剔除；这样新增项才能继续以默认 0 顶到最前面。
    @Test
    void shouldDropZeroOrdersBeforePersisting() {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        incoming.put("snippet-a", 0);
        incoming.put("snippet-b", 2);

        Map<String, Integer> sanitized = endpoint.validateIncomingOrders(incoming, "代码块");

        assertEquals(Map.of("snippet-b", 2), sanitized);
    }

    // why: 排序值只允许非负整数；负值会破坏“0 为默认、值越小越靠前”的约定，必须在入口直接拦下。
    @Test
    void shouldRejectNegativeOrders() {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        incoming.put("snippet-a", -1);

        ServerWebInputException error = assertThrows(ServerWebInputException.class,
                () -> endpoint.validateIncomingOrders(incoming, "代码块"));

        assertEquals("代码块排序项 snippet-a 的值不能小于 0", error.getReason());
    }

    // why: 同值项会回落到“名称字符序”；后端清洗映射时也应保持这个稳定顺序，避免返回给前端的 JSON 键位来回跳。
    @Test
    void shouldSortSamePriorityByDisplayName() {
        InjectionRule zebra = rule("rule-z", "Zebra");
        InjectionRule alpha = rule("rule-a", "Alpha");

        Map<String, Integer> sanitized = endpoint.sanitizeOrders(
                Map.of("rule-z", 1, "rule-a", 1),
                List.of(zebra, alpha),
                InjectionRule::getName
        );

        assertEquals(List.of("rule-a", "rule-z"), sanitized.keySet().stream().toList());
    }

    // why: 已删除资源的旧排序项不该再继续污染最新顺序；下一次提交时应自动清掉无效 id。
    @Test
    void shouldDropDeletedIdsWhenSanitizingOrders() {
        CodeSnippet existing = snippet("snippet-a", "Alpha");

        Map<String, Integer> sanitized = endpoint.sanitizeOrders(
                Map.of("snippet-a", 1, "snippet-deleted", 2),
                List.of(existing),
                CodeSnippet::getName
        );

        assertEquals(Map.of("snippet-a", 1), sanitized);
    }

    private InjectionRule rule(String id, String name) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setName(name);
        return rule;
    }

    private CodeSnippet snippet(String id, String name) {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setName(name);
        return snippet;
    }
}
