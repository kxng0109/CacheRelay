import { describe, expect, it } from 'vitest'
import { isEditable, targetForChord } from './shortcuts.js'

describe('targetForChord', () => {
  it('maps mnemonic letters to session-aware destinations', () => {
    expect(targetForChord('o')).toBe('/')
    expect(targetForChord('p')).toBe('/playground')
    expect(targetForChord('e')).toBe('/embeddings')
    expect(targetForChord('b')).toBe('/observability')
    expect(targetForChord('c')).toBe('/circuits')
    expect(targetForChord('k')).toBe('/keys')
    expect(targetForChord('l')).toBe('/ledger')
    expect(targetForChord('a')).toBe('/approvals')
    expect(targetForChord('m')).toBe('/mcp')
  })

  it('is case-insensitive and rejects unbound keys', () => {
    expect(targetForChord('P')).toBe('/playground')
    expect(targetForChord('z')).toBeNull()
    expect(targetForChord('Enter')).toBeNull()
    expect(targetForChord('')).toBeNull()
  })
})

describe('isEditable', () => {
  it('recognizes form fields', () => {
    // jsdom never implements editing hosts, so contentEditable coverage
    // lives with the keyboard tests (typing never triggers chords).
    expect(isEditable(document.createElement('input'))).toBe(true)
    expect(isEditable(document.createElement('textarea'))).toBe(true)
    expect(isEditable(document.createElement('select'))).toBe(true)
  })

  it('leaves body and buttons alone', () => {
    expect(isEditable(document.body)).toBe(false)
    expect(isEditable(document.createElement('button'))).toBe(false)
    expect(isEditable(null)).toBe(false)
  })
})
