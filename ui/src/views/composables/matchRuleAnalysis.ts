import type {
  TransformationRuleEditorDraft,
  TransformationRuleEditorState,
  MatchRule,
} from "@/types";

import { resolveRuleMatchRule } from "./matchRuleSource";

type AnalysisExpression =
  | { kind: "CONST"; value: boolean }
  | {
      kind: "LEAF";
      type: "PATH" | "TEMPLATE_ID";
      matcher: "ANT" | "REGEX" | "EXACT";
      value: string;
    }
  | { kind: "NOT"; child: AnalysisExpression }
  | { kind: "GROUP"; operator: "AND" | "OR"; children: AnalysisExpression[] };

type PathPrecheckKind = "PATH_SCOPED" | "TEMPLATE_ONLY" | "UNSUPPORTED";

function isGroupExpression(
  expression: AnalysisExpression,
): expression is Extract<AnalysisExpression, { kind: "GROUP" }> {
  return expression.kind === "GROUP";
}

function isNotExpression(
  expression: AnalysisExpression,
): expression is Extract<AnalysisExpression, { kind: "NOT" }> {
  return expression.kind === "NOT";
}

export function isValidMatchRule(rule: MatchRule | null): boolean {
  if (!rule) return false;
  if (rule.type === "GROUP") {
    return !!rule.children?.length && rule.children.every((child) => isValidMatchRule(child));
  }
  return !!rule.value?.trim();
}

export function supportsDomPathPrecheck(rule: MatchRule | null): boolean {
  return analyzePathPrecheckKind(minimizeMatchRuleForAnalysis(rule)) === "PATH_SCOPED";
}

/**
 * why: 性能提示也必须和保存/export 一样基于当前 editor source 解析结果，
 * 不能在 JSON_DRAFT 已经变坏时还继续读取旧的 `matchRule`，否则就会出现状态错位。
 */
export function getDomRulePerformanceWarning(
  rule: Pick<TransformationRuleEditorDraft, "mode" | "matchRule"> &
    Partial<TransformationRuleEditorState>,
): string | null {
  const result = resolveRuleMatchRule(rule);
  if (!result.rule || result.error) {
    return null;
  }
  if (rule.mode !== "SELECTOR" || supportsDomPathPrecheck(result.rule)) {
    return null;
  }
  return "⚠ 当前规则还不能先按页面路径缩小范围。建议在“全部满足（AND）”里先加入“页面路径匹配”，再按需叠加模板 ID 等条件。否则 CSS 选择器模式会先处理所有页面，再继续判断其它条件，因此会多一些处理开销。";
}

export function matchRuleSummary(rule: MatchRule): string {
  return formatAnalysisExpression(minimizeMatchRuleForAnalysis(rule), true);
}

/**
 * why: 这套布尔最小化只服务于分析期语义：性能提示、路径预筛能力判断和表达式展示。
 * 编辑器草稿仍保留用户原始结构，避免出现“界面里写的是 A，系统偷偷改成了 B”的心智错位。
 */
function minimizeMatchRuleForAnalysis(rule: MatchRule | null): AnalysisExpression {
  return simplifyAnalysisExpression(buildAnalysisExpression(rule));
}

function buildAnalysisExpression(rule: MatchRule | null): AnalysisExpression {
  if (!rule || !rule.type) {
    return { kind: "CONST", value: false };
  }

  const base: AnalysisExpression =
    rule.type === "GROUP"
      ? {
          kind: "GROUP",
          operator: rule.operator === "OR" ? "OR" : "AND",
          children: (rule.children ?? []).map((child) => buildAnalysisExpression(child)),
        }
      : {
          kind: "LEAF",
          type: rule.type,
          matcher:
            rule.type === "PATH"
              ? rule.matcher === "REGEX" || rule.matcher === "EXACT"
                ? rule.matcher
                : "ANT"
              : rule.matcher === "REGEX"
                ? "REGEX"
                : "EXACT",
          value: rule.value?.trim() ?? "",
        };

  return rule.negate ? { kind: "NOT", child: base } : base;
}

function simplifyAnalysisExpression(expression: AnalysisExpression): AnalysisExpression {
  switch (expression.kind) {
    case "CONST":
    case "LEAF":
      return expression;
    case "NOT":
      return simplifyNotExpression({
        kind: "NOT",
        child: simplifyAnalysisExpression(expression.child),
      });
    case "GROUP":
      return simplifyGroupExpression({
        kind: "GROUP",
        operator: expression.operator,
        children: expression.children.map((child) => simplifyAnalysisExpression(child)),
      });
  }
}

function simplifyNotExpression(
  expression: Extract<AnalysisExpression, { kind: "NOT" }>,
): AnalysisExpression {
  const child = expression.child;
  if (child.kind === "CONST") {
    return { kind: "CONST", value: !child.value };
  }
  if (child.kind === "NOT") {
    return simplifyAnalysisExpression(child.child);
  }
  return expression;
}

function simplifyGroupExpression(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression {
  let current: AnalysisExpression = expression;

  while (current.kind === "GROUP") {
    const currentGroup = current;
    const flattenedChildren = currentGroup.children.flatMap((child) =>
      isGroupExpression(child) && child.operator === currentGroup.operator
        ? child.children
        : [child],
    );
    const deduplicatedChildren = deduplicateExpressions(flattenedChildren);
    const next = simplifyGroupOnce({
      kind: "GROUP",
      operator: currentGroup.operator,
      children: sortExpressions(deduplicatedChildren),
    });

    if (analysisExpressionKey(next) === analysisExpressionKey(currentGroup)) {
      return next;
    }
    current = simplifyAnalysisExpression(next);
  }

  return current;
}

function simplifyGroupOnce(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression {
  const deMorganCandidate = buildReverseDeMorganCandidate(expression);
  if (shouldPreferCandidate(expression, deMorganCandidate)) {
    return deMorganCandidate;
  }

  const factorizedCandidate = buildFactorizedCandidate(expression);
  if (shouldPreferCandidate(expression, factorizedCandidate)) {
    return factorizedCandidate;
  }

  const absorbedCandidate = buildAbsorbedCandidate(expression);
  if (shouldPreferCandidate(expression, absorbedCandidate)) {
    return absorbedCandidate;
  }

  const complementedCandidate = foldComplementedExpression(expression);
  if (shouldPreferCandidate(expression, complementedCandidate)) {
    return complementedCandidate;
  }

  return foldConstantExpression(expression);
}

function buildReverseDeMorganCandidate(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression | null {
  if (expression.children.length < 2 || expression.children.some((child) => child.kind !== "NOT")) {
    return null;
  }

  return {
    kind: "NOT",
    child: {
      kind: "GROUP",
      operator: expression.operator === "AND" ? "OR" : "AND",
      children: expression.children.map(
        (child) => (child as Extract<AnalysisExpression, { kind: "NOT" }>).child,
      ),
    },
  };
}

function buildFactorizedCandidate(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression | null {
  if (expression.operator !== "OR" || expression.children.length < 2) {
    return null;
  }

  const factorizedTerms = expression.children.map((child) =>
    isGroupExpression(child) && child.operator === "AND" ? child.children : [child],
  );
  const commonFactorKeys = intersectExpressionKeys(factorizedTerms);
  if (commonFactorKeys.length === 0) {
    return null;
  }

  const commonFactors = factorizedTerms[0].filter((term) =>
    commonFactorKeys.includes(analysisExpressionKey(term)),
  );
  const residualTerms = factorizedTerms.map((termFactors) => {
    const residualFactors = termFactors.filter(
      (term) => !commonFactorKeys.includes(analysisExpressionKey(term)),
    );
    if (residualFactors.length === 0) {
      return { kind: "CONST", value: true } satisfies AnalysisExpression;
    }
    if (residualFactors.length === 1) {
      return residualFactors[0];
    }
    return {
      kind: "GROUP",
      operator: "AND",
      children: residualFactors,
    } satisfies AnalysisExpression;
  });

  return {
    kind: "GROUP",
    operator: "AND",
    children: [
      ...commonFactors,
      {
        kind: "GROUP",
        operator: "OR",
        children: residualTerms,
      },
    ],
  };
}

function buildAbsorbedCandidate(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression | null {
  if (expression.operator !== "AND") {
    return null;
  }

  const directChildKeys = new Set(
    expression.children
      .filter((child) => !isGroupExpression(child) || child.operator !== "OR")
      .map((child) => analysisExpressionKey(child)),
  );
  const filteredChildren = expression.children.filter((child) => {
    if (!isGroupExpression(child) || child.operator !== "OR") {
      return true;
    }
    return !child.children.some((option) => directChildKeys.has(analysisExpressionKey(option)));
  });

  if (filteredChildren.length === expression.children.length) {
    return null;
  }

  return {
    kind: "GROUP",
    operator: "AND",
    children: filteredChildren,
  };
}

function foldComplementedExpression(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression | null {
  const childKeys = new Set(expression.children.map((child) => analysisExpressionKey(child)));
  for (const child of expression.children) {
    if (!isNotExpression(child)) {
      continue;
    }
    if (childKeys.has(analysisExpressionKey(child.child))) {
      return {
        kind: "CONST",
        value: expression.operator === "OR",
      };
    }
  }
  return null;
}

function foldConstantExpression(
  expression: Extract<AnalysisExpression, { kind: "GROUP" }>,
): AnalysisExpression {
  if (expression.operator === "AND") {
    if (expression.children.some((child) => child.kind === "CONST" && child.value === false)) {
      return { kind: "CONST", value: false };
    }
    const nonTrueChildren = expression.children.filter(
      (child) => child.kind !== "CONST" || child.value !== true,
    );
    if (nonTrueChildren.length === 0) {
      return { kind: "CONST", value: true };
    }
    if (nonTrueChildren.length === 1) {
      return nonTrueChildren[0];
    }
    return {
      kind: "GROUP",
      operator: "AND",
      children: nonTrueChildren,
    };
  }

  if (expression.children.some((child) => child.kind === "CONST" && child.value === true)) {
    return { kind: "CONST", value: true };
  }
  const nonFalseChildren = expression.children.filter(
    (child) => child.kind !== "CONST" || child.value !== false,
  );
  if (nonFalseChildren.length === 0) {
    return { kind: "CONST", value: false };
  }
  if (nonFalseChildren.length === 1) {
    return nonFalseChildren[0];
  }
  return {
    kind: "GROUP",
    operator: "OR",
    children: nonFalseChildren,
  };
}

function deduplicateExpressions(expressions: AnalysisExpression[]) {
  const deduplicated = new Map<string, AnalysisExpression>();
  for (const expression of expressions) {
    deduplicated.set(analysisExpressionKey(expression), expression);
  }
  return [...deduplicated.values()];
}

function sortExpressions(expressions: AnalysisExpression[]) {
  return [...expressions].sort(compareAnalysisExpressions);
}

function intersectExpressionKeys(terms: AnalysisExpression[][]) {
  if (terms.length === 0) {
    return [];
  }

  let sharedKeys = new Set(terms[0].map((term) => analysisExpressionKey(term)));
  for (const termFactors of terms.slice(1)) {
    const currentKeys = new Set(termFactors.map((term) => analysisExpressionKey(term)));
    sharedKeys = new Set([...sharedKeys].filter((key) => currentKeys.has(key)));
  }
  return [...sharedKeys].sort();
}

function shouldPreferCandidate(
  current: AnalysisExpression,
  candidate: AnalysisExpression | null,
): candidate is AnalysisExpression {
  return (
    candidate !== null &&
    analysisExpressionComplexity(candidate) < analysisExpressionComplexity(current)
  );
}

function analysisExpressionComplexity(expression: AnalysisExpression): number {
  switch (expression.kind) {
    case "CONST":
      return 0;
    case "LEAF":
      return 1;
    case "NOT":
      return 1 + analysisExpressionComplexity(expression.child);
    case "GROUP":
      return (
        1 + expression.children.reduce((sum, child) => sum + analysisExpressionComplexity(child), 0)
      );
  }
}

function compareAnalysisExpressions(left: AnalysisExpression, right: AnalysisExpression): number {
  const rankDifference = analysisExpressionSortRank(left) - analysisExpressionSortRank(right);
  if (rankDifference !== 0) {
    return rankDifference;
  }
  return analysisExpressionKey(left).localeCompare(analysisExpressionKey(right));
}

function analysisExpressionSortRank(expression: AnalysisExpression): number {
  switch (expression.kind) {
    case "CONST":
      return 0;
    case "LEAF":
      return 1;
    case "NOT":
      return 2;
    case "GROUP":
      return 3;
  }
}

function analysisExpressionKey(expression: AnalysisExpression): string {
  switch (expression.kind) {
    case "CONST":
      return expression.value ? "TRUE" : "FALSE";
    case "LEAF":
      return `${expression.type}:${expression.matcher}:${expression.value}`;
    case "NOT":
      return `!${analysisExpressionKey(expression.child)}`;
    case "GROUP":
      return `${expression.operator}(${sortExpressions(expression.children)
        .map((child) => analysisExpressionKey(child))
        .join(",")})`;
  }
}

function formatAnalysisExpression(expression: AnalysisExpression, root = false): string {
  switch (expression.kind) {
    case "CONST":
      return expression.value ? "TRUE" : "FALSE";
    case "LEAF": {
      const subject = expression.type === "PATH" ? "path" : "id";
      const matcher =
        expression.matcher === "REGEX" ? "re" : expression.matcher === "EXACT" ? "=" : "ant";
      return `${subject}:${matcher}:${expression.value}`;
    }
    case "NOT": {
      const needsGrouping = isGroupExpression(expression.child);
      const child = needsGrouping
        ? formatAnalysisExpression(expression.child, true)
        : formatAnalysisExpression(expression.child);
      return needsGrouping ? `!(${child})` : `!${child}`;
    }
    case "GROUP": {
      const operator = expression.operator === "OR" ? " | " : " & ";
      const content = expression.children
        .map((child) => formatAnalysisExpression(child))
        .join(operator);
      return root ? content : `(${content})`;
    }
  }
}

/**
 * why: 前端与后端共用同一套“路径预筛能力”判定思路，
 * 用来识别 DOM 注入是否能先按页面路径缩小范围，并在配置页给出准确的性能提示。
 */
function analyzePathPrecheckKind(expression: AnalysisExpression): PathPrecheckKind {
  if (expression.kind === "CONST") {
    return "PATH_SCOPED";
  }
  if (expression.kind === "LEAF") {
    return expression.type === "PATH" ? "PATH_SCOPED" : "TEMPLATE_ONLY";
  }
  if (expression.kind === "NOT") {
    return containsTemplateExpression(expression.child) ? "UNSUPPORTED" : "PATH_SCOPED";
  }
  const children = expression.children;
  if (!children.length) return "PATH_SCOPED";
  if (expression.operator === "OR") {
    return analyzeOrPathPrecheckKind(children);
  }
  return analyzeAndPathPrecheckKind(children);
}

function analyzeAndPathPrecheckKind(children: AnalysisExpression[]): PathPrecheckKind {
  let hasPathScoped = false;
  for (const child of children) {
    const kind = analyzePathPrecheckKind(child);
    if (kind === "UNSUPPORTED") return "UNSUPPORTED";
    if (kind === "PATH_SCOPED") hasPathScoped = true;
  }
  return hasPathScoped ? "PATH_SCOPED" : "TEMPLATE_ONLY";
}

function analyzeOrPathPrecheckKind(children: AnalysisExpression[]): PathPrecheckKind {
  let hasPathScoped = false;
  let hasTemplateOnly = false;
  for (const child of children) {
    const kind = analyzePathPrecheckKind(child);
    if (kind === "UNSUPPORTED") return "UNSUPPORTED";
    if (kind === "PATH_SCOPED") hasPathScoped = true;
    if (kind === "TEMPLATE_ONLY") hasTemplateOnly = true;
  }
  if (hasPathScoped && hasTemplateOnly) return "UNSUPPORTED";
  if (hasPathScoped) return "PATH_SCOPED";
  return "TEMPLATE_ONLY";
}

function containsTemplateExpression(expression: AnalysisExpression): boolean {
  if (expression.kind === "LEAF") {
    return expression.type === "TEMPLATE_ID";
  }
  if (expression.kind === "NOT") {
    return containsTemplateExpression(expression.child);
  }
  if (expression.kind === "GROUP") {
    return expression.children.some((child) => containsTemplateExpression(child));
  }
  return false;
}
