export {
  normalizeMatchRule,
  formatMatchRule,
  parseMatchRuleDraft,
  validateMatchRuleTree,
  validateSimpleMatchRuleTree,
  validateMatchRuleObject,
  formatMatchRuleError,
  type MatchRuleValidationError,
  type MatchRuleParseResult,
  type MatchRuleValidationSummary,
} from "./matchRuleValidation";
export {
  cloneMatchRule,
  cloneMatchRuleSource,
  makeRuleTreeSource,
  makeJsonDraftSource,
  buildMatchRuleEditorSourceForMode,
  resolveRuleMatchRule,
} from "./matchRuleSource";
export {
  isValidMatchRule,
  supportsDomPathPrecheck,
  getDomRulePerformanceWarning,
  matchRuleSummary,
} from "./matchRuleAnalysis";
