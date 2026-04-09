import {
  makeRuleEditorDraft,
  makeSnippetEditorDraft,
  RUNTIME_ORDER_DEFAULT,
  RUNTIME_ORDER_MAX,
  type TransformationSnippetEditorDraft,
  type TransformationRuleEditorDraft,
  type MatchRuleSource,
} from "@/types";

import {
  makeJsonDraftSource,
  makeRuleTreeSource,
  normalizeMatchRule,
  parseMatchRuleDraft,
  validateMatchRuleObject,
} from "./matchRule";
import {
  ensureAllowedFields,
  isPlainObject,
  parseTransferEnvelope,
  validateEnumField,
} from "./transferEnvelope";
import type {
  RuleBatchTransferEnvelope,
  RuleTransferData,
  RuleTransferEnvelope,
  SnippetBatchTransferEnvelope,
  SnippetTransferData,
  SnippetTransferEnvelope,
} from "./transferExportBuilder";

export function parseSnippetTransfer(raw: string): TransformationSnippetEditorDraft {
  const envelope = parseTransferEnvelope<SnippetTransferEnvelope>(raw, "snippet");
  return parseSnippetTransferData(envelope.data);
}

export function parseSnippetBatchTransfer(raw: string): TransformationSnippetEditorDraft[] {
  const envelope = parseTransferEnvelope<SnippetBatchTransferEnvelope>(raw, "snippet-batch");
  return parseTransferItemList(envelope.data, "批量代码片段", parseSnippetTransferData);
}

export function parseRuleTransfer(raw: string): TransformationRuleEditorDraft {
  const envelope = parseTransferEnvelope<RuleTransferEnvelope>(raw, "rule");
  return parseRuleTransferData(envelope.data);
}

export function parseRuleBatchTransfer(raw: string): TransformationRuleEditorDraft[] {
  const envelope = parseTransferEnvelope<RuleBatchTransferEnvelope>(raw, "rule-batch");
  return parseTransferItemList(envelope.data, "批量转换规则", parseRuleTransferData);
}

function parseSnippetTransferData(data: SnippetTransferData): TransformationSnippetEditorDraft {
  ensureAllowedFields(data, ["enabled", "name", "description", "code"], "代码片段");
  if (data.enabled !== undefined && typeof data.enabled !== "boolean") {
    throw new Error("导入失败：`enabled` 必须是布尔值；仅支持 true 或 false");
  }
  if (data.name !== undefined && typeof data.name !== "string") {
    throw new Error("导入失败：`name` 必须是字符串");
  }
  if (data.description !== undefined && typeof data.description !== "string") {
    throw new Error("导入失败：`description` 必须是字符串");
  }
  if (data.code !== undefined && typeof data.code !== "string") {
    throw new Error("导入失败：`code` 必须是字符串");
  }
  return makeSnippetEditorDraft({
    enabled: typeof data.enabled === "boolean" ? data.enabled : true,
    name: typeof data.name === "string" ? data.name : "",
    description: typeof data.description === "string" ? data.description : "",
    code: typeof data.code === "string" ? data.code : "",
  });
}

function parseRuleTransferData(data: RuleTransferData): TransformationRuleEditorDraft {
  const baseline = makeRuleEditorDraft();
  ensureAllowedFields(
    data,
    [
      "enabled",
      "name",
      "description",
      "mode",
      "match",
      "position",
      "wrapMarker",
      "runtimeOrder",
      "matchRuleSource",
    ],
    "转换规则",
  );
  if (data.enabled !== undefined && typeof data.enabled !== "boolean") {
    throw new Error("导入失败：`enabled` 必须是布尔值；仅支持 true 或 false");
  }
  if (data.name !== undefined && typeof data.name !== "string") {
    throw new Error("导入失败：`name` 必须是字符串");
  }
  if (data.description !== undefined && typeof data.description !== "string") {
    throw new Error("导入失败：`description` 必须是字符串");
  }
  if (data.mode !== undefined) {
    validateEnumField("mode", data.mode, ["HEAD", "FOOTER", "SELECTOR"]);
  }
  if (data.match !== undefined && typeof data.match !== "string") {
    throw new Error("导入失败：`match` 必须是字符串");
  }
  if (data.position !== undefined) {
    validateEnumField("position", data.position, [
      "APPEND",
      "PREPEND",
      "BEFORE",
      "AFTER",
      "REPLACE",
      "REMOVE",
    ]);
  }
  if (data.wrapMarker !== undefined && typeof data.wrapMarker !== "boolean") {
    throw new Error("导入失败：`wrapMarker` 必须是布尔值；仅支持 true 或 false");
  }
  const runtimeOrder = data.runtimeOrder ?? baseline.runtimeOrder ?? RUNTIME_ORDER_DEFAULT;
  if (typeof runtimeOrder !== "number" || !Number.isInteger(runtimeOrder)) {
    throw new Error("导入失败：`runtimeOrder` 必须是整数");
  }
  if (runtimeOrder < 0) {
    throw new Error("导入失败：`runtimeOrder` 不能小于 0");
  }
  if (runtimeOrder > RUNTIME_ORDER_MAX) {
    throw new Error(`导入失败：\`runtimeOrder\` 不能大于 ${RUNTIME_ORDER_MAX}`);
  }
  const matchRuleState = data.matchRuleSource
    ? parseImportedMatchRuleSource(data.matchRuleSource)
    : {
        matchRule: baseline.matchRule,
        matchRuleSource: baseline.matchRuleSource ?? makeRuleTreeSource(baseline.matchRule),
      };
  return makeRuleEditorDraft({
    enabled: typeof data.enabled === "boolean" ? data.enabled : baseline.enabled,
    name: typeof data.name === "string" ? data.name : baseline.name,
    description: typeof data.description === "string" ? data.description : baseline.description,
    mode: data.mode ?? baseline.mode,
    match: typeof data.match === "string" ? data.match : baseline.match,
    matchRule: matchRuleState.matchRule,
    position: data.position ?? baseline.position,
    wrapMarker: typeof data.wrapMarker === "boolean" ? data.wrapMarker : baseline.wrapMarker,
    runtimeOrder,
    matchRuleSource: matchRuleState.matchRuleSource,
  });
}

function parseTransferItemList<TData, TResult>(
  data: unknown,
  resourceLabel: string,
  parseItem: (item: TData) => TResult,
): TResult[] {
  if (!isPlainObject(data)) {
    throw new Error(`导入失败：${resourceLabel}的 \`data\` 必须是对象`);
  }
  ensureAllowedFields(data, ["items"], resourceLabel);
  if (!Array.isArray(data.items)) {
    throw new Error(`导入失败：${resourceLabel}的 \`items\` 必须是数组`);
  }
  if (data.items.length === 0) {
    throw new Error(`导入失败：${resourceLabel}至少需要 1 项`);
  }
  return data.items.map((item, index) => {
    if (!isPlainObject(item)) {
      throw new Error(`导入失败：第 ${index + 1} 项必须是对象`);
    }
    try {
      return parseItem(item as TData);
    } catch (error) {
      const message = error instanceof Error ? error.message : "导入失败";
      throw new Error(`导入失败：第 ${index + 1} 项：${stripImportFailurePrefix(message)}`);
    }
  });
}

function stripImportFailurePrefix(message: string) {
  return message.replace(/^导入失败：/, "");
}

function parseImportedMatchRuleSource(source: unknown): {
  matchRule: TransformationRuleEditorDraft["matchRule"];
  matchRuleSource: MatchRuleSource;
} {
  if (!isPlainObject(source)) {
    throw new Error("导入失败：`matchRuleSource` 必须是对象");
  }

  ensureAllowedFields(source, ["kind", "data"], "匹配规则来源");
  validateEnumField("matchRuleSource.kind", source.kind, ["RULE_TREE", "JSON_DRAFT"]);

  if (!Object.prototype.hasOwnProperty.call(source, "data")) {
    throw new Error("导入失败：`matchRuleSource.data` 缺少必填字段");
  }

  if (source.kind === "JSON_DRAFT") {
    if (typeof source.data !== "string") {
      throw new Error("导入失败：`matchRuleSource.data` 必须是字符串");
    }
    const parsed = parseMatchRuleDraft(source.data);
    return {
      matchRule:
        parsed.rule ??
        normalizeMatchRule({ type: "GROUP", negate: false, operator: "AND", children: [] }),
      matchRuleSource: makeJsonDraftSource(source.data),
    };
  }

  if (!isPlainObject(source.data)) {
    throw new Error("导入失败：`matchRuleSource.data` 必须是对象");
  }

  const matchRuleResult = validateMatchRuleObject(source.data, "data.matchRuleSource.data");
  if (matchRuleResult.error) {
    return {
      matchRule: normalizeMatchRule(source.data),
      matchRuleSource: makeJsonDraftSource(JSON.stringify(source.data, null, 2)),
    };
  }

  const matchRule = matchRuleResult.rule ?? normalizeMatchRule(source.data);
  return {
    matchRule,
    matchRuleSource: makeRuleTreeSource(matchRule),
  };
}
