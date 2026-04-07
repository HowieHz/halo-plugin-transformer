export type ImportJsonSourceAction = 'close' | 'import-from-clipboard' | 'import-from-file'

export const IMPORT_JSON_SOURCE_ACTIONS: ReadonlyArray<{
  action: ImportJsonSourceAction
  label: string
  secondary?: boolean
}> = [
  {
    action: 'close',
    label: '取消',
  },
  {
    action: 'import-from-clipboard',
    label: '从剪贴板导入',
    secondary: true,
  },
  {
    action: 'import-from-file',
    label: '从文件导入',
    secondary: true,
  },
]
