package top.howiehz.halo.transformer.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.util.OptimisticConcurrencyGuard;

class ResourceOrderEndpointTest {
    private final ResourceOrderEndpoint endpoint = new ResourceOrderEndpoint(null);

    // why: 排序映射里 0 代表“未显式排序”，入库时应直接剔除；这样新增项才能继续以默认 0 顶到最前面。
    @Test
    void shouldDropZeroOrdersBeforePersisting() {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        incoming.put("snippet-a", 0);
        incoming.put("snippet-b", 2);

        Map<String, Integer> sanitized = endpoint.validateIncomingOrders(incoming, "代码片段");

        assertEquals(Map.of("snippet-b", 2), sanitized);
    }

    // why: 排序值只允许非负整数；负值会破坏“0 为默认、值越小越靠前”的约定，必须在入口直接拦下。
    @Test
    void shouldRejectNegativeOrders() {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        incoming.put("snippet-a", -1);

        ServerWebInputException error = assertThrows(ServerWebInputException.class,
            () -> endpoint.validateIncomingOrders(incoming, "代码片段"));

        assertEquals("代码片段排序项 snippet-a 的值不能小于 0", error.getReason());
    }

    // why: 同值项会回落到“名称字符序”；后端清洗映射时也应保持这个稳定顺序，避免返回给前端的 JSON 键位来回跳。
    @Test
    void shouldSortSamePriorityByDisplayName() {
        TransformationRule zebra = rule("rule-z", "Zebra");
        TransformationRule alpha = rule("rule-a", "Alpha");

        Map<String, Integer> sanitized = endpoint.sanitizeOrders(
            Map.of("rule-z", 1, "rule-a", 1),
            List.of(zebra, alpha),
            TransformationRule::getName
        );

        assertEquals(List.of("rule-a", "rule-z"), sanitized.keySet().stream().toList());
    }

    // why: 已删除资源的旧排序项不该再继续污染最新顺序；下一次提交时应自动清掉无效 id。
    @Test
    void shouldDropDeletedIdsWhenSanitizingOrders() {
        TransformationSnippet existing = snippet("snippet-a", "Alpha");

        Map<String, Integer> sanitized = endpoint.sanitizeOrders(
            Map.of("snippet-a", 1, "snippet-deleted", 2),
            List.of(existing),
            TransformationSnippet::getName
        );

        assertEquals(Map.of("snippet-a", 1), sanitized);
    }

    // why: 排序映射一旦进入后端，就应收敛成单一规范形状；
    // 即使客户端传了稀疏值或重复值，持久化结果也必须被标准化为稳定的 1..n。
    @Test
    void shouldCanonicalizeOrdersIntoSequentialRanking() {
        TransformationRule zebra = rule("rule-z", "Zebra");
        TransformationRule alpha = rule("rule-a", "Alpha");

        Map<String, Integer> sanitized = endpoint.sanitizeOrders(
            Map.of("rule-z", 50, "rule-a", 50),
            List.of(zebra, alpha),
            TransformationRule::getName
        );

        assertEquals(Map.of("rule-a", 1, "rule-z", 2), sanitized);
    }

    // why: 删除中的资源仍可能暂时出现在底层 list 结果里；
    // 排序读模型不能继续把它当成有效资源，否则控制台左侧顺序会比可见列表慢一拍。
    @Test
    void shouldIgnoreDeletingResourcesWhenSanitizingOrders() {
        TransformationSnippet active = snippet("snippet-a", "Alpha");
        TransformationSnippet deleting = snippet("snippet-b", "Beta");
        deleting.getMetadata().setDeletionTimestamp(Instant.now());

        Map<String, Integer> sanitized = endpoint.sanitizeOrders(
            Map.of("snippet-a", 1, "snippet-b", 2),
            List.of(active, deleting),
            TransformationSnippet::getName
        );

        assertEquals(Map.of("snippet-a", 1), sanitized);
    }

    // why: 排序接口现在依赖 Halo 的 metadata.version 做乐观并发；
    // payload 必须显式携带版本，不能再默默退回“最后一次写入覆盖前面所有写入”。
    @Test
    void shouldExposeOrderPayloadVersion() {
        ResourceOrderEndpoint.OrderPayload payload = new ResourceOrderEndpoint.OrderPayload();
        Metadata metadata = new Metadata();
        metadata.setVersion(12L);
        payload.setMetadata(metadata);

        assertEquals(12L, payload.getMetadata().getVersion());
    }

    // why: 排序拖拽的冲突语义必须是显式 409；
    // 否则平台有 version，插件接口却还在静默覆盖，等于把 Halo 的保护绕掉了。
    @Test
    void shouldRejectStaleOrderVersion() {
        Metadata persisted = new Metadata();
        persisted.setVersion(5L);
        Metadata incoming = new Metadata();
        incoming.setVersion(4L);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> OptimisticConcurrencyGuard.requireMatchingVersion(persisted, incoming,
                "代码片段排序配置")
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("代码片段排序配置已被其他人修改，请刷新后重试", error.getReason());
    }

    private TransformationRule rule(String id, String name) {
        TransformationRule rule = new TransformationRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setName(name);
        return rule;
    }

    private TransformationSnippet snippet(String id, String name) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setName(name);
        return snippet;
    }
}
