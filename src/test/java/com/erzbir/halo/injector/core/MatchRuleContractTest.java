package com.erzbir.halo.injector.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchRuleContractTest {
    private static final Path CONTRACT_FIXTURE = locateContractFixture();
    private static final Path CHECKLIST_FIXTURE = locateFixture("match-rule-contract-checklist.generated.jsonc");
    private static final Path METADATA_FIXTURE = locateFixture("match-rule-contract-metadata.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // why: README 里的共享语义已经显式沉淀为 checklist；
    // 这里要求每个 shared contract 至少有一条 fixture 覆盖，避免文档加了语义但双端契约测试没跟上。
    @Test
    void shouldCoverEverySharedChecklistItemWithFixtureCases() {
        JsonNode checklist = loadJson(CHECKLIST_FIXTURE);
        Set<String> knownIds = streamTextValues(checklist.path("items"), "id");
        Set<String> sharedIds = streamObjectNodes(checklist.path("items"))
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

    // why: 允许字段集合和错误文本模板改成由共享 metadata 生成后，
    // 这里直接反查 metadata 文件，防止以后只改了源定义却忘了同步生成 Java helper。
    @Test
    void shouldKeepGeneratedJavaMetadataInSync() {
        JsonNode metadata = loadJson(METADATA_FIXTURE);
        JsonNode checklist = loadJson(CHECKLIST_FIXTURE);

        assertEquals(metadata.path("checklist"), checklist.path("items"));
        assertEquals(
                textList(metadata.at("/nodeTypes/GROUP/allowedFields")),
                MatchRuleContractMessages.allowedFieldsFor(MatchRule.Type.GROUP)
        );
        assertEquals(
                textList(metadata.at("/nodeTypes/PATH/allowedFields")),
                MatchRuleContractMessages.allowedFieldsFor(MatchRule.Type.PATH)
        );
        assertEquals(
                textList(metadata.at("/nodeTypes/TEMPLATE_ID/allowedFields")),
                MatchRuleContractMessages.allowedFieldsFor(MatchRule.Type.TEMPLATE_ID)
        );
        assertEquals(
                textList(metadata.at("/enumValues/TYPE/values")),
                MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.TYPE)
        );
        assertEquals(
                textList(metadata.at("/enumValues/BOOLEAN/values")),
                MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.BOOLEAN)
        );
        assertEquals(
                textList(metadata.at("/enumValues/OPERATOR/values")),
                MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.OPERATOR)
        );
        assertEquals(
                textList(metadata.at("/enumValues/PATH_MATCHER/values")),
                MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.PATH_MATCHER)
        );
        assertEquals(
                textList(metadata.at("/enumValues/TEMPLATE_MATCHER/values")),
                MatchRuleContractMessages.enumValuesFor(MatchRuleContractMessages.EnumName.TEMPLATE_MATCHER)
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

    private ContractSuite loadContractSuite() {
        try {
            String json = Files.readString(CONTRACT_FIXTURE, StandardCharsets.UTF_8);
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

    private static Path locateContractFixture() {
        return locateFixture("match-rule-contracts.json");
    }

    private static Path locateFixture(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot find contracts/" + fileName);
    }

    private JsonNode loadJson(Path path) {
        try {
            return objectMapper.readTree(stripJsoncHeader(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load JSON fixture: " + path, e);
        }
    }

    private String stripJsoncHeader(String content) {
        return content.lines()
                .filter(line -> !line.stripLeading().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));
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

    private record ContractSuite(int version, List<ContractCase> cases) {
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
        String relativePath = overrideExpectation != null && overrideExpectation.relativePath() != null
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

    private record SharedExpectation(Boolean domPathPrecheck, WriteValidationExpectation writeValidation) {
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
