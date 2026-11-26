import { describe, expect, it } from 'vitest'
import { dropPosition } from './dropPosition'

describe('dropPosition', () => {
  it('위 1/4는 앞, 아래 1/4는 뒤, 가운데는 안이다', () => {
    expect(dropPosition(100, 40, 100)).toBe('before')
    expect(dropPosition(100, 40, 109)).toBe('before')
    expect(dropPosition(100, 40, 110)).toBe('inside')
    expect(dropPosition(100, 40, 129)).toBe('inside')
    expect(dropPosition(100, 40, 130)).toBe('after')
    expect(dropPosition(100, 40, 139)).toBe('after')
  })
})
