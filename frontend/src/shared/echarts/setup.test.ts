import { describe, expect, it } from 'vitest'
import { echarts } from './setup.js'

describe('echarts setup', () => {
  it('registers the tree-shaken instance without a canvas', () => {
    expect(typeof echarts.init).toBe('function')
    expect(typeof echarts.use).toBe('function')
  })
})
