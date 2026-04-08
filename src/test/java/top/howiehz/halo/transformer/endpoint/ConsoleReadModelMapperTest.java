package top.howiehz.halo.transformer.endpoint;

import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleReadModelMapperTest {
    private final ConsoleReadModelMapper mapper = new ConsoleReadModelMapper();

    // why: `id` 已从持久化实体 JSON 中移除后，控制台仍需要稳定的读模型主键；
    // projection 必须统一从 `metadata.name` 派生它，而不是让前端自己猜。
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

    // why: 规则 read model 也必须由同一 projection 统一生成，
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
}
