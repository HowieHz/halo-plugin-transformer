import { computed, ref, watch, type ComputedRef, type Ref } from "vue";

import type {
  ActiveTab,
  TransformationSnippetEditorDraft,
  TransformationSnippetReadModel,
  TransformationRuleEditorDraft,
  TransformationRuleReadModel,
} from "@/types";

import { mergeSavedMetadata } from "./resourceSupport";
import { getRuleCapabilities } from "./ruleCapabilities";
import { hydrateRuleEditorDraft } from "./ruleDraft";
import { hydrateSnippetEditorDraft } from "./snippetDraft";

interface UseEditorSelectionStateOptions {
  activeTab: Ref<ActiveTab>;
  snippets: ComputedRef<TransformationSnippetReadModel[]>;
  rules: ComputedRef<TransformationRuleReadModel[]>;
}

interface SnippetEditorSession {
  tab: "snippets";
  draft: TransformationSnippetEditorDraft | null;
  dirty: boolean;
}

interface RuleEditorSession {
  tab: "rules";
  draft: TransformationRuleEditorDraft | null;
  dirty: boolean;
}

type EditorSession = SnippetEditorSession | RuleEditorSession;

/**
 * why: 选中态、草稿 hydration 与“只同步已保存快照的一小部分字段”属于编辑器上下文；
 * 把它从总控模块里拆出来后，CRUD 与排序逻辑都不必再关心右侧面板如何维护草稿。
 */
export function useEditorSelectionState(options: UseEditorSelectionStateOptions) {
  const rememberedSelectionByTab = ref<Record<ActiveTab, string | null>>({
    snippets: null,
    rules: null,
  });
  const editorSession = ref<EditorSession>(createSnippetEditorSession());

  const selectedSnippetId = computed({
    get: () => rememberedSelectionByTab.value.snippets,
    set: (selectedId: string | null) => {
      rememberedSelectionByTab.value = {
        ...rememberedSelectionByTab.value,
        snippets: selectedId,
      };
      if (options.activeTab.value === "snippets") {
        hydrateSelectedSnippetDraft();
      }
    },
  });

  const selectedRuleId = computed({
    get: () => rememberedSelectionByTab.value.rules,
    set: (selectedId: string | null) => {
      rememberedSelectionByTab.value = {
        ...rememberedSelectionByTab.value,
        rules: selectedId,
      };
      if (options.activeTab.value === "rules") {
        hydrateSelectedRuleDraft();
      }
    },
  });

  const editSnippet = computed({
    get: () => (editorSession.value.tab === "snippets" ? editorSession.value.draft : null),
    set: (draft: TransformationSnippetEditorDraft | null) => {
      if (editorSession.value.tab !== "snippets") {
        return;
      }
      editorSession.value = {
        ...editorSession.value,
        draft,
      };
    },
  });

  const editRule = computed({
    get: () => (editorSession.value.tab === "rules" ? editorSession.value.draft : null),
    set: (draft: TransformationRuleEditorDraft | null) => {
      if (editorSession.value.tab !== "rules") {
        return;
      }
      editorSession.value = {
        ...editorSession.value,
        draft,
      };
    },
  });

  const editDirty = computed({
    get: () => editorSession.value.dirty,
    set: (dirty: boolean) => {
      editorSession.value = {
        ...editorSession.value,
        dirty,
      };
    },
  });

  const rulesUsingSnippet = computed(() => {
    if (!selectedSnippetId.value) return [];
    return options.rules.value.filter((rule) =>
      resolveRuleRelationSnippetIds(rule).includes(selectedSnippetId.value!),
    );
  });

  const snippetsInRule = computed(() => {
    if (!selectedRuleId.value) return [];
    const snippetIds = resolveRuleRelationSnippetIds(
      editorSession.value.tab === "rules" && editorSession.value.draft?.id === selectedRuleId.value
        ? editorSession.value.draft
        : (options.rules.value.find((item) => item.id === selectedRuleId.value) ?? null),
    );
    if (!snippetIds.length) return [];
    return snippetIds
      .map((id) => options.snippets.value.find((snippet) => snippet.id === id))
      .filter((snippet): snippet is TransformationSnippetReadModel => !!snippet);
  });

  function filterExistingSnippetIds(snippetIds: string[]) {
    const availableSnippetIds = new Set(options.snippets.value.map((snippet) => snippet.id));
    return snippetIds.filter((snippetId, index) => {
      return availableSnippetIds.has(snippetId) && snippetIds.indexOf(snippetId) === index;
    });
  }

  function hydrateSelectedSnippetDraft() {
    if (options.activeTab.value !== "snippets") {
      return;
    }
    const found = options.snippets.value.find((snippet) => snippet.id === selectedSnippetId.value);
    editorSession.value = createSnippetEditorSession(
      found ? hydrateSnippetEditorDraft(found) : null,
    );
  }

  function hydrateSelectedRuleDraft() {
    if (options.activeTab.value !== "rules") {
      return;
    }
    const found = options.rules.value.find((rule) => rule.id === selectedRuleId.value);
    const snippetIds = found ? filterExistingSnippetIds(found.snippetIds ?? []) : [];
    const draft = found ? hydrateRuleEditorDraft(found) : null;
    if (draft) {
      draft.snippetIds = [...snippetIds];
    }
    editorSession.value = createRuleEditorSession(draft);
  }

  /**
   * why: snippet 可能被别处删除，而 rule 清理是异步最终一致；
   * 编辑器不应继续把已不存在的 snippet id 当成“已选”，否则 UI 计数和后续保存 payload 都会漂移。
   */
  function reconcileRuleEditorSnippetIds() {
    if (editorSession.value.tab !== "rules") {
      return;
    }
    if (!editorSession.value.draft) {
      return;
    }
    const nextSnippetIds = filterExistingSnippetIds(editorSession.value.draft.snippetIds);
    if (nextSnippetIds.length === editorSession.value.draft.snippetIds.length) {
      return;
    }
    editorSession.value = {
      ...editorSession.value,
      draft: {
        ...editorSession.value.draft,
        snippetIds: [...nextSnippetIds],
      },
    };
  }

  /**
   * why: 启停接口现在只返回最新已保存资源；
   * 编辑器这里只同步当前草稿里真正受影响的字段，已保存列表快照由 snapshot state 负责替换。
   */
  function syncSavedSnippetDraft(snippet: TransformationSnippetReadModel) {
    if (editorSession.value.tab === "snippets" && editorSession.value.draft?.id === snippet.id) {
      editorSession.value.draft.enabled = snippet.enabled;
      editorSession.value.draft.metadata = mergeSavedMetadata(
        editorSession.value.draft.metadata,
        snippet.metadata,
      );
    }
  }

  /**
   * why: 规则启停只作用于已保存资源，但“当前草稿是否已经脏了”会决定同步策略：
   * 若草稿仍是干净的已保存视图，就应直接收敛到后端返回的规范形态；否则只同步
   * enabled/version，避免把未保存编辑误当成一次完整保存。
   */
  function syncSavedRuleDraft(rule: TransformationRuleReadModel) {
    if (editorSession.value.tab === "rules" && editorSession.value.draft?.id === rule.id) {
      if (!editorSession.value.dirty) {
        editorSession.value = createRuleEditorSession(hydrateRuleEditorDraft(rule));
        return;
      }
      editorSession.value.draft.enabled = rule.enabled;
      editorSession.value.draft.metadata = mergeSavedMetadata(
        editorSession.value.draft.metadata,
        rule.metadata,
      );
    }
  }

  function discardSnippetEdit() {
    hydrateSelectedSnippetDraft();
  }

  function discardRuleEdit() {
    hydrateSelectedRuleDraft();
  }

  watch(options.activeTab, (activeTab) => {
    if (activeTab === "snippets") {
      hydrateSelectedSnippetDraft();
      return;
    }
    hydrateSelectedRuleDraft();
  });
  watch(options.snippets, reconcileRuleEditorSnippetIds);

  return {
    selectedSnippetId,
    selectedRuleId,
    editSnippet,
    editRule,
    editDirty,
    rulesUsingSnippet,
    snippetsInRule,
    hydrateSelectedSnippetDraft,
    hydrateSelectedRuleDraft,
    syncSavedSnippetDraft,
    syncSavedRuleDraft,
    discardSnippetEdit,
    discardRuleEdit,
  };
}

function createSnippetEditorSession(
  draft: TransformationSnippetEditorDraft | null = null,
): SnippetEditorSession {
  return {
    tab: "snippets",
    draft,
    dirty: false,
  };
}

function createRuleEditorSession(
  draft: TransformationRuleEditorDraft | null = null,
): RuleEditorSession {
  return {
    tab: "rules",
    draft,
    dirty: false,
  };
}

function resolveRuleRelationSnippetIds(
  rule:
    | Pick<TransformationRuleEditorDraft, "mode" | "position" | "snippetIds">
    | Pick<TransformationRuleReadModel, "mode" | "position" | "snippetIds">
    | null,
) {
  if (!rule) {
    return [];
  }
  return getRuleCapabilities(rule).showsSnippetPicker ? (rule.snippetIds ?? []) : [];
}
