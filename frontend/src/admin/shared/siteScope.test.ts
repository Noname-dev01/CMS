import { describe, expect, it } from 'vitest'
import { preserveScope, scopeLabel, scopeOptions, scopeValue, type ScopeSite } from './siteScope'

const sites: ScopeSite[] = [
  { id: '1', name: '본사', active: true },
  { id: '2', name: '옛 지점', active: false },
]

describe('표시 대상(사이트) 규칙 — static/js/admin/content-scope.js와 같다', () => {
  it('선택지는 공통이 먼저이고 비활성 사이트는 이름 뒤에 표시한다', () => {
    expect(scopeOptions(sites, [])).toEqual([
      { value: 'COMMON', label: '공통 (모든 사이트)' },
      { value: '1', label: '본사' },
      { value: '2', label: '옛 지점 (비활성)' },
    ])
  })

  it('서버 값 null은 COMMON, 사이트 ID는 문자열이다', () => {
    expect(scopeValue(null)).toBe('COMMON')
    expect(scopeValue(7)).toBe('7')
  })

  it('목록에 없는 사이트 ID는 보존 옵션을 더해 값을 지킨다(공통으로 저장되는 것을 막는다)', () => {
    const extras = preserveScope(sites, [], '9')
    expect(extras).toEqual(['9'])
    expect(scopeOptions(sites, extras).at(-1)).toEqual({ value: '9', label: '사이트 #9 (목록에 없음)' })
    expect(preserveScope(sites, extras, '9')).toBe(extras)
    expect(preserveScope(sites, extras, '1')).toBe(extras)
    expect(preserveScope(sites, extras, 'COMMON')).toBe(extras)
  })

  it('배지 이름: 공통은 "공통", 아는 사이트는 선택지 이름, 모르는 사이트는 "사이트 #ID"', () => {
    expect(scopeLabel(sites, [], null)).toBe('공통')
    expect(scopeLabel(sites, [], 2)).toBe('옛 지점 (비활성)')
    expect(scopeLabel(sites, [], 9)).toBe('사이트 #9')
    expect(scopeLabel(sites, ['9'], 9)).toBe('사이트 #9 (목록에 없음)')
  })
})
