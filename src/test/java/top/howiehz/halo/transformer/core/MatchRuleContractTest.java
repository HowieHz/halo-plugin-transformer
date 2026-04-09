package top.howiehz.halo.transformer.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import top.howiehz.halo.transformer.core.generated.MatchRuleContractMessages;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchRuleContractTest {
    private static final Path CASES_FIXTURE = locateFixture("contract.cases.jsonc");
    private static final Path SPEC_FIXTURE = locateFixture("contract.spec.jsonc");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Path locateFixture(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("specs").resolve("match-rule").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot find specs/match-rule/" + fileName);
    }

    private static Stream<JsonNode> streamObjectNodes(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false);
    }

    private static Set<String> streamTextValues(JsonNode arrayNode, String fieldName) {
        return streamObjectNodes(arrayNode)
            .map(node -> node.path(fieldName).asText())
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> textList(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    }

    // why: `MatchRule` 在 Java 与 TypeScript 各有一份实现，这里用同一批样例锁住共享语义，避免两端规则树能力悄悄漂移。
    @DisplayName("match-rule write validation stays aligned with contract fixtures")
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void shouldMatchWriteValidationContract(ContractCase contractCase) {
        MatchRule rule = deserializeRule(contractCase.input());
        ResolvedWriteValidationExpectation expectation = resolveWriteValidation(
            contractCase.shared(),
            contractCase.javaSide(),
            "matchRule"
        );

        if (expectation.ok()) {
            assertDoesNotThrow(() -> MatchRule.validateForWrite(rule));
            return;
        }

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> MatchRule.validateForWrite(rule)
        );

        assertEquals(expectation.formattedMessage(), error.getMessage());
    }

    // why: DOM 预筛能力决定运行时是否能先按路径收窄范围，这属于前后端都依赖的共享契约，必须和同一批样例一起锁定。
    @DisplayName("match-rule dom-path-precheck stays aligned with contract fixtures")
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCasesWithDomPathPrecheck")
    void shouldMatchDomPathPrecheckContract(ContractCase contractCase) {
        MatchRule rule = deserializeRule(contractCase.input());

        MatchRule.validateForWrite(rule);

        assertEquals(
            contractCase.shared().domPathPrecheck(),
            MatchRule.supportsDomPathPrecheck(rule)
        );
    }

    // why: 布尔最小化规则属于共享语义，而不是前后端各自“差不多就行”的优化细节；
    // 这里复用同一批 fixture，保证 Java 运行时最小化与前端分析表达式对齐。
    @DisplayName("match-rule boolean minimization stays aligned with contract fixtures")
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCasesWithMinimizedExpression")
    void shouldMatchBooleanMinimizationContract(ContractCase contractCase) {
        MatchRule rule = deserializeRule(contractCase.input());

        MatchRule.validateForWrite(rule);

        assertEquals(
            contractCase.shared().minimizedSummary(),
            MatchRuleBooleanMinimizer.minimizedSummary(rule)
        );
    }

    // why: README 里的共享语义已经显式沉淀为 checklist；
    // 这里要求每个 shared contract 至少有一条 fixture 覆盖，避免文档加了语义但双端契约测试没跟上。
    @Test
    void shouldCoverEverySharedChecklistItemWithFixtureCases() {
        JsonNode spec = loadJson(SPEC_FIXTURE);
        Set<String> knownIds = streamTextValues(spec.path("checklist"), "id");
        Set<String> sharedIds = streamObjectNodes(spec.path("checklist"))
            .filter(item -> "shared_contract".equals(item.path("layer").asText()))
            .map(item -> item.path("id").asText())
            .collect(java.util.stream.Collectors.toSet());
        Set<String> coveredIds = loadContractSuite().cases().stream()
            .flatMap(contractCase -> contractCase.covers() == null
                ? Stream.empty()
                : contractCase.covers().stream())
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(), coveredIds.stream().filter(id -> !knownIds.contains(id))
            .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(), sharedIds.stream().filter(id -> !coveredIds.contains(id))
            .collect(java.util.stream.Collectors.toSet()));
    }

    // why: 允许字段集合和错误文本模板改成由共享 spec 生成后，
    // 这里直接反查 spec 文件，防止以后只改了源定义却忘了同步生成 Java helper。
    @Test
    void shouldKeepGeneratedJavaMetadataInSync() {
        JsonNode spec = loadJson(SPEC_FIXTURE);

        assertEquals(
            textList(spec.at("/nodeTypes/GROUP/allowedFields")),
            MatchRuleContractMessages.allowedFieldsFor(MatchRule.Type.GROUP)
        );
        assertEquals(
            textList(spec.at("/nodeTypes/PATH/allowedFields")),
            MatchRuleContractMessages.allowedFieldsFor(MatchRule.Type.PATH)
        );
        assertEquals(
            textList(spec.at("/nodeTypes/TEMPLATE_ID/allowedFields")),
            MatchRuleContractMessages.allowedFieldsFor(MatchRule.Type.TEMPLATE_ID)
        );
        assertEquals(
            textList(spec.at("/enumValues/TYPE/values")),
            MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.TYPE)
        );
        assertEquals(
            textList(spec.at("/enumValues/BOOLEAN/values")),
            MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.BOOLEAN)
        );
        assertEquals(
            textList(spec.at("/enumValues/OPERATOR/values")),
            MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.OPERATOR)
        );
        assertEquals(
            textList(spec.at("/enumValues/PATH_MATCHER/values")),
            MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.PATH_MATCHER)
        );
        assertEquals(
            textList(spec.at("/enumValues/TEMPLATE_MATCHER/values")),
            MatchRuleContractMessages.enumValuesFor(
                MatchRuleContractMessages.EnumName.TEMPLATE_MATCHER)
        );
    }

    private Stream<ContractCase> contractCases() {
        return loadContractSuite().cases().stream()
            .toList()
            .stream();
    }

    private Stream<ContractCase> contractCasesWithDomPathPrecheck() {
        return loadContractSuite().cases().stream()
            .filter(contractCase -> contractCase.shared() != null
                && contractCase.shared().domPathPrecheck() != null)
            .toList()
            .stream();
    }

    private Stream<ContractCase> contractCasesWithMinimizedExpression() {
        return loadContractSuite().cases().stream()
            .filter(contractCase -> contractCase.shared() != null
                && contractCase.shared().minimizedSummary() != null)
            .toList()
            .stream();
    }

    private ContractSuite loadContractSuite() {
        try {
            String json = normalizeJsonc(Files.readString(CASES_FIXTURE, StandardCharsets.UTF_8));
            return objectMapper.readValue(json, ContractSuite.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load match-rule contract fixtures", e);
        }
    }

    private MatchRule deserializeRule(JsonNode input) {
        try {
            return objectMapper.treeToValue(input, MatchRule.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize match-rule contract input", e);
        }
    }

    private JsonNode loadJson(Path path) {
        try {
            return objectMapper.readTree(
                normalizeJsonc(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load JSON fixture: " + path, e);
        }
    }

    private String normalizeJsonc(String content) {
        return stripTrailingCommas(stripJsonComments(content));
    }

    private String stripJsonComments(String content) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length() ? content.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false;
                    result.append(current);
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }

            if (inString) {
                result.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '/' && next == '/') {
                inLineComment = true;
                index++;
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }

            if (current == '"') {
                inString = true;
            }

            result.append(current);
        }

        return result.toString();
    }

    private String stripTrailingCommas(String content) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);

            if (inString) {
                result.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                result.append(current);
                continue;
            }

            if (current == ',') {
                int lookahead = index + 1;
                while (lookahead < content.length() && Character.isWhitespace(
                    content.charAt(lookahead))) {
                    lookahead++;
                }
                if (lookahead < content.length()) {
                    char trailingTarget = content.charAt(lookahead);
                    if (trailingTarget == '}' || trailingTarget == ']') {
                        continue;
                    }
                }
            }

            result.append(current);
        }

        return result.toString();
    }

    private ResolvedWriteValidationExpectation resolveWriteValidation(SharedExpectation shared,
        SideExpectation override,
        String rootPath) {
        WriteValidationExpectation sharedExpectation =
            shared != null ? shared.writeValidation() : null;
        WriteValidationExpectation overrideExpectation =
            override != null ? override.writeValidation() : null;

        Boolean ok = overrideExpectation != null && overrideExpectation.ok() != null
            ? overrideExpectation.ok()
            : sharedExpectation != null ? sharedExpectation.ok() : null;
        String relativePath =
            overrideExpectation != null && overrideExpectation.relativePath() != null
                ? overrideExpectation.relativePath()
                : sharedExpectation != null ? sharedExpectation.relativePath() : null;
        String message = overrideExpectation != null && overrideExpectation.message() != null
            ? overrideExpectation.message()
            : sharedExpectation != null ? sharedExpectation.message() : null;

        if (ok == null) {
            throw new IllegalStateException("Missing writeValidation.ok in contract fixture");
        }

        return new ResolvedWriteValidationExpectation(ok, formatRuntimePath(rootPath, relativePath),
            message);
    }

    private String formatRuntimePath(String rootPath, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return rootPath;
        }
        return rootPath + "." + relativePath;
    }

    private record ContractSuite(int version, List<ContractCase> cases) {
    }

    private record ContractCase(
        String name,
        JsonNode input,
        List<String> covers,
        SharedExpectation shared,
        @JsonProperty("java") SideExpectation javaSide,
        @JsonProperty("ts") SideExpectation ts
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record SharedExpectation(Boolean domPathPrecheck,
                                     String minimizedSummary,
                                     WriteValidationExpectation writeValidation) {
    }

    private record SideExpectation(WriteValidationExpectation writeValidation) {
    }

    private record WriteValidationExpectation(Boolean ok, String relativePath, String message) {
    }

    private record ResolvedWriteValidationExpectation(boolean ok, String path, String message) {
        private String formattedMessage() {
            return path + "：" + message;
        }
    }
}
