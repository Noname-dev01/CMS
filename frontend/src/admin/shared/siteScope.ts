/**
 * 콘텐츠 "표시 대상" 선택(공통 또는 공개 사이트) 규칙 — static/js/admin/content-scope.js와 같은 규칙의 TS 판이다
 * (전환기 동안 두 벌을 수용, PLAN-react-admin-faq.md 결정 7). 이름은 관리자 입력이라 텍스트로만 렌더링한다.
 */
export const COMMON = 'COMMON'

export interface ScopeSite {
  id: string
  name: string
  active: boolean
}

export interface ScopeOption {
  value: string
  label: string
}

/** 서버의 siteId(null = 공통)를 선택 박스 값으로. */
export function scopeValue(siteId: number | null | undefined): string {
  return siteId === null || siteId === undefined ? COMMON : String(siteId)
}

function siteLabel(site: ScopeSite): string {
  return site.active ? site.name : site.name + ' (비활성)'
}

function extraLabel(id: string): string {
  return '사이트 #' + id + ' (목록에 없음)'
}

export function scopeOptions(sites: ScopeSite[], extras: string[]): ScopeOption[] {
  return [
    { value: COMMON, label: '공통 (모든 사이트)' },
    ...sites.map((s) => ({ value: s.id, label: siteLabel(s) })),
    ...extras.map((id) => ({ value: id, label: extraLabel(id) })),
  ]
}

/**
 * 폼에 채울 값이 선택지에 없으면(문서를 연 뒤 다른 관리자가 만든 사이트) 보존 옵션 목록에 더한다. 선택지에 있으면 같은 배열을 돌려준다.
 * 옵션에 없는 값을 그대로 고르면 선택이 비워져 "공통"으로 저장될 수 있다.
 */
export function preserveScope(sites: ScopeSite[], extras: string[], value: string): string[] {
  if (value === COMMON || sites.some((s) => s.id === value) || extras.includes(value)) {
    return extras
  }
  return [...extras, value]
}

/** 목록 배지에 쓸 이름. 공통이면 "공통", 선택지에 없는 사이트는 "사이트 #ID". */
export function scopeLabel(sites: ScopeSite[], extras: string[], siteId: number | null | undefined): string {
  if (siteId === null || siteId === undefined) {
    return '공통'
  }
  const id = String(siteId)
  const site = sites.find((s) => s.id === id)
  if (site) {
    return siteLabel(site)
  }
  return extras.includes(id) ? extraLabel(id) : '사이트 #' + id
}
