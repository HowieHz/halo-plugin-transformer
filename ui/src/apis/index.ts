import { axiosInstance } from "@halo-dev/api-client";

import type {
  TransformationSnippetReadModel,
  TransformationSnippetWritePayload,
  TransformationRuleReadModel,
  TransformationRuleWritePayload,
  ItemList,
  OrderedItemList,
} from "@/types";

const CONSOLE_BASE = "/apis/console.api.transformer.howiehz.top/v1alpha1";
const SNIPPETS = `${CONSOLE_BASE}/transformationSnippets`;
const SNIPPET_SNAPSHOT = `${SNIPPETS}/-/snapshot`;
const SNIPPETS_WRITE = `${CONSOLE_BASE}/transformationSnippets`;
const SNIPPET_ORDER = `${CONSOLE_BASE}/snippet-order`;
const RULES = `${CONSOLE_BASE}/transformationRules`;
const RULE_SNAPSHOT = `${RULES}/-/snapshot`;
const RULES_WRITE = `${CONSOLE_BASE}/transformationRules`;
const RULE_ORDER = `${CONSOLE_BASE}/rule-order`;

export type OrderMap = Record<string, number>;
export interface PersistedOrderState {
  orders: OrderMap;
  version: number | null;
}

interface DeletePayload {
  metadata: {
    version: number | null;
  };
}

export const snippetApi = {
  list() {
    return axiosInstance.get<ItemList<TransformationSnippetReadModel>>(SNIPPETS);
  },

  getSnapshot() {
    return axiosInstance.get<OrderedItemList<TransformationSnippetReadModel>>(SNIPPET_SNAPSHOT);
  },

  add(snippet: TransformationSnippetWritePayload) {
    return axiosInstance.post<TransformationSnippetReadModel>(SNIPPETS_WRITE, snippet);
  },

  update(id: string, snippet: TransformationSnippetWritePayload) {
    return axiosInstance.put<TransformationSnippetReadModel>(`${SNIPPETS_WRITE}/${id}`, snippet);
  },

  updateEnabled(id: string, enabled: boolean, version: number | null | undefined) {
    return axiosInstance.put<TransformationSnippetReadModel>(`${SNIPPETS_WRITE}/${id}/enabled`, {
      enabled,
      metadata: { version: version ?? null },
    });
  },

  getOrder() {
    return axiosInstance.get<PersistedOrderState>(SNIPPET_ORDER);
  },

  updateOrder(orders: OrderMap, version: number | null | undefined) {
    return axiosInstance.put<PersistedOrderState>(SNIPPET_ORDER, {
      orders,
      metadata: { version: version ?? null },
    });
  },

  delete(id: string, version: number | null | undefined) {
    return axiosInstance.delete(`${SNIPPETS_WRITE}/${id}`, {
      data: {
        metadata: { version: version ?? null },
      } satisfies DeletePayload,
    });
  },
};

export const ruleApi = {
  list() {
    return axiosInstance.get<ItemList<TransformationRuleReadModel>>(RULES);
  },

  getSnapshot() {
    return axiosInstance.get<OrderedItemList<TransformationRuleReadModel>>(RULE_SNAPSHOT);
  },

  add(rule: TransformationRuleWritePayload) {
    return axiosInstance.post<TransformationRuleReadModel>(RULES_WRITE, rule);
  },

  update(id: string, rule: TransformationRuleWritePayload) {
    return axiosInstance.put<TransformationRuleReadModel>(`${RULES_WRITE}/${id}`, rule);
  },

  updateEnabled(id: string, enabled: boolean, version: number | null | undefined) {
    return axiosInstance.put<TransformationRuleReadModel>(`${RULES_WRITE}/${id}/enabled`, {
      enabled,
      metadata: { version: version ?? null },
    });
  },

  getOrder() {
    return axiosInstance.get<PersistedOrderState>(RULE_ORDER);
  },

  updateOrder(orders: OrderMap, version: number | null | undefined) {
    return axiosInstance.put<PersistedOrderState>(RULE_ORDER, {
      orders,
      metadata: { version: version ?? null },
    });
  },

  delete(id: string, version: number | null | undefined) {
    return axiosInstance.delete(`${RULES_WRITE}/${id}`, {
      data: {
        metadata: { version: version ?? null },
      } satisfies DeletePayload,
    });
  },
};
