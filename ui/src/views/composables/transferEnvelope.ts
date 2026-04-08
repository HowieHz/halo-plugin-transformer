export type SingleTransferResourceType = 'snippet' | 'rule'
export type BatchTransferResourceType = 'snippet-batch' | 'rule-batch'
export type TransferResourceType = SingleTransferResourceType | BatchTransferResourceType

export const TRANSFER_SCHEMA_URL =
  'https://raw.githubusercontent.com/HowieHz/halo-plugin-transformer/main/ui/public/transformer.schema.json'

export interface TransferEnvelope<TType extends TransferResourceType, TData> {
  $schema?: string
  version: 1
  resourceType: TType
  data: TData
}

export function parseTransferEnvelope<
  T extends { $schema?: string; version: number; resourceType: string; data: unknown },
>(raw: string, expectedType: TransferResourceType) {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new Error('导入失败：文件不是合法的 JSON')
  }
  if (!isPlainObject(parsed)) {
    throw new Error('导入失败：根节点必须是对象')
  }
  ensureAllowedFields(parsed, ['$schema', 'version', 'resourceType', 'data'], '导入文件')
  if (parsed.$schema !== undefined && typeof parsed.$schema !== 'string') {
    throw new Error('导入失败：`$schema` 必须是字符串')
  }
  if (parsed.version !== 1) {
    throw new Error('导入失败：暂不支持这个导出版本')
  }
  if (parsed.resourceType !== expectedType) {
    throw new Error(resolveTransferTypeMismatchMessage(expectedType))
  }
  if (!('data' in parsed) || !isPlainObject(parsed.data)) {
    throw new Error('导入失败：缺少 `data` 对象')
  }
  return parsed as T
}

function resolveTransferTypeMismatchMessage(expectedType: TransferResourceType) {
  switch (expectedType) {
    case 'snippet':
      return '导入失败：当前只能导入代码片段 JSON'
    case 'rule':
      return '导入失败：当前只能导入转换规则 JSON'
    case 'snippet-batch':
      return '导入失败：当前只能导入批量代码片段 JSON'
    case 'rule-batch':
      return '导入失败：当前只能导入批量转换规则 JSON'
  }
}

export function validateEnumField(
  fieldName: string,
  value: unknown,
  allowedValues: readonly string[],
  options: { required?: boolean } = {},
) {
  const quotedAllowedValues = allowedValues.map((item) => `"${item}"`).join('、')
  const required = options.required ?? true

  if (value === undefined) {
    if (required) {
      throw new Error(`导入失败：\`${fieldName}\` 缺少必填字段；仅支持 ${quotedAllowedValues}`)
    }
    return
  }

  if (typeof value !== 'string') {
    throw new Error(`导入失败：\`${fieldName}\` 必须是字符串；仅支持 ${quotedAllowedValues}`)
  }

  if (!allowedValues.includes(value)) {
    throw new Error(`导入失败：\`${fieldName}\` 仅支持 ${quotedAllowedValues}`)
  }
}

export function ensureAllowedFields(
  data: object,
  allowedFields: readonly string[],
  resourceLabel: string,
) {
  const invalidField = Object.keys(data).find((key) => !allowedFields.includes(key))
  if (!invalidField) {
    return
  }
  const quotedAllowedFields = allowedFields.map((field) => `"${field}"`).join('、')
  throw new Error(
    `导入失败：\`${invalidField}\` 不支持；${resourceLabel}仅支持 ${quotedAllowedFields}`,
  )
}

export function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

