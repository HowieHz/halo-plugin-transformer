/**
 * why: `match-rule` 的共享规范需要同时驱动前端 helper、后端消息模板和导入导出 schema；
 * 这里把 spec/cases 与 generated artifacts 收到同一条生成链，避免 schema、fixture、
 * 错误文本模板和允许字段集合在多处重复维护。
 *
 * Source files:
 * - `specs/match-rule/contract.spec.jsonc`
 * - `specs/match-rule/contract.cases.jsonc`
 *
 * Generated files:
 * - `ui/src/views/composables/matchRuleContract.generated.ts`
 * - `src/main/java/top/howiehz/halo/transformer/core/MatchRuleContractMessages.java`
 * - `ui/public/generated/match-rule.schema.json`
 * - `ui/public/transformer.schema.json`
 *
 * Modes:
 * - default: 写回所有 generated artifacts
 * - `--check`: 只校验仓库里的 generated artifacts 是否与 spec 一致，不改文件
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");
const specPath = path.join(repoRoot, "specs", "match-rule", "contract.spec.jsonc");
const checkOnly = process.argv.includes("--check");

const spec = parseJsoncFile(specPath);

const artifacts = [
  {
    path: path.join(
      repoRoot,
      "ui",
      "src",
      "views",
      "composables",
      "matchRuleContract.generated.ts",
    ),
    content: buildTsArtifact(spec),
  },
  {
    path: path.join(
      repoRoot,
      "src",
      "main",
      "java",
      "top",
      "howiehz",
      "halo",
      "transformer",
      "core",
      "MatchRuleContractMessages.java",
    ),
    content: buildJavaArtifact(spec),
  },
  {
    path: path.join(repoRoot, "ui", "public", "generated", "match-rule.schema.json"),
    content: buildJsonArtifact(buildMatchRuleSchema(spec)),
  },
  {
    path: path.join(repoRoot, "ui", "public", "transformer.schema.json"),
    content: buildJsonArtifact(buildTransferEnvelopeSchema()),
  },
];

if (checkOnly) {
  verifyArtifacts(artifacts);
} else {
  writeArtifacts(artifacts);
}

function parseJsoncFile(filePath) {
  return JSON.parse(normalizeJsonc(readFileSync(filePath, "utf8")));
}

function normalizeJsonc(content) {
  return stripTrailingCommas(stripJsonComments(content));
}

function stripJsonComments(content) {
  let result = "";
  let inString = false;
  let isEscaped = false;
  let inLineComment = false;
  let inBlockComment = false;

  for (let index = 0; index < content.length; index += 1) {
    const char = content[index];
    const next = content[index + 1];

    if (inLineComment) {
      if (char === "\n") {
        inLineComment = false;
        result += char;
      }
      continue;
    }

    if (inBlockComment) {
      if (char === "*" && next === "/") {
        inBlockComment = false;
        index += 1;
      }
      continue;
    }

    if (inString) {
      result += char;
      if (isEscaped) {
        isEscaped = false;
      } else if (char === "\\") {
        isEscaped = true;
      } else if (char === '"') {
        inString = false;
      }
      continue;
    }

    if (char === "/" && next === "/") {
      inLineComment = true;
      index += 1;
      continue;
    }

    if (char === "/" && next === "*") {
      inBlockComment = true;
      index += 1;
      continue;
    }

    if (char === '"') {
      inString = true;
    }

    result += char;
  }

  return result;
}

function stripTrailingCommas(content) {
  let result = "";
  let inString = false;
  let isEscaped = false;

  for (let index = 0; index < content.length; index += 1) {
    const char = content[index];

    if (inString) {
      result += char;
      if (isEscaped) {
        isEscaped = false;
      } else if (char === "\\") {
        isEscaped = true;
      } else if (char === '"') {
        inString = false;
      }
      continue;
    }

    if (char === '"') {
      inString = true;
      result += char;
      continue;
    }

    if (char === ",") {
      let lookahead = index + 1;
      while (lookahead < content.length && /\s/.test(content[lookahead])) {
        lookahead += 1;
      }
      if (content[lookahead] === "}" || content[lookahead] === "]") {
        continue;
      }
    }

    result += char;
  }

  return result;
}

function verifyArtifacts(artifacts) {
  const staleArtifacts = artifacts
    .filter(
      (artifact) =>
        !existsSync(artifact.path) ||
        normalizeLineEndings(readFileSync(artifact.path, "utf8")) !==
          normalizeLineEndings(artifact.content),
    )
    .map((artifact) => path.relative(repoRoot, artifact.path));

  if (staleArtifacts.length === 0) {
    return;
  }

  console.error("Match-rule spec artifacts are out of date:");
  for (const artifactPath of staleArtifacts) {
    console.error(`- ${artifactPath}`);
  }
  console.error("Run `pnpm generate:spec-artifacts` from the repository root to refresh them.");
  process.exitCode = 1;
}

function writeArtifacts(artifacts) {
  for (const artifact of artifacts) {
    writeText(artifact.path, artifact.content);
  }
}

function buildJsonArtifact(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function writeText(targetPath, value) {
  mkdirSync(path.dirname(targetPath), { recursive: true });
  writeFileSync(targetPath, value, "utf8");
}

function normalizeLineEndings(value) {
  return value.replace(/\r\n/g, "\n");
}

function buildTsArtifact(data) {
  return `// Generated by scripts/sync-match-rule-spec-artifacts.js
// Source: specs/match-rule/contract.spec.jsonc

export const MATCH_RULE_NODE_SPECS = ${JSON.stringify(data.nodeTypes, null, 2)} as const

export const MATCH_RULE_ENUM_SPECS = ${JSON.stringify(data.enumValues, null, 2)} as const

export type MatchRuleContractNodeType = keyof typeof MATCH_RULE_NODE_SPECS
export type MatchRuleContractEnumName = keyof typeof MATCH_RULE_ENUM_SPECS

export function allowedFieldsFor(nodeType: MatchRuleContractNodeType) {
  return MATCH_RULE_NODE_SPECS[nodeType].allowedFields
}

export function enumValuesFor(enumName: MatchRuleContractEnumName) {
  return MATCH_RULE_ENUM_SPECS[enumName].values
}

export function formatAllowedFields(nodeType: MatchRuleContractNodeType) {
  return formatQuotedValues(allowedFieldsFor(nodeType))
}

export function formatQuotedValues(
  values: readonly string[],
  joinStyle: 'list' | 'or' = 'list',
) {
  const quoted = values.map((value) => (value === 'true' || value === 'false' ? value : \`"\${value}"\`))
  if (quoted.length <= 1) {
    return quoted[0] ?? ''
  }
  if (joinStyle === 'or') {
    if (quoted.length === 2) {
      return \`\${quoted[0]} 或 \${quoted[1]}\`
    }
    return \`\${quoted.slice(0, -1).join('、')} 或 \${quoted[quoted.length - 1]}\`
  }
  return quoted.join('、')
}

export function formatUnsupportedFieldMessage(nodeType: MatchRuleContractNodeType) {
  const spec = MATCH_RULE_NODE_SPECS[nodeType]
  return \`不支持该字段；\${spec.label}仅支持 \${formatAllowedFields(nodeType)}\`
}

export function formatMissingEnumFieldMessage(
  fieldName: string,
  enumName: MatchRuleContractEnumName,
  label = '',
) {
  const spec = MATCH_RULE_ENUM_SPECS[enumName]
  const prefix = label ? \`\${label}缺少必填字段\` : '缺少必填字段'
  return \`\${prefix} "\${fieldName}"；该字段可选值为 \${formatQuotedValues(spec.values, spec.joinStyle)}\`
}

export function formatInvalidEnumFieldTypeMessage(enumName: MatchRuleContractEnumName) {
  const spec = MATCH_RULE_ENUM_SPECS[enumName]
  return \`必须是字符串；仅支持 \${formatQuotedValues(spec.values, spec.joinStyle)}\`
}

export function formatInvalidEnumFieldValueMessage(
  enumName: MatchRuleContractEnumName,
  prefix = '仅支持',
) {
  const spec = MATCH_RULE_ENUM_SPECS[enumName]
  return \`\${prefix} \${formatQuotedValues(spec.values, spec.joinStyle)}\`
}

export function formatInvalidBooleanFieldMessage() {
  return \`必须是布尔值；仅支持 \${formatQuotedValues(enumValuesFor('BOOLEAN'), MATCH_RULE_ENUM_SPECS.BOOLEAN.joinStyle)}\`
}
`;
}

function buildJavaArtifact(data) {
  const nodeTypeCases = Object.entries(data.nodeTypes)
    .map(
      ([name, spec]) =>
        `            case ${name} ->
                new NodeTypeSpec("${spec.label}", List.of(${spec.allowedFields
                  .map((field) => `"${field}"`)
                  .join(", ")}));`,
    )
    .join("\n");

  const enumCases = Object.entries(data.enumValues)
    .map(
      ([name, enumSpec]) =>
        `            case ${name} -> new EnumSpec(List.of(${enumSpec.values.map((value) => `"${value}"`).join(", ")}), JoinStyle.${enumSpec.joinStyle.toUpperCase()});`,
    )
    .join("\n");

  return `package top.howiehz.halo.transformer.core;

import java.util.List;

// Generated by scripts/sync-match-rule-spec-artifacts.js
// Source: specs/match-rule/contract.spec.jsonc
public final class MatchRuleContractMessages {
    private MatchRuleContractMessages() {
    }

    public static String formatUnsupportedFieldMessage(MatchRule.Type nodeType) {
        NodeTypeSpec spec = nodeTypeSpec(nodeType);
        return "不支持该字段；" + spec.label() + "仅支持 " + formatQuotedValues(spec.allowedFields(),
            JoinStyle.LIST);
    }

    public static String formatMissingEnumFieldMessage(String fieldName, EnumName enumName,
        String label) {
        EnumSpec spec = enumSpec(enumName);
        String prefix = label == null || label.isBlank() ? "缺少必填字段" : label + "缺少必填字段";
        return prefix + " \\"" + fieldName + "\\"；该字段可选值为 "
            + formatQuotedValues(spec.values(), spec.joinStyle());
    }

    public static String formatInvalidEnumFieldTypeMessage(EnumName enumName) {
        EnumSpec spec = enumSpec(enumName);
        return "必须是字符串；仅支持 " + formatQuotedValues(spec.values(), spec.joinStyle());
    }

    public static String formatInvalidEnumFieldValueMessage(EnumName enumName) {
        EnumSpec spec = enumSpec(enumName);
        return "仅支持 " + formatQuotedValues(spec.values(), spec.joinStyle());
    }

    public static String formatInvalidBooleanFieldMessage() {
        EnumSpec spec = enumSpec(EnumName.BOOLEAN);
        return "必须是布尔值；仅支持 " + formatQuotedValues(spec.values(), spec.joinStyle());
    }

    public static List<String> allowedFieldsFor(MatchRule.Type nodeType) {
        return nodeTypeSpec(nodeType).allowedFields();
    }

    public static List<String> enumValuesFor(EnumName enumName) {
        return enumSpec(enumName).values();
    }

    private static NodeTypeSpec nodeTypeSpec(MatchRule.Type nodeType) {
        return switch (nodeType) {
${nodeTypeCases}
        };
    }

    private static EnumSpec enumSpec(EnumName enumName) {
        return switch (enumName) {
${enumCases}
        };
    }

    private static String formatQuotedValues(List<String> values, JoinStyle joinStyle) {
        List<String> quoted = values.stream()
            .map(value -> "true".equals(value) || "false".equals(value) ? value
                : "\\"" + value + "\\"")
            .toList();
        if (quoted.isEmpty()) {
            return "";
        }
        if (quoted.size() == 1) {
            return quoted.getFirst();
        }
        if (joinStyle == JoinStyle.OR) {
            if (quoted.size() == 2) {
                return quoted.get(0) + " 或 " + quoted.get(1);
            }
            return String.join("、", quoted.subList(0, quoted.size() - 1))
                + " 或 " + quoted.get(quoted.size() - 1);
        }
        return String.join("、", quoted);
    }

    public enum EnumName {
        TYPE,
        BOOLEAN,
        OPERATOR,
        PATH_MATCHER,
        TEMPLATE_MATCHER
    }

    private enum JoinStyle {
        LIST,
        OR
    }

    private record NodeTypeSpec(String label, List<String> allowedFields) {
    }

    private record EnumSpec(List<String> values, JoinStyle joinStyle) {
    }
}
`;
}

function buildMatchRuleSchema(data) {
  const nodeTypeNames = Object.keys(data.nodeTypes);
  const nodeRefs = nodeTypeNames.map((name) => ({
    $ref: `#/$defs/${schemaNodeDefinitionName(name)}`,
  }));

  const nodeDefinitions = Object.fromEntries(
    nodeTypeNames.map((name) => [schemaNodeDefinitionName(name), buildNodeSchema(name, data)]),
  );

  return {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    $id: "https://raw.githubusercontent.com/HowieHz/halo-plugin-transformer/main/ui/public/generated/match-rule.schema.json",
    title: "Halo Plugin Transformer Match Rule",
    description: "Generated schema for Halo Plugin Transformer match-rule trees and transfer sources.",
    $defs: {
      matchRuleSource: {
        oneOf: [
          {
            type: "object",
            required: ["kind", "data"],
            properties: {
              kind: { const: "RULE_TREE" },
              data: { $ref: "#/$defs/matchRuleGroup" },
            },
            additionalProperties: false,
          },
          {
            type: "object",
            required: ["kind", "data"],
            properties: {
              kind: { const: "JSON_DRAFT" },
              data: { type: "string" },
            },
            additionalProperties: false,
          },
        ],
      },
      matchRuleNode: {
        oneOf: nodeRefs,
      },
      ...nodeDefinitions,
    },
  };
}

function schemaNodeDefinitionName(nodeTypeName) {
  if (nodeTypeName === "GROUP") {
    return "matchRuleGroup";
  }
  return `matchRule${toPascalCase(nodeTypeName)}`;
}

function buildNodeSchema(nodeTypeName, data) {
  const nodeSpec = data.nodeTypes[nodeTypeName];
  const properties = {};

  for (const fieldName of nodeSpec.allowedFields) {
    properties[fieldName] = buildNodeFieldSchema(nodeTypeName, fieldName, data);
  }

  return {
    type: "object",
    required: nodeSpec.allowedFields,
    properties,
    additionalProperties: false,
  };
}

function buildNodeFieldSchema(nodeTypeName, fieldName, data) {
  if (fieldName === "type") {
    return { const: nodeTypeName };
  }
  if (fieldName === "negate") {
    return { type: "boolean" };
  }
  if (fieldName === "children") {
    return {
      type: "array",
      items: { $ref: "#/$defs/matchRuleNode" },
    };
  }
  if (fieldName === "operator") {
    return {
      type: "string",
      enum: data.enumValues.OPERATOR.values,
    };
  }
  if (fieldName === "matcher") {
    const enumName = nodeTypeName === "PATH" ? "PATH_MATCHER" : "TEMPLATE_MATCHER";
    return {
      type: "string",
      enum: data.enumValues[enumName].values,
    };
  }
  if (fieldName === "value") {
    return { type: "string" };
  }
  throw new Error(
    `Unsupported field in match-rule schema generation: ${nodeTypeName}.${fieldName}`,
  );
}

function toPascalCase(value) {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => `${part.slice(0, 1).toUpperCase()}${part.slice(1)}`)
    .join("");
}

function buildTransferEnvelopeSchema() {
  return {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    $id: "https://raw.githubusercontent.com/HowieHz/halo-plugin-transformer/main/ui/public/transformer.schema.json",
    title: "Halo Plugin Transformer Transfer",
    description: "JSON import/export schema for Halo Plugin Transformer resources.",
    type: "object",
    required: ["version", "resourceType", "data"],
    properties: {
      $schema: {
        type: "string",
      },
      version: {
        type: "integer",
      },
      resourceType: {
        type: "string",
        enum: ["snippet", "rule", "snippet-batch", "rule-batch"],
      },
      data: true,
    },
    additionalProperties: false,
    oneOf: [
      {
        title: "Snippet Transfer v1",
        properties: {
          version: {
            const: 1,
          },
          resourceType: {
            const: "snippet",
          },
          data: {
            $ref: "#/$defs/snippetDataV1",
          },
        },
      },
      {
        title: "Rule Transfer v1",
        properties: {
          version: {
            const: 1,
          },
          resourceType: {
            const: "rule",
          },
          data: {
            $ref: "#/$defs/ruleDataV1",
          },
        },
      },
      {
        title: "Snippet Batch Transfer v1",
        properties: {
          version: {
            const: 1,
          },
          resourceType: {
            const: "snippet-batch",
          },
          data: {
            $ref: "#/$defs/snippetBatchDataV1",
          },
        },
      },
      {
        title: "Rule Batch Transfer v1",
        properties: {
          version: {
            const: 1,
          },
          resourceType: {
            const: "rule-batch",
          },
          data: {
            $ref: "#/$defs/ruleBatchDataV1",
          },
        },
      },
    ],
    $defs: {
      snippetDataV1: {
        type: "object",
        properties: {
          enabled: {
            type: "boolean",
          },
          name: {
            type: "string",
          },
          description: {
            type: "string",
          },
          code: {
            type: "string",
          },
        },
        additionalProperties: false,
      },
      snippetBatchDataV1: {
        type: "object",
        required: ["items"],
        properties: {
          items: {
            type: "array",
            minItems: 1,
            items: {
              $ref: "#/$defs/snippetDataV1",
            },
          },
        },
        additionalProperties: false,
      },
      ruleDataV1: {
        type: "object",
        required: [
          "enabled",
          "name",
          "description",
          "mode",
          "match",
          "position",
          "wrapMarker",
          "matchRuleSource",
        ],
        properties: {
          enabled: {
            type: "boolean",
          },
          name: {
            type: "string",
          },
          description: {
            type: "string",
          },
          mode: {
            type: "string",
            enum: ["HEAD", "FOOTER", "SELECTOR"],
          },
          match: {
            type: "string",
          },
          position: {
            type: "string",
            enum: ["APPEND", "PREPEND", "BEFORE", "AFTER", "REPLACE", "REMOVE"],
          },
          wrapMarker: {
            type: "boolean",
          },
          runtimeOrder: {
            type: "integer",
            minimum: 0,
            maximum: 2147483647,
          },
          matchRuleSource: {
            $ref: "./generated/match-rule.schema.json#/$defs/matchRuleSource",
          },
        },
        additionalProperties: false,
      },
      ruleBatchDataV1: {
        type: "object",
        required: ["items"],
        properties: {
          items: {
            type: "array",
            minItems: 1,
            items: {
              $ref: "#/$defs/ruleDataV1",
            },
          },
        },
        additionalProperties: false,
      },
    },
  };
}
