package com.erzbir.halo.injector.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchRuleContractTest {
    private static final Path CONTRACT_FIXTURE = locateContractFixture();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: `MatchRule` 在 Java 与 TypeScript 各有一份实现，这里用同一批样例锁住共享语义，避免两端规则树能力悄悄漂移。
    @DisplayName("match-rule write validation stays aligned with contract fixtures")
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void shouldMatchWriteValidationContract(ContractCase contractCase) {
        MatchRule rule = deserializeRule(contractCase.input());
        WriteValidationExpectation expectation = contractCase.javaSide().writeValidation();

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
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve("match-rule-contracts.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot find contracts/match-rule-contracts.json");
    }

    private record ContractSuite(int version, List<ContractCase> cases) {
    }

    private record ContractCase(
            String name,
            JsonNode input,
            SharedExpectation shared,
            @JsonProperty("java") SideExpectation javaSide,
            SideExpectation ts
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record SharedExpectation(Boolean domPathPrecheck) {
    }

    private record SideExpectation(WriteValidationExpectation writeValidation) {
    }

    private record WriteValidationExpectation(boolean ok, String path, String message) {
        private String formattedMessage() {
            return path + "：" + message;
        }
    }
}
