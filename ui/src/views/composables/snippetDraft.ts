import type { CodeSnippetEditorDraft, CodeSnippetReadModel, CodeSnippetWritePayload } from '@/types'

/**
 * why: 列表读模型与编辑草稿必须显式转换，避免把服务端返回对象直接当作可变表单状态复用。
 */
export function hydrateSnippetEditorDraft(snippet: CodeSnippetReadModel): CodeSnippetEditorDraft {
  return {
    apiVersion: snippet.apiVersion,
    kind: snippet.kind,
    metadata: {
      name: snippet.metadata.name,
      version: snippet.metadata.version ?? null,
    },
    id: snippet.id,
    name: snippet.name,
    code: snippet.code,
    description: snippet.description,
    enabled: snippet.enabled,
  }
}

/**
 * why: 写接口只接受持久化字段；这里集中收口，防止 `id` 之类的展示态字段再次混进 payload。
 */
export function buildSnippetWritePayload(snippet: CodeSnippetEditorDraft): CodeSnippetWritePayload {
  const { id: _ignoredId, ...payload } = snippet
  return payload
}
