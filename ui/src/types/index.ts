export interface ResourceWriteMetadata {
  name?: string;
  generateName?: string;
  version?: number | null;
}

export interface ResourceReadMetadata {
  name: string;
  version?: number | null;
}

/**
 * why: 写接口只应承载后端真正接受的持久化字段；
 * 像 `id` 这类前端展示态派生字段，不应混进写入模型。
 */
export interface TransformationSnippetWritePayload {
  apiVersion: "transformer.howiehz.top/v1alpha1";
  kind: "TransformationSnippet";
  metadata: ResourceWriteMetadata;
  name: string;
  code: string;
  description: string;
  enabled: boolean;
}

export interface TransformationSnippetReadModel {
  apiVersion: "transformer.howiehz.top/v1alpha1";
  kind: "TransformationSnippet";
  metadata: ResourceReadMetadata;
  id: string;
  name: string;
  code: string;
  description: string;
  enabled: boolean;
}

export interface TransformationSnippetEditorDraft extends TransformationSnippetWritePayload {
  id: string;
}

export type TransformationMode = "HEAD" | "FOOTER" | "SELECTOR";
export type TransformationPosition =
  | "APPEND"
  | "PREPEND"
  | "BEFORE"
  | "AFTER"
  | "REPLACE"
  | "REMOVE";
export type MatchRuleType = "GROUP" | "PATH" | "TEMPLATE_ID";
export type MatchRuleOperator = "AND" | "OR";
export type MatchRuleMatcher = "ANT" | "REGEX" | "EXACT";
export type MatchRuleEditorMode = "SIMPLE" | "JSON";
export type MatchRuleSourceKind = "RULE_TREE" | "JSON_DRAFT";

export interface MatchRule {
  type: MatchRuleType;
  negate: boolean;
  operator?: MatchRuleOperator;
  matcher?: MatchRuleMatcher;
  value?: string;
  children?: MatchRule[];
}

export interface MatchRuleSource {
  kind: MatchRuleSourceKind;
  data: MatchRule | string;
}

export interface TransformationRuleWritePayload {
  apiVersion: "transformer.howiehz.top/v1alpha1";
  kind: "TransformationRule";
  metadata: ResourceWriteMetadata;
  name: string;
  description: string;
  enabled: boolean;
  mode: TransformationMode;
  match: string;
  matchRule: MatchRule;
  position: TransformationPosition;
  wrapMarker: boolean;
  runtimeOrder: number;
  snippetIds: string[];
}

export interface TransformationRuleReadModel {
  apiVersion: "transformer.howiehz.top/v1alpha1";
  kind: "TransformationRule";
  metadata: ResourceReadMetadata;
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  mode: TransformationMode;
  match: string;
  matchRule: MatchRule;
  position: TransformationPosition;
  wrapMarker: boolean;
  runtimeOrder: number;
  snippetIds: string[];
}

export interface TransformationRuleEditorState {
  matchRuleSource?: MatchRuleSource;
}

export interface TransformationRuleEditorDraft
  extends TransformationRuleWritePayload, TransformationRuleEditorState {
  id: string;
}

export interface ItemList<T> {
  page: number;
  size: number;
  total: number;
  items: Array<T>;
  first: boolean;
  last: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
  totalPages: number;
}

export interface RuntimeOrderStep {
  value: number;
  label: string;
}

export type ActiveTab = "snippets" | "rules";

export const MODE_OPTIONS: { value: TransformationMode; label: string }[] = [
  { value: "HEAD", label: "<head>" },
  { value: "FOOTER", label: "<footer>" },
  { value: "SELECTOR", label: "CSS 选择器" },
];

export const POSITION_OPTIONS: { value: TransformationPosition; label: string }[] = [
  { value: "APPEND", label: "内部末尾 (append)" },
  { value: "PREPEND", label: "内部开头 (prepend)" },
  { value: "BEFORE", label: "元素之前 (before)" },
  { value: "AFTER", label: "元素之后 (after)" },
  { value: "REPLACE", label: "替换元素 (replace)" },
  { value: "REMOVE", label: "移除元素 (remove)" },
];

/**
 * why: 新建规则默认应落在最低优先级预设，而不是抢到既有规则前面；
 * 这样只“新建了一条规则”不会悄悄改变同阶段旧规则的执行先后。
 */
export const RUNTIME_ORDER_DEFAULT = 2147483645;
export const RUNTIME_ORDER_MAX = 2147483647;
export const RUNTIME_ORDER_STEPS: RuntimeOrderStep[] = [
  { value: 0, label: "最高" },
  { value: 429496729, label: "高" },
  { value: 858993458, label: "较高" },
  { value: 1288490187, label: "普通" },
  { value: 1717986916, label: "较低" },
  { value: RUNTIME_ORDER_DEFAULT, label: "最低" },
];

export const MATCH_RULE_GROUP_OPTIONS: { value: MatchRuleOperator; label: string }[] = [
  { value: "AND", label: "全部满足 (AND)" },
  { value: "OR", label: "任一满足 (OR)" },
];

export const PATH_MATCHER_OPTIONS: { value: MatchRuleMatcher; label: string }[] = [
  { value: "ANT", label: "Ant 风格" },
  { value: "REGEX", label: "正则表达式" },
  { value: "EXACT", label: "精确匹配" },
];

export const TEMPLATE_MATCHER_OPTIONS: { value: MatchRuleMatcher; label: string }[] = [
  { value: "EXACT", label: "精确匹配" },
  { value: "REGEX", label: "正则表达式" },
];

export function makePathMatchRule(override: Partial<MatchRule> = {}): MatchRule {
  return {
    type: "PATH",
    negate: false,
    matcher: "ANT",
    value: "/**",
    ...override,
  };
}

export function makeTemplateMatchRule(override: Partial<MatchRule> = {}): MatchRule {
  return {
    type: "TEMPLATE_ID",
    negate: false,
    matcher: "EXACT",
    value: "post",
    ...override,
  };
}

export function makeMatchRuleGroup(override: Partial<MatchRule> = {}): MatchRule {
  return {
    type: "GROUP",
    negate: false,
    operator: "AND",
    children: [makePathMatchRule()],
    ...override,
  };
}

/**
 * why: 新建与导入流程都应围绕编辑草稿工作，而不是把只读返回模型直接塞进表单。
 */
export function makeSnippetEditorDraft(
  override: Partial<TransformationSnippetEditorDraft> = {},
): TransformationSnippetEditorDraft {
  return {
    apiVersion: "transformer.howiehz.top/v1alpha1",
    kind: "TransformationSnippet",
    metadata: { name: "", generateName: "TransformationSnippet-" },
    id: "",
    name: "",
    code: "",
    description: "",
    enabled: true,
    ...override,
  };
}

/**
 * why: 规则编辑器需要一个自洽的草稿起点，包含规则树与 `matchRuleSource`；
 * 这样简单模式、JSON 模式、导入导出都能共享同一份编辑态模型。
 */
export function makeRuleEditorDraft(
  override: Partial<TransformationRuleEditorDraft> = {},
): TransformationRuleEditorDraft {
  const matchRule = makeMatchRuleGroup({
    children: [makePathMatchRule({ value: "" })],
  });
  return {
    apiVersion: "transformer.howiehz.top/v1alpha1",
    kind: "TransformationRule",
    metadata: { name: "", generateName: "TransformationRule-" },
    id: "",
    name: "",
    description: "",
    enabled: true,
    mode: "FOOTER",
    match: "",
    matchRule,
    position: "APPEND",
    wrapMarker: true,
    runtimeOrder: RUNTIME_ORDER_DEFAULT,
    snippetIds: [],
    matchRuleSource: {
      kind: "RULE_TREE",
      data: JSON.parse(JSON.stringify(matchRule)) as MatchRule,
    },
    ...override,
  };
}
