import { beforeEach, describe, expect, it } from 'vitest'
import { useDriftStore } from './store.js'

beforeEach(() => {
  useDriftStore.getState().clear()
})

describe('useDriftStore', () => {
  it('starts quiet with no hidden payloads', () => {
    expect(useDriftStore.getState().count).toBe(0)
    expect(useDriftStore.getState().lastEndpoint).toBeNull()
  })

  it('counts hidden payloads behind the latest endpoint', () => {
    useDriftStore.getState().note('keys')
    useDriftStore.getState().note('ledger')
    expect(useDriftStore.getState().count).toBe(2)
    expect(useDriftStore.getState().lastEndpoint).toBe('ledger')
  })

  it('dismisses the notice', () => {
    useDriftStore.getState().note('keys')
    useDriftStore.getState().clear()
    expect(useDriftStore.getState().count).toBe(0)
    expect(useDriftStore.getState().lastEndpoint).toBeNull()
  })
})
