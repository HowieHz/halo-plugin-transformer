package top.howiehz.halo.transformer.endpoint;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.MetadataOperator;
import top.howiehz.halo.transformer.rule.MatchRule;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.extension.TransformationSnippet;

@Component
public class ConsoleReadModelMapper {
    static final String API_VERSION = "transformer.howiehz.top/v1alpha1";

    /**
     * why: 存储实体只保留持久化语义；控制台需要的 `id` 等派生字段由统一映射生成，
     * 避免以后每多一个只读展示字段，都再长回实体层或字段清洗补丁。
     */
    public TransformationSnippetReadModel toReadModel(TransformationSnippet snippet) {
        return new TransformationSnippetReadModel(
            API_VERSION,
            "TransformationSnippet",
            toConsoleMetadata(snippet.getMetadata()),
            snippet.getMetadata() == null ? "" : snippet.getMetadata().getName(),
            snippet.getName(),
            snippet.getCode(),
            snippet.getDescription(),
            Boolean.TRUE.equals(snippet.getEnabled())
        );
    }

    public TransformationRuleReadModel toReadModel(TransformationRule rule) {
        return new TransformationRuleReadModel(
            API_VERSION,
            "TransformationRule",
            toConsoleMetadata(rule.getMetadata()),
            rule.getMetadata() == null ? "" : rule.getMetadata().getName(),
            rule.getName(),
            rule.getDescription(),
            Boolean.TRUE.equals(rule.getEnabled()),
            rule.getMode(),
            rule.getMatch(),
            MatchRule.canonicalizeForStorage(rule.getMatchRule()),
            rule.getPosition(),
            rule.getWrapMarker(),
            rule.getRuntimeOrder(),
            rule.getSnippetIds() == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(rule.getSnippetIds())
        );
    }

    public ConsoleItemList<TransformationSnippetReadModel> toSnippetList(
        List<TransformationSnippet> snippets) {
        return ConsoleItemList.of(snippets.stream()
            .filter(snippet -> !ExtensionUtil.isDeleted(snippet))
            .map(this::toReadModel)
            .toList());
    }

    public ConsoleItemList<TransformationRuleReadModel> toRuleList(List<TransformationRule> rules) {
        return ConsoleItemList.of(rules.stream()
            .filter(rule -> !ExtensionUtil.isDeleted(rule))
            .map(this::toReadModel)
            .toList());
    }

    public ConsoleOrderedItemList<TransformationSnippetReadModel> toSnippetSnapshot(
        List<TransformationSnippet> snippets,
        Map<String, Integer> orders,
        Long orderVersion) {
        return ConsoleOrderedItemList.of(toSnippetList(snippets), orders, orderVersion);
    }

    public ConsoleOrderedItemList<TransformationRuleReadModel> toRuleSnapshot(
        List<TransformationRule> rules,
        Map<String, Integer> orders,
        Long orderVersion) {
        return ConsoleOrderedItemList.of(toRuleList(rules), orders, orderVersion);
    }

    private ConsoleResourceMetadata toConsoleMetadata(MetadataOperator metadata) {
        if (metadata == null) {
            return null;
        }
        return new ConsoleResourceMetadata(metadata.getName(), metadata.getVersion());
    }
}
