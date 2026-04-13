import { Dialog, Toast } from "@halo-dev/components";
import { computed, type ComputedRef, type Ref } from "vue";

import { ruleApi } from "@/apis";
import type { TransformationRuleEditorDraft, TransformationRuleReadModel } from "@/types";

import { appendCreatedResourcesInOrder } from "./resourceOrder";
import { getErrorMessage } from "./resourceSupport";
import { buildRuleWritePayload } from "./ruleDraft";
import { validateRuleDraft } from "./ruleValidation";

interface UseRuleStateOptions {
  creating: Ref<boolean>;
  savingEditor: Ref<boolean>;
  processingBulk: Ref<boolean>;
  rules: ComputedRef<TransformationRuleReadModel[]>;
  editRule: Ref<TransformationRuleEditorDraft | null>;
  editDirty: Ref<boolean>;
  selectedRuleId: Ref<string | null>;
  refreshRuleSnapshot: () => Promise<void>;
  refreshAllResources: () => Promise<void>;
  saveRuleOrderMap: (items: TransformationRuleReadModel[]) => Promise<true | string>;
  applySavedRuleSnapshot: (rule: TransformationRuleReadModel) => void;
}

/**
 * why: 规则上下文本身比代码片段更复杂，包含 match-rule 校验、snippet 关系归一化与 CRUD；
 * 独立拆出后，规则写语义不再和列表装载、选中态、排序队列挤在同一个 500+ 行模块里。
 */
export function useRuleState(options: UseRuleStateOptions) {
  const ruleEditorError = computed(() => {
    if (!options.editRule.value) return null;
    return validateRuleDraft(options.editRule.value);
  });

  async function addRule(rule: TransformationRuleEditorDraft): Promise<string | null> {
    const error = validateRuleDraft(rule);
    if (error) {
      Toast.error(error);
      return null;
    }
    options.creating.value = true;
    try {
      const payload = buildRuleWritePayload(rule);
      if (!payload) {
        Toast.error("匹配规则有误，请先修正后再保存");
        return null;
      }
      const response = await ruleApi.add(payload);
      await options.refreshRuleSnapshot();
      const orderResult = await options.saveRuleOrderMap(options.rules.value);
      options.selectedRuleId.value = response.data.id;
      if (orderResult === true) {
        Toast.success("规则已创建");
      } else {
        Toast.warning(`规则已创建，但顺序保存失败：${orderResult}`);
      }
      return response.data.id;
    } catch (error) {
      Toast.error(getErrorMessage(error, "创建失败"));
      return null;
    } finally {
      options.creating.value = false;
    }
  }

  async function importRules(
    rules: TransformationRuleEditorDraft[],
    enabled: boolean,
  ): Promise<string[]> {
    if (!rules.length) {
      return [];
    }

    options.processingBulk.value = true;
    const createdIds: string[] = [];
    const failures: string[] = [];

    try {
      for (const rule of rules) {
        const error = validateRuleDraft(rule);
        if (error) {
          failures.push(error);
          continue;
        }

        try {
          const payload = buildRuleWritePayload({
            ...rule,
            enabled,
          });
          if (!payload) {
            failures.push("匹配规则有误，请先修正后再保存");
            continue;
          }
          const response = await ruleApi.add(payload);
          createdIds.push(response.data.id);
        } catch (error) {
          failures.push(getErrorMessage(error, "创建失败"));
        }
      }

      if (createdIds.length > 0) {
        await options.refreshRuleSnapshot();
        const orderedItems = appendCreatedResourcesInOrder(options.rules.value, createdIds);
        const orderResult = await options.saveRuleOrderMap(orderedItems);
        if (orderResult !== true) {
          failures.push(`顺序保存失败：${orderResult}`);
        }
      }

      if (createdIds.length > 0 && failures.length === 0) {
        Toast.success(`已导入 ${createdIds.length} 个转换规则`);
      } else if (createdIds.length > 0) {
        Toast.warning(`已导入 ${createdIds.length} 个转换规则，另有 ${failures.length} 项失败`);
      } else {
        Toast.error(failures[0] ?? "导入失败");
      }

      return createdIds;
    } finally {
      options.processingBulk.value = false;
    }
  }

  async function saveRule() {
    if (!options.editRule.value) return false;
    const error = ruleEditorError.value;
    if (error) {
      Toast.error(error);
      return false;
    }
    options.savingEditor.value = true;
    try {
      const payload = buildRuleWritePayload(options.editRule.value);
      if (!payload) {
        Toast.error("匹配规则有误，请先修正后再保存");
        return false;
      }
      await ruleApi.update(options.editRule.value.id, payload);
      await options.refreshRuleSnapshot();
      options.editDirty.value = false;
      Toast.success("保存成功");
      return true;
    } catch (error) {
      Toast.error(getErrorMessage(error, "保存失败"));
      return false;
    } finally {
      options.savingEditor.value = false;
    }
  }

  async function toggleRuleEnabled() {
    if (!options.editRule.value) return;
    const nextEnabled = !options.editRule.value.enabled;
    const previousEnabled = options.editRule.value.enabled;
    try {
      const response = await ruleApi.updateEnabled(
        options.editRule.value.id,
        nextEnabled,
        options.editRule.value.metadata.version,
      );
      options.applySavedRuleSnapshot(response.data);
      Toast.success(nextEnabled ? "规则已启用" : "规则已停用");
    } catch (error) {
      options.editRule.value.enabled = previousEnabled;
      Toast.error(getErrorMessage(error, nextEnabled ? "启用失败" : "停用失败"));
    }
  }

  async function setRulesEnabled(ids: string[], enabled: boolean) {
    const targetRules = options.rules.value.filter((rule) => ids.includes(rule.id));
    if (!targetRules.length) {
      Toast.warning("请先选择转换规则");
      return;
    }

    options.processingBulk.value = true;
    let successCount = 0;
    try {
      for (const rule of targetRules) {
        try {
          await ruleApi.updateEnabled(rule.id, enabled, rule.metadata.version);
          successCount += 1;
        } catch {
          continue;
        }
      }

      await options.refreshRuleSnapshot();

      if (successCount === targetRules.length) {
        Toast.success(`已${enabled ? "启用" : "禁用"} ${successCount} 个转换规则`);
      } else if (successCount > 0) {
        Toast.warning(
          `已${enabled ? "启用" : "禁用"} ${successCount} 个转换规则，另有 ${
            targetRules.length - successCount
          } 个失败`,
        );
      } else {
        Toast.error(`${enabled ? "启用" : "禁用"}失败`);
      }
    } finally {
      options.processingBulk.value = false;
    }
  }

  function confirmDeleteRule() {
    if (!options.editRule.value) return;
    const id = options.editRule.value.id;
    Dialog.warning({
      title: "删除规则",
      description: `确认删除规则 ${id}？删除后无法恢复。`,
      confirmType: "danger",
      async onConfirm() {
        try {
          await ruleApi.delete(id, options.editRule.value?.metadata.version);
          if (options.selectedRuleId.value === id) options.selectedRuleId.value = null;
          options.editRule.value = null;
          options.editDirty.value = false;
          await options.refreshAllResources();
          const orderResult = await options.saveRuleOrderMap(options.rules.value);
          if (orderResult === true) {
            Toast.success("规则已删除");
          } else {
            Toast.warning(`规则已删除，但顺序保存失败：${orderResult}`);
          }
        } catch (error) {
          Toast.error(getErrorMessage(error, "删除失败"));
        }
      },
    });
  }

  function confirmDeleteRules(ids: string[]) {
    const targetRules = options.rules.value.filter((rule) => ids.includes(rule.id));
    if (!targetRules.length) {
      Toast.warning("请先选择转换规则");
      return;
    }

    Dialog.warning({
      title: "批量删除转换规则",
      description: `确认删除已选择的 ${targetRules.length} 个转换规则？删除后无法恢复。`,
      confirmType: "danger",
      async onConfirm() {
        options.processingBulk.value = true;
        let successCount = 0;
        try {
          for (const rule of targetRules) {
            try {
              await ruleApi.delete(rule.id, rule.metadata.version);
              successCount += 1;
            } catch {
              continue;
            }
          }

          if (targetRules.some((rule) => rule.id === options.selectedRuleId.value)) {
            options.selectedRuleId.value = null;
            options.editRule.value = null;
            options.editDirty.value = false;
          }

          await options.refreshAllResources();
          const orderResult = await options.saveRuleOrderMap(options.rules.value);

          if (successCount === targetRules.length && orderResult === true) {
            Toast.success(`已删除 ${successCount} 个转换规则`);
          } else if (successCount > 0) {
            const suffix = orderResult === true ? "" : `；顺序保存失败：${orderResult}`;
            Toast.warning(
              `已删除 ${successCount} 个转换规则，另有 ${
                targetRules.length - successCount
              } 个失败${suffix}`,
            );
          } else {
            Toast.error("删除失败");
          }
        } finally {
          options.processingBulk.value = false;
        }
      },
    });
  }

  return {
    ruleEditorError,
    addRule,
    importRules,
    saveRule,
    toggleRuleEnabled,
    setRulesEnabled,
    confirmDeleteRule,
    confirmDeleteRules,
  };
}
