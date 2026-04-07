import { describe, expect, it } from 'vitest'
import { IMPORT_JSON_SOURCE_ACTIONS } from '../importJsonSource'

describe('IMPORT_JSON_SOURCE_ACTIONS', () => {
  // why: 显式的二次选择流程；
  // 这里锁住动作顺序和文案，避免后续重构时又悄悄退回成单一路径导入。
  it('defines cancel clipboard and file actions in order', () => {
    expect(IMPORT_JSON_SOURCE_ACTIONS).toEqual([
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
    ])
  })
})
