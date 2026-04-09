import {
  type MatchRule,
  makeMatchRuleGroup,
  makePathMatchRule,
  makeTemplateMatchRule,
} from "@/types";

import {
  allowedFieldsFor,
  formatInvalidBooleanFieldMessage,
  formatInvalidEnumFieldTypeMessage,
  formatInvalidEnumFieldValueMessage,
  formatMissingEnumFieldMessage,
  formatUnsupportedFieldMessage,
} from "./generated/matchRuleContract";

function isObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

export interface MatchRuleValidationError {
  path: string;
  message: string;
  line?: number;
  column?: number;
}

export interface MatchRuleParseResult {
  rule: MatchRule | null;
  error: MatchRuleValidationError | null;
}

export interface MatchRuleValidationSummary {
  errors: MatchRuleValidationError[];
  rule: MatchRule | null;
}

interface MatchRuleValidationOptions {
  requireGroupRoot: boolean;
  allowEmptyGroup: boolean;
  allowEmptyValue: boolean;
  allowInvalidRegex: boolean;
  allowIncompatibleMatcher: boolean;
  allowUnknownKeys: boolean;
  allowMissingRequiredKeys: boolean;
}

const GROUP_ALLOWED_KEYS = allowedFieldsFor("GROUP");
const PATH_ALLOWED_KEYS = allowedFieldsFor("PATH");
const TEMPLATE_ALLOWED_KEYS = allowedFieldsFor("TEMPLATE_ID");

/**
 * why: 只对“形状正确”的对象做归一化，保证简单模式、JSON 模式和后端入参围绕同一份稳定结构工作。
 * 同时保留编辑过程中的“空条件组”中间态，让用户先删空再继续补条件；是否允许保存，交给校验层决定。
 */
export function normalizeMatchRule(input: unknown): MatchRule {
  if (!isObject(input)) {
    return makeMatchRuleGroup();
  }

  const type = input.type;
  const negate = input.negate === true;
  const inputChildren = Array.isArray(input.children) ? input.children : null;

  if (type === "PATH") {
    return makePathMatchRule({
      negate,
      matcher: input.matcher === "REGEX" || input.matcher === "EXACT" ? input.matcher : "ANT",
      value: typeof input.value === "string" ? input.value : "/**",
    });
  }

  if (type === "TEMPLATE_ID") {
    return makeTemplateMatchRule({
      negate,
      matcher: input.matcher === "REGEX" ? "REGEX" : "EXACT",
      value: typeof input.value === "string" ? input.value : "post",
    });
  }

  const hasExplicitChildren = Array.isArray(inputChildren);
  const children = hasExplicitChildren
    ? inputChildren.map((child) => normalizeMatchRule(child))
    : [makePathMatchRule()];

  return makeMatchRuleGroup({
    negate,
    operator: input.operator === "OR" ? "OR" : "AND",
    children: hasExplicitChildren ? children : [makePathMatchRule()],
  });
}

export function formatMatchRule(rule: MatchRule): string {
  return JSON.stringify(normalizeMatchRule(rule), null, 2);
}

export function parseMatchRuleDraft(draft?: string | null): MatchRuleParseResult {
  if (!draft || !draft.trim()) {
    return {
      rule: null,
      error: {
        path: "$",
        message: "请输入匹配规则 JSON",
      },
    };
  }
  try {
    return validateMatchRuleInput(JSON.parse(draft), "$", {
      requireGroupRoot: true,
      allowEmptyGroup: false,
      allowEmptyValue: false,
      allowInvalidRegex: false,
      allowIncompatibleMatcher: false,
      allowUnknownKeys: false,
      allowMissingRequiredKeys: false,
    });
  } catch (error) {
    return {
      rule: null,
      error: buildJsonSyntaxError(draft, error),
    };
  }
}

export function validateMatchRuleTree(rule: MatchRule | null | undefined): MatchRuleParseResult {
  if (!rule) {
    return {
      rule: null,
      error: {
        path: "$",
        message: "请完善匹配规则",
      },
    };
  }
  return validateMatchRuleInput(JSON.parse(JSON.stringify(rule)) as unknown, "$", {
    requireGroupRoot: true,
    allowEmptyGroup: false,
    allowEmptyValue: false,
    allowInvalidRegex: false,
    allowIncompatibleMatcher: false,
    allowUnknownKeys: false,
    allowMissingRequiredKeys: false,
  });
}

/**
 * why: 简单模式需要把所有可定位到字段的错误同时标出来，
 * 用户才能一次看全空组、空值、非法正则等问题，而不是修完一个才看到下一个。
 */
export function validateSimpleMatchRuleTree(
  rule: MatchRule | null | undefined,
): MatchRuleValidationSummary {
  if (!rule) {
    return {
      rule: null,
      errors: [{ path: "$", message: "请完善匹配规则" }],
    };
  }
  const normalized = normalizeMatchRule(rule);
  const errors: MatchRuleValidationError[] = [];
  collectSimpleMatchRuleErrors(normalized, "$", errors);
  return {
    rule: normalized,
    errors,
  };
}

/**
 * why: 导入场景需要拦住会破坏编辑器结构的坏数据，
 * 同时对“写错字段名”与“漏填可补默认值的字段”做宽松归一化：
 * 错键直接丢弃，缺键补默认值；但像非法根节点类型这类会破坏结构的问题，仍然直接拒绝导入。
 */
export function validateMatchRuleObject(input: unknown, path = "matchRule"): MatchRuleParseResult {
  return validateMatchRuleInput(input, path, {
    requireGroupRoot: true,
    allowEmptyGroup: true,
    allowEmptyValue: true,
    allowInvalidRegex: true,
    allowIncompatibleMatcher: true,
    allowUnknownKeys: true,
    allowMissingRequiredKeys: true,
  });
}

export function formatMatchRuleError(error: MatchRuleValidationError | null): string {
  if (!error) return "";
  const location =
    typeof error.line === "number" && typeof error.column === "number"
      ? `第 ${error.line} 行，第 ${error.column} 列`
      : error.path;
  return `${location}：${error.message}`;
}

function validateMatchRuleInput(
  input: unknown,
  path: string,
  options: MatchRuleValidationOptions,
): MatchRuleParseResult {
  if (!isObject(input)) {
    return invalid(path, "必须是对象");
  }

  const type = input.type;
  if (!hasOwnKey(input, "type") && !options.allowMissingRequiredKeys) {
    return invalid(`${path}.type`, formatMissingEnumFieldMessage("type", "TYPE"));
  }
  if (input.type !== undefined && typeof input.type !== "string") {
    return invalid(`${path}.type`, formatInvalidEnumFieldTypeMessage("TYPE"));
  }
  if (type !== "GROUP" && type !== "PATH" && type !== "TEMPLATE_ID") {
    return invalid(`${path}.type`, formatInvalidEnumFieldValueMessage("TYPE"));
  }

  if (!hasOwnKey(input, "negate") && !options.allowMissingRequiredKeys) {
    return invalid(`${path}.negate`, formatMissingEnumFieldMessage("negate", "BOOLEAN"));
  }
  if (input.negate !== undefined && typeof input.negate !== "boolean") {
    return invalid(`${path}.negate`, formatInvalidBooleanFieldMessage());
  }

  if (options.requireGroupRoot && type !== "GROUP") {
    return invalid(`${path}.type`, "根节点必须是 GROUP");
  }

  if (type === "GROUP") {
    const unknownKey = findUnknownKey(input, GROUP_ALLOWED_KEYS);
    if (unknownKey && !options.allowUnknownKeys) {
      return invalid(`${path}.${unknownKey}`, formatUnsupportedFieldMessage("GROUP"));
    }
    if (!hasOwnKey(input, "operator") && !options.allowMissingRequiredKeys) {
      return invalid(
        `${path}.operator`,
        formatMissingEnumFieldMessage("operator", "OPERATOR", "条件组"),
      );
    }
    if (!hasOwnKey(input, "children") && !options.allowMissingRequiredKeys) {
      return invalid(`${path}.children`, '条件组缺少必填字段 "children"');
    }
    if (input.operator !== undefined && typeof input.operator !== "string") {
      return invalid(`${path}.operator`, formatInvalidEnumFieldTypeMessage("OPERATOR"));
    }
    if (input.operator !== undefined && input.operator !== "AND" && input.operator !== "OR") {
      return invalid(`${path}.operator`, formatInvalidEnumFieldValueMessage("OPERATOR"));
    }
    if (hasOwnKey(input, "children") && !Array.isArray(input.children)) {
      return invalid(`${path}.children`, "必须是数组");
    }
    const rawChildren = Array.isArray(input.children)
      ? input.children
      : options.allowMissingRequiredKeys
        ? []
        : [makePathMatchRule()];
    if (!rawChildren.length && !options.allowEmptyGroup) {
      return invalid(`${path}.children`, "不能有空组");
    }

    const children: MatchRule[] = [];
    for (let index = 0; index < rawChildren.length; index += 1) {
      const childResult = validateMatchRuleInput(rawChildren[index], `${path}.children[${index}]`, {
        ...options,
        requireGroupRoot: false,
      });
      if (childResult.error) {
        return childResult;
      }
      children.push(childResult.rule as MatchRule);
    }

    return {
      rule: makeMatchRuleGroup({
        negate: input.negate === true,
        operator: input.operator === "OR" ? "OR" : "AND",
        children,
      }),
      error: null,
    };
  }

  if (input.operator !== undefined) {
    return invalid(`${path}.operator`, formatUnsupportedFieldMessage(type));
  }
  if (input.children !== undefined) {
    return invalid(`${path}.children`, formatUnsupportedFieldMessage(type));
  }
  const allowedKeys = type === "PATH" ? PATH_ALLOWED_KEYS : TEMPLATE_ALLOWED_KEYS;
  const unknownKey = findUnknownKey(input, allowedKeys);
  if (unknownKey && !options.allowUnknownKeys) {
    return invalid(`${path}.${unknownKey}`, formatUnsupportedFieldMessage(type));
  }
  if (!hasOwnKey(input, "matcher") && !options.allowMissingRequiredKeys) {
    return invalid(
      `${path}.matcher`,
      type === "PATH"
        ? formatMissingEnumFieldMessage("matcher", "PATH_MATCHER", "页面路径条件")
        : formatMissingEnumFieldMessage("matcher", "TEMPLATE_MATCHER", "模板 ID 条件"),
    );
  }
  if (!hasOwnKey(input, "value") && !options.allowMissingRequiredKeys) {
    return invalid(
      `${path}.value`,
      type === "PATH" ? '页面路径条件缺少必填字段 "value"' : '模板 ID 条件缺少必填字段 "value"',
    );
  }

  if (hasOwnKey(input, "value") && typeof input.value !== "string") {
    return invalid(`${path}.value`, "必须是字符串");
  }
  if (typeof input.value === "string" && !input.value.trim() && !options.allowEmptyValue) {
    return invalid(`${path}.value`, "必须是非空字符串");
  }

  if (type === "PATH") {
    const normalizedValue = typeof input.value === "string" ? input.value.trim() : "/**";

    if (input.matcher !== undefined && typeof input.matcher !== "string") {
      return invalid(`${path}.matcher`, formatInvalidEnumFieldTypeMessage("PATH_MATCHER"));
    }
    if (
      input.matcher !== undefined &&
      input.matcher !== "ANT" &&
      input.matcher !== "REGEX" &&
      input.matcher !== "EXACT"
    ) {
      if (!options.allowIncompatibleMatcher) {
        return invalid(`${path}.matcher`, formatInvalidEnumFieldValueMessage("PATH_MATCHER"));
      }
    }
    if (input.matcher === "REGEX" && !options.allowInvalidRegex) {
      const regexError = validateRegexValue(normalizedValue, `${path}.value`);
      if (regexError) return regexError;
    }
    return {
      rule: makePathMatchRule({
        negate: input.negate === true,
        matcher: input.matcher === "REGEX" || input.matcher === "EXACT" ? input.matcher : "ANT",
        value: normalizedValue,
      }),
      error: null,
    };
  }

  const normalizedValue = typeof input.value === "string" ? input.value.trim() : "post";

  if (input.matcher !== undefined && typeof input.matcher !== "string") {
    return invalid(`${path}.matcher`, formatInvalidEnumFieldTypeMessage("TEMPLATE_MATCHER"));
  }
  if (input.matcher !== undefined && input.matcher !== "REGEX" && input.matcher !== "EXACT") {
    if (!options.allowIncompatibleMatcher) {
      return invalid(
        `${path}.matcher`,
        `模板 ID ${formatInvalidEnumFieldValueMessage("TEMPLATE_MATCHER")}`,
      );
    }
  }
  if (input.matcher === "REGEX" && !options.allowInvalidRegex) {
    const regexError = validateRegexValue(normalizedValue, `${path}.value`);
    if (regexError) return regexError;
  }

  return {
    rule: makeTemplateMatchRule({
      negate: input.negate === true,
      matcher: input.matcher === "REGEX" ? "REGEX" : "EXACT",
      value: normalizedValue,
    }),
    error: null,
  };
}

function invalid(path: string, message: string): MatchRuleParseResult {
  return {
    rule: null,
    error: { path, message },
  };
}

function hasOwnKey(input: Record<string, unknown>, key: string) {
  return Object.prototype.hasOwnProperty.call(input, key);
}

function findUnknownKey(
  input: Record<string, unknown>,
  allowedKeys: readonly string[],
): string | null {
  for (const key of Object.keys(input)) {
    if (!allowedKeys.includes(key)) {
      return key;
    }
  }
  return null;
}

function validateRegexValue(value: string, path: string): MatchRuleParseResult | null {
  try {
    // 前端提前编译一次，尽早把错误定位到具体字段，避免用户保存后才发现规则无法生效。
    new RegExp(value);
    return null;
  } catch (error) {
    const message = error instanceof Error ? error.message : "正则表达式无效";
    return invalid(path, `正则表达式无效：${message}`);
  }
}

function collectSimpleMatchRuleErrors(
  rule: MatchRule,
  path: string,
  errors: MatchRuleValidationError[],
) {
  if (rule.type === "GROUP") {
    const children = rule.children ?? [];
    if (!children.length) {
      errors.push({ path: `${path}.children`, message: "不能有空组" });
      return;
    }
    children.forEach((child, index) => {
      collectSimpleMatchRuleErrors(child, `${path}.children[${index}]`, errors);
    });
    return;
  }

  const value = rule.value?.trim() ?? "";
  if (!value) {
    errors.push({ path: `${path}.value`, message: "必须是非空字符串" });
    return;
  }

  if (rule.matcher === "REGEX") {
    const regexError = validateRegexValue(value, `${path}.value`);
    if (regexError?.error) {
      errors.push(regexError.error);
    }
  }
}

function buildJsonSyntaxError(draft: string, error: unknown): MatchRuleValidationError {
  const message = error instanceof Error ? error.message : "JSON 格式无效";
  const positionMatch = message.match(/position\s+(\d+)/i);
  if (!positionMatch) {
    return { path: "$", message };
  }

  const position = Number(positionMatch[1]);
  const prefix = draft.slice(0, position);
  const lines = prefix.split("\n");
  return {
    path: "$",
    message,
    line: lines.length,
    column: lines[lines.length - 1].length + 1,
  };
}
