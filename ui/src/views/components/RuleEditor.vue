<script lang="ts" setup>
import { VButton } from "@halo-dev/components";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from "vue";

import type {
  TransformationSnippetReadModel,
  TransformationRuleEditorDraft,
  MatchRuleSource,
} from "@/types";
import { MODE_OPTIONS, POSITION_OPTIONS } from "@/types";
import {
  resolveEditorEmptyStateMessage,
  type EditorEmptyStateLayout,
} from "@/views/composables/editorEmptyState.ts";
import {
  cloneMatchRule,
  cloneMatchRuleSource,
  makeRuleTreeSource,
} from "@/views/composables/matchRule";
import {
  buildRuleUndoBaselineSnapshot,
  resolveRuleUndoFieldCurrentValue,
  type UndoableRuleField,
} from "@/views/composables/ruleEditorUndo.ts";
import { updateSelectByWheel } from "@/views/composables/selectWheel.ts";
import {
  buildRuleTransfer,
  createTransferFileDraft,
  type TransferFileDraft,
} from "@/views/composables/transfer.ts";
import { useDragAutoScroll } from "@/views/composables/useDragAutoScroll";
import { useFieldUndo } from "@/views/composables/useFieldUndo.ts";
import { useRuleFormSemantics } from "@/views/composables/useRuleFormSemantics";
import { sortSelectedFirst } from "@/views/composables/util.ts";

import DragAutoScrollOverlay from "./DragAutoScrollOverlay.vue";
import EditorFooter from "./EditorFooter.vue";
import EditorToolbar from "./EditorToolbar.vue";
import ExportContentModal from "./ExportContentModal.vue";
import FieldUndoButton from "./FieldUndoButton.vue";
import FormField from "./FormField.vue";
import ItemPicker from "./ItemPicker.vue";
import MatchRuleEditor from "./MatchRuleEditor.vue";
import RuleRuntimeOrderField from "./RuleRuntimeOrderField.vue";

const props = defineProps<{
  rule: TransformationRuleEditorDraft | null;
  snippets: TransformationSnippetReadModel[];
  saving: boolean;
  dirty: boolean;
  emptyStateLayout?: EditorEmptyStateLayout;
}>();

const emit = defineEmits<{
  (e: "save"): void;
  (e: "delete"): void;
  (e: "toggle-enabled"): void;
  (e: "toggle-bulk-mode"): void;
  (e: "field-change"): void;
  (e: "update:rule", rule: TransformationRuleEditorDraft): void;
}>();

const currentRule = computed(() => props.rule);
const sortedSnippets = computed(() =>
  sortSelectedFirst(props.snippets, currentRule.value?.snippetIds ?? []),
);
const exportFallback = ref<TransferFileDraft | null>(null);
const editorId = useId();
const matchFieldErrorId = `rule-editor-match-error-${editorId}`;
const editorScrollContainer = ref<HTMLElement | null>(null);
const editorToolbarShell = ref<HTMLElement | null>(null);
const editorFooterShell = ref<HTMLElement | null>(null);
const matchRuleEditorRef = ref<{
  commitPendingDrop: () => void;
} | null>(null);
const RULE_EDITOR_EDGE_OVERLAP_PX = 8;
const dragOverlayTopHeight = ref(48 + RULE_EDITOR_EDGE_OVERLAP_PX);
const dragOverlayBottomHeight = ref(52 + RULE_EDITOR_EDGE_OVERLAP_PX);
const autoScroll = useDragAutoScroll(editorScrollContainer);
let dragOverlayResizeObserver: ResizeObserver | null = null;

const matchDraft = ref("");
const matchInitialValue = ref("");
const {
  buildToggledSnippetIds,
  emptySnippetAssociationWarning,
  matchFieldError,
  needsSnippets,
  needsTarget,
  needsWrapMarker,
  performanceWarning,
} = useRuleFormSemantics({
  rule: currentRule,
  matchValue: matchDraft,
});
const undo = useFieldUndo();
const textFieldInitialValue = ref<Record<"name" | "description", string>>({
  name: "",
  description: "",
});
const emptyStateMessage = computed(() =>
  resolveEditorEmptyStateMessage({
    layout: props.emptyStateLayout ?? "split-pane",
    resourceLabel: "规则",
  }),
);

function updateDragOverlayHeights() {
  dragOverlayTopHeight.value =
    (editorToolbarShell.value?.offsetHeight ?? 48) + RULE_EDITOR_EDGE_OVERLAP_PX;
  dragOverlayBottomHeight.value =
    (editorFooterShell.value?.offsetHeight ?? 52) + RULE_EDITOR_EDGE_OVERLAP_PX;
}

function handleEditorContainerDragOver(event: DragEvent) {
  autoScroll.handleContainerDragOver(event, {
    topZoneHeight: dragOverlayTopHeight.value,
    bottomZoneHeight: dragOverlayBottomHeight.value,
  });
}

function handleEditorContainerDragLeave(event: DragEvent) {
  autoScroll.handleContainerDragLeave(event);
}

function handleEditorContainerDropCapture() {
  matchRuleEditorRef.value?.commitPendingDrop();
}

function observeDragOverlayShell() {
  dragOverlayResizeObserver?.disconnect();
  if (typeof ResizeObserver === "undefined") {
    updateDragOverlayHeights();
    return;
  }

  dragOverlayResizeObserver = new ResizeObserver(() => {
    updateDragOverlayHeights();
  });

  if (editorToolbarShell.value) {
    dragOverlayResizeObserver.observe(editorToolbarShell.value);
  }
  if (editorFooterShell.value) {
    dragOverlayResizeObserver.observe(editorFooterShell.value);
  }

  updateDragOverlayHeights();
}

watch(
  () => currentRule.value?.id ?? null,
  async () => {
    await nextTick();
    observeDragOverlayShell();
  },
  { immediate: true },
);

watch(
  () => currentRule.value?.match,
  (value) => {
    matchDraft.value = value ?? "";
  },
  { immediate: true },
);

watch(
  () => [currentRule.value?.id, props.dirty],
  () => {
    if (!currentRule.value || props.dirty) {
      return;
    }
    undo.resetBaseline(buildRuleUndoBaselineSnapshot(currentRule.value));
  },
  { immediate: true },
);

/**
 * why: 正常编辑需要记录撤销历史，但撤销/重置本身不能再次入栈，
 * 否则会把“当前值”重新压回历史，导致连续撤销时来回跳动。
 */
function updateField<K extends keyof TransformationRuleEditorDraft>(
  key: K,
  value: TransformationRuleEditorDraft[K],
  options?: { trackHistory?: boolean },
) {
  if (!currentRule.value) return;
  const trackHistory = options?.trackHistory ?? true;
  const next = { ...currentRule.value, [key]: value };
  if (trackHistory && key !== "matchRule") {
    undo.trackChange(String(key), currentRule.value[key], next[key]);
  }
  emit("update:rule", next);
  emit("field-change");
}

function updateRuleSnapshot(next: TransformationRuleEditorDraft) {
  emit("update:rule", next);
  emit("field-change");
}

function currentMatchRuleSnapshot() {
  return {
    matchRule: currentRule.value ? cloneMatchRule(currentRule.value.matchRule) : undefined,
    matchRuleSource: currentRule.value ? cloneCurrentMatchRuleSource() : undefined,
  };
}

function cloneCurrentMatchRuleSource() {
  if (!currentRule.value) {
    return makeRuleTreeSource({ type: "GROUP", negate: false, operator: "AND", children: [] });
  }
  return cloneMatchRuleSource(
    currentRule.value.matchRuleSource ?? makeRuleTreeSource(currentRule.value.matchRule),
  );
}

function updateMatchRuleField(patch: Partial<TransformationRuleEditorDraft>) {
  if (!currentRule.value) return;
  const previous = currentMatchRuleSnapshot();
  const next = {
    ...currentRule.value,
    ...patch,
  };
  const after = {
    matchRule: cloneMatchRule(next.matchRule),
    matchRuleSource: cloneMatchRuleSource(
      next.matchRuleSource ?? makeRuleTreeSource(next.matchRule),
    ),
  };
  undo.trackChange("matchRule", previous, after);
  updateRuleSnapshot(next);
}

function handleToggleSnippet(snippetId: string) {
  if (!currentRule.value) {
    return;
  }
  const previous = currentRule.value.snippetIds;
  const next = buildToggledSnippetIds(snippetId);
  undo.trackChange("snippetIds", previous, next);
  updateField("snippetIds", next as TransformationRuleEditorDraft["snippetIds"], {
    trackHistory: false,
  });
}

function beginMatchEdit() {
  matchInitialValue.value = currentRule.value?.match ?? matchDraft.value;
}

function handleMatchInput(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  matchDraft.value = value;
  updateField("match", value as TransformationRuleEditorDraft["match"], { trackHistory: false });
}

function commitMatchDraft() {
  if (matchInitialValue.value === matchDraft.value) {
    return;
  }
  undo.trackChange("match", matchInitialValue.value, matchDraft.value);
}

function beginTextFieldEdit(field: "name" | "description") {
  textFieldInitialValue.value = {
    ...textFieldInitialValue.value,
    [field]: currentRule.value?.[field] ?? "",
  };
}

function handleTextFieldInput(field: "name" | "description", value: string) {
  updateField(field, value as TransformationRuleEditorDraft[typeof field], { trackHistory: false });
}

function commitTextFieldDraft(field: "name" | "description") {
  const currentValue = currentRule.value?.[field] ?? "";
  if (textFieldInitialValue.value[field] === currentValue) {
    return;
  }
  undo.trackChange(field, textFieldInitialValue.value[field], currentValue);
}

function canUndo(field: UndoableRuleField) {
  if (!currentRule.value) return false;
  return undo.isModified(field, resolveRuleUndoFieldCurrentValue(field, currentRule.value));
}

function undoField(field: UndoableRuleField) {
  if (!currentRule.value) return;
  const previous = undo.undo(field, resolveRuleUndoFieldCurrentValue(field, currentRule.value));
  if (previous === undefined) return;
  applyUndoFieldState(field, previous);
}

function applyUndoFieldState(field: UndoableRuleField, value: unknown) {
  if (!currentRule.value) return;

  if (field === "matchRule") {
    const snapshot = value as {
      matchRule: TransformationRuleEditorDraft["matchRule"];
      matchRuleSource?: MatchRuleSource;
    };
    const next = {
      ...currentRule.value,
      matchRule: snapshot.matchRule,
      matchRuleSource: snapshot.matchRuleSource ?? makeRuleTreeSource(snapshot.matchRule),
    };
    updateRuleSnapshot(next);
    return;
  }

  if (field === "snippetIds") {
    updateField("snippetIds", value as TransformationRuleEditorDraft["snippetIds"], {
      trackHistory: false,
    });
    return;
  }

  updateField(field, value as TransformationRuleEditorDraft[typeof field], { trackHistory: false });
}
function resetField(field: UndoableRuleField) {
  if (!currentRule.value) return;
  const baseline = undo.reset(field);
  if (baseline === undefined) return;

  if (field === "matchRule") {
    const snapshot = baseline as {
      matchRule: TransformationRuleEditorDraft["matchRule"];
      matchRuleSource?: MatchRuleSource;
    };
    const next = {
      ...currentRule.value,
      matchRule: snapshot.matchRule,
      matchRuleSource: snapshot.matchRuleSource ?? makeRuleTreeSource(snapshot.matchRule),
    };
    updateRuleSnapshot(next);
    return;
  }

  if (field === "snippetIds") {
    updateField("snippetIds", baseline as TransformationRuleEditorDraft["snippetIds"], {
      trackHistory: false,
    });
    return;
  }

  updateField(field, baseline as TransformationRuleEditorDraft[typeof field], {
    trackHistory: false,
  });
}

async function exportRule() {
  if (!currentRule.value) {
    return;
  }
  exportFallback.value = createTransferFileDraft(
    buildRuleTransfer(currentRule.value),
    currentRule.value.name || currentRule.value.id || "transformation-rule",
  );
}

onMounted(async () => {
  await nextTick();
  observeDragOverlayShell();
});

onBeforeUnmount(() => {
  dragOverlayResizeObserver?.disconnect();
});
</script>

<template>
  <div
    class=":uno: transformer-editor-container relative flex h-full flex-col"
    @dragover.capture="handleEditorContainerDragOver"
    @dragleave.capture="handleEditorContainerDragLeave"
    @drop.capture="handleEditorContainerDropCapture"
  >
    <ExportContentModal
      v-if="exportFallback"
      :content="exportFallback.content"
      :file-name="exportFallback.fileName"
      @close="exportFallback = null"
    />

    <div ref="editorToolbarShell">
      <EditorToolbar
        :enabled="currentRule?.enabled"
        :id-text="currentRule?.id"
        :show-export="!!currentRule"
        :show-actions="!!currentRule"
        :show-default-actions="true"
        :title="currentRule ? '编辑规则' : '转换规则'"
        @delete="emit('delete')"
        @export="exportRule"
        @toggle-enabled="emit('toggle-enabled')"
      >
        <template #actions>
          <VButton size="sm" @click="emit('toggle-bulk-mode')">批量操作</VButton>
        </template>
      </EditorToolbar>
    </div>

    <DragAutoScrollOverlay
      :active="autoScroll.isDragActive.value"
      :active-direction="autoScroll.activeDirection.value"
      :can-scroll-up="autoScroll.canScrollUp.value"
      :can-scroll-down="autoScroll.canScrollDown.value"
      :top-zone-height="dragOverlayTopHeight"
      :bottom-zone-height="dragOverlayBottomHeight"
    />

    <div v-if="!currentRule" class=":uno: flex flex-1 items-center justify-center">
      <span class=":uno: text-sm text-gray-500">{{ emptyStateMessage }}</span>
    </div>

    <form v-else class=":uno: flex min-h-0 flex-1 flex-col" @submit.prevent="emit('save')">
      <div
        ref="editorScrollContainer"
        class=":uno: relative min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4"
        @scroll="autoScroll.handleContainerScroll"
      >
        <FormField label="名称">
          <template v-if="canUndo('name')" #actions>
            <FieldUndoButton @reset="resetField('name')" @undo="undoField('name')" />
          </template>
          <template #default="{ inputId }">
            <input
              :id="inputId"
              :value="currentRule.name"
              class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
              placeholder="不填默认为 ID"
              @focus="beginTextFieldEdit('name')"
              @input="handleTextFieldInput('name', ($event.target as HTMLInputElement).value)"
              @change="commitTextFieldDraft('name')"
            />
          </template>
        </FormField>

        <FormField label="描述">
          <template v-if="canUndo('description')" #actions>
            <FieldUndoButton @reset="resetField('description')" @undo="undoField('description')" />
          </template>
          <template #default="{ inputId }">
            <input
              :id="inputId"
              :value="currentRule.description"
              class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
              placeholder="说明此规则的用途"
              @focus="beginTextFieldEdit('description')"
              @input="
                handleTextFieldInput('description', ($event.target as HTMLInputElement).value)
              "
              @change="commitTextFieldDraft('description')"
            />
          </template>
        </FormField>

        <FormField label="运行顺序" label-semantics="group">
          <template v-if="canUndo('runtimeOrder')" #actions>
            <FieldUndoButton
              @reset="resetField('runtimeOrder')"
              @undo="undoField('runtimeOrder')"
            />
          </template>
          <template #default="{ inputId, labelId }">
            <div :id="inputId" :aria-labelledby="labelId" role="group">
              <RuleRuntimeOrderField
                :model-value="currentRule.runtimeOrder"
                @update:model-value="updateField('runtimeOrder', $event)"
              />
            </div>
          </template>
        </FormField>

        <FormField label="注入模式" required>
          <template v-if="canUndo('mode')" #actions>
            <FieldUndoButton @reset="resetField('mode')" @undo="undoField('mode')" />
          </template>
          <template #default="{ inputId, required }">
            <select
              :id="inputId"
              :value="currentRule.mode"
              class=":uno: focus:border-primary w-full rounded-md border border-gray-200 bg-white px-3 py-1.5 text-sm focus:outline-none"
              :required="required"
              @wheel="updateSelectByWheel"
              @change="
                updateField(
                  'mode',
                  ($event.target as HTMLSelectElement)
                    .value as TransformationRuleEditorDraft['mode'],
                )
              "
            >
              <option v-for="o in MODE_OPTIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
          </template>
        </FormField>

        <template v-if="needsTarget">
          <FormField :invalid="!!matchFieldError" label="CSS 选择器" required>
            <template v-if="canUndo('match')" #actions>
              <FieldUndoButton @reset="resetField('match')" @undo="undoField('match')" />
            </template>
            <template #default="{ inputId, required }">
              <div class=":uno: space-y-1">
                <input
                  :id="inputId"
                  :aria-describedby="matchFieldError ? matchFieldErrorId : undefined"
                  :aria-invalid="!!matchFieldError"
                  :required="required"
                  placeholder="例如：#main-content、.post-card、div[data-role=banner]"
                  :value="matchDraft"
                  :class="
                    matchFieldError
                      ? ':uno: border-red-300 bg-red-50/40 focus:border-red-500'
                      : ':uno: focus:border-primary border-gray-200'
                  "
                  class=":uno: w-full rounded-md border px-3 py-1.5 font-mono text-sm focus:outline-none"
                  @focus="beginMatchEdit"
                  @input="handleMatchInput"
                  @change="commitMatchDraft"
                />
                <p
                  v-if="matchFieldError"
                  :id="matchFieldErrorId"
                  aria-live="polite"
                  class=":uno: text-xs text-red-500"
                  role="alert"
                >
                  {{ matchFieldError }}
                </p>
              </div>
            </template>
          </FormField>

          <FormField label="插入位置">
            <template v-if="canUndo('position')" #actions>
              <FieldUndoButton @reset="resetField('position')" @undo="undoField('position')" />
            </template>
            <template #default="{ inputId }">
              <select
                :id="inputId"
                :value="currentRule.position"
                class=":uno: focus:border-primary w-full rounded-md border border-gray-200 bg-white px-3 py-1.5 text-sm focus:outline-none"
                @wheel="updateSelectByWheel"
                @change="
                  updateField(
                    'position',
                    ($event.target as HTMLSelectElement)
                      .value as TransformationRuleEditorDraft['position'],
                  )
                "
              >
                <option v-for="o in POSITION_OPTIONS" :key="o.value" :value="o.value">
                  {{ o.label }}
                </option>
              </select>
            </template>
          </FormField>
        </template>

        <FormField v-if="needsWrapMarker">
          <template v-if="canUndo('wrapMarker')" #actions>
            <FieldUndoButton @reset="resetField('wrapMarker')" @undo="undoField('wrapMarker')" />
          </template>
          <template #default="{ inputId }">
            <label class=":uno: inline-flex items-center gap-2 text-sm text-gray-700">
              <input
                :id="inputId"
                :checked="currentRule.wrapMarker"
                type="checkbox"
                @change="updateField('wrapMarker', ($event.target as HTMLInputElement).checked)"
              />
              输出注释标记
            </label>
          </template>
        </FormField>

        <FormField v-if="needsSnippets" label="关联代码片段">
          <template #actions>
            <div class=":uno: flex items-center gap-2">
              <span aria-live="polite" class=":uno: text-xs text-gray-400">
                {{ currentRule.snippetIds.length }} 个已选
              </span>
              <FieldUndoButton
                v-if="canUndo('snippetIds')"
                @reset="resetField('snippetIds')"
                @undo="undoField('snippetIds')"
              />
            </div>
          </template>
          <template #default>
            <div class=":uno: space-y-2">
              <ItemPicker
                :items="sortedSnippets"
                label="关联代码片段选择列表"
                :selected-ids="currentRule.snippetIds"
                empty-text="暂无代码片段, 请先创建"
                @toggle="handleToggleSnippet"
              />
              <div
                v-if="emptySnippetAssociationWarning"
                aria-live="polite"
                class=":uno: rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800"
              >
                {{ emptySnippetAssociationWarning }}
              </div>
            </div>
          </template>
        </FormField>

        <FormField label="匹配规则" label-semantics="group" required>
          <template v-if="canUndo('matchRule')" #actions>
            <FieldUndoButton @reset="resetField('matchRule')" @undo="undoField('matchRule')" />
          </template>
          <template #default="{ inputId, labelId, required }">
            <div :id="inputId" :aria-labelledby="labelId" :aria-required="required" role="group">
              <MatchRuleEditor
                ref="matchRuleEditorRef"
                :model-value="currentRule.matchRule"
                :source="currentRule.matchRuleSource"
                @change="emit('field-change')"
                @drag-state-change="autoScroll.setDragActive"
                @update:state="updateMatchRuleField($event)"
              />
            </div>
            <div
              v-if="performanceWarning"
              aria-live="polite"
              class=":uno: mt-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800"
            >
              {{ performanceWarning }}
            </div>
          </template>
        </FormField>
      </div>

      <div ref="editorFooterShell">
        <EditorFooter :dirty="dirty" :saving="saving" @save="emit('save')" />
      </div>
    </form>
  </div>
</template>
