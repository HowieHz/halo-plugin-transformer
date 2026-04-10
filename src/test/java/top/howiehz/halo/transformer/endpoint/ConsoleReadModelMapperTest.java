package top.howiehz.halo.transformer.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.core.MatchRule;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

class ConsoleReadModelMapperTest {
    private final ConsoleReadModelMapper mapper = new ConsoleReadModelMapper();

    // why: `id` 已从持久化实体 JSON 中移除后，控制台仍需要稳定的读模型主键；
    // 响应模型必须统一从 `metadata.name` 派生这个字段，而不是让前端自己猜。
    @Test
    void shouldProjectSnippetIdFromMetadataName() {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        metadata.setVersion(3L);
        snippet.setMetadata(metadata);
        snippet.setName("Snippet A");
        snippet.setCode("<div>ok</div>");
        snippet.setDescription("desc");
        snippet.setEnabled(true);

        TransformationSnippetReadModel readModel = mapper.toReadModel(snippet);

        assertEquals("snippet-a", readModel.id());
        assertEquals("Snippet A", readModel.name());
        assertEquals(3L, readModel.metadata().version());
    }

    // why: 规则响应模型（read model）也必须由同一套映射逻辑统一生成，
    // 这样控制台列表与写接口响应就不会再直接耦合到存储实体的序列化细节。
    @Test
    void shouldProjectRuleReadModelFromStoredRule() {
        TransformationRule rule = new TransformationRule();
        Metadata metadata = new Metadata();
        metadata.setName("rule-a");
        metadata.setVersion(5L);
        rule.setMetadata(metadata);
        rule.setName("Rule A");
        rule.setEnabled(true);
        rule.setMode(TransformationRule.Mode.FOOTER);
        rule.setPosition(TransformationRule.Position.APPEND);
        rule.setRuntimeOrder(42);
        rule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a")));

        TransformationRuleReadModel readModel = mapper.toReadModel(rule);

        assertEquals("rule-a", readModel.id());
        assertEquals("Rule A", readModel.name());
        assertEquals(5L, readModel.metadata().version());
        assertEquals(42, readModel.runtimeOrder());
        assertEquals(Set.of("snippet-a"), readModel.snippetIds());
    }

    // why: 读投影应主动输出 canonical 的 `matchRule` 形状；
    // 这样旧脏数据不会在控制台里再被原样回写给写接口。
    @Test
    void shouldCanonicalizeMatchRuleInRuleReadModel() {
        TransformationRule rule = new TransformationRule();
        Metadata metadata = new Metadata();
        metadata.setName("rule-a");
        rule.setMetadata(metadata);
        MatchRule dirtyLeaf = MatchRule.pathRule(MatchRule.Matcher.ANT, "/**");
        dirtyLeaf.setChildren(List.of());
        MatchRule dirtyRoot = new MatchRule();
        dirtyRoot.setNegate(false);
        dirtyRoot.setOperator(MatchRule.Operator.AND);
        dirtyRoot.setChildren(List.of(dirtyLeaf));
        rule.setMatchRule(dirtyRoot);

        TransformationRuleReadModel readModel = mapper.toReadModel(rule);

        assertNull(readModel.matchRule().getChildren().getFirst().getChildren());
    }

    // why: 代码片段删除走 finalizer 最终一致流程；控制台列表必须立刻把“删除中”的资源隐藏掉，
    // 否则左侧列表会滞后一拍，用户会误以为第一次删除没有生效。
    @Test
    void shouldHideDeletingSnippetFromSnippetList() {
        TransformationSnippet activeSnippet = new TransformationSnippet();
        Metadata activeMetadata = new Metadata();
        activeMetadata.setName("snippet-active");
        activeSnippet.setMetadata(activeMetadata);
        activeSnippet.setName("Active");

        TransformationSnippet deletingSnippet = new TransformationSnippet();
        Metadata deletingMetadata = new Metadata();
        deletingMetadata.setName("snippet-deleting");
        deletingMetadata.setDeletionTimestamp(Instant.now());
        deletingSnippet.setMetadata(deletingMetadata);
        deletingSnippet.setName("Deleting");

        ConsoleItemList<TransformationSnippetReadModel> list = mapper.toSnippetList(
            List.of(activeSnippet, deletingSnippet));

        assertEquals(1, list.items().size());
        assertEquals("snippet-active", list.items().getFirst().id());
    }

    // why: 控制台读模型应对“删除中”的资源保持同一套可见性语义；
    // 即使规则删除通常更快完成，也不该把“删除中”的规则继续暴露给左侧列表。
    @Test
    void shouldHideDeletingRuleFromRuleList() {
        TransformationRule activeRule = new TransformationRule();
        Metadata activeMetadata = new Metadata();
        activeMetadata.setName("rule-active");
        activeRule.setMetadata(activeMetadata);

        TransformationRule deletingRule = new TransformationRule();
        Metadata deletingMetadata = new Metadata();
        deletingMetadata.setName("rule-deleting");
        deletingMetadata.setDeletionTimestamp(Instant.now());
        deletingRule.setMetadata(deletingMetadata);

        ConsoleItemList<TransformationRuleReadModel> list = mapper.toRuleList(
            List.of(activeRule, deletingRule));

        assertEquals(1, list.items().size());
        assertEquals("rule-active", list.items().getFirst().id());
        assertTrue(list.items().stream().noneMatch(item -> item.id().equals("rule-deleting")));
    }

    // why: 前端左侧顺序真正依赖“列表 + 排序映射 + 排序版本”这一个快照；
    // 读模型映射必须把三者一起输出，避免前端常规刷新时再拼成跨时刻混合态。
    @Test
    void shouldProjectSnippetSnapshotWithOrdersAndVersion() {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setName("Snippet A");

        ConsoleOrderedItemList<TransformationSnippetReadModel> snapshot = mapper.toSnippetSnapshot(
            List.of(snippet),
            Map.of("snippet-a", 1),
            7L
        );

        assertEquals(1, snapshot.items().size());
        assertEquals(Map.of("snippet-a", 1), snapshot.orders());
        assertEquals(7L, snapshot.orderVersion());
    }
}
