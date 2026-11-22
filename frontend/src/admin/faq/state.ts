/**
 * FAQ 관리 화면의 순수 상태 로직(PLAN-react-admin-faq.md 결정 6). 옛 인라인 스크립트(templates/admin/faq/manage.html)의
 * serverOrder·isDirty·interactionLocked·formLocked·renderCategorySelect·categorySnapshot·faqSnapshot과 같은 규칙이다.
 */
import type { FaqTree, SiteScopeValue } from '../../api/faq'
import type { HttpResult } from '../../api/http'

export interface OrderGroup {
  id: number
  faqIds: number[]
}

export interface LockFlags {
  busy: boolean
  loadingDetail: boolean
  syncFailed: boolean
}

export interface CategoryForm {
  name: string
  useYn: boolean
  siteScope: SiteScopeValue
}

export interface FaqForm {
  /** 선택 박스 값(카테고리 ID 문자열). 트리에서 사라진 카테고리도 그대로 들고 있는다 */
  categoryId: string
  question: string
  useYn: boolean
}

export interface CategoryOption {
  value: string
  label: string
  missing: boolean
}

export type WriteOutcome = 'ok' | 'unknown' | 'conflict' | 'rejected'

export function serverOrder(tree: FaqTree): OrderGroup[] {
  return tree.categories.map((c) => ({ id: c.id, faqIds: c.faqs.map((f) => f.id) }))
}

/** 순서 초안이 서버 순서와 다른가. */
export function isDirty(tree: FaqTree | null, order: OrderGroup[]): boolean {
  return tree !== null && JSON.stringify(order) !== JSON.stringify(serverOrder(tree))
}

function move<T>(items: T[], from: number, to: number): T[] {
  const next = items.slice()
  next.splice(to, 0, next.splice(from, 1)[0])
  return next
}

/** 카테고리 순서를 바꾼 새 초안. 범위 밖·제자리면 같은 배열을 그대로 돌려준다. */
export function moveCategory(order: OrderGroup[], from: number, to: number): OrderGroup[] {
  if (to < 0 || to >= order.length || from === to) {
    return order
  }
  return move(order, from, to)
}

/** 같은 카테고리 안에서 FAQ 순서를 바꾼 새 초안. 범위 밖·제자리·없는 카테고리면 같은 배열을 그대로 돌려준다. */
export function moveFaq(order: OrderGroup[], categoryId: number, from: number, to: number): OrderGroup[] {
  const index = order.findIndex((g) => g.id === categoryId)
  if (index < 0) {
    return order
  }
  const group = order[index]
  if (to < 0 || to >= group.faqIds.length || from === to) {
    return order
  }
  const next = order.slice()
  next[index] = { id: group.id, faqIds: move(group.faqIds, from, to) }
  return next
}

/** 드래그·▲▼·순서 반영 등 목록 조작이 막히는 상태. */
export function interactionLocked(flags: LockFlags): boolean {
  return flags.busy || flags.loadingDetail || flags.syncFailed
}

/** 폼 입력과 쓰기 버튼이 막히는 상태 — 초안이 있는 동안에도 막는다(목록 재조회가 초안을 지우므로). */
export function formLocked(flags: LockFlags, dirty: boolean): boolean {
  return interactionLocked(flags) || dirty
}

/** 쓰기 응답 분기. 2xx면 본문을 읽지 못해도 성공이다(목록 재조회로 계속). */
export function classifyWrite(result: HttpResult): WriteOutcome {
  if (result.kind === 'network') {
    return 'unknown'
  }
  if (result.ok) {
    return 'ok'
  }
  return result.status === 409 ? 'conflict' : 'rejected'
}

/**
 * FAQ 폼의 카테고리 선택지. 선택했던 카테고리가 트리에서 사라졌고 FAQ 모달이 열려 있으면 다른 카테고리가 조용히 선택되지 않도록
 * 임시 옵션으로 보존한다(제출은 이 값이 트리에 없으면 요청 없이 막는다).
 */
export function categoryOptions(tree: FaqTree, selected: string, preserveMissing: boolean): CategoryOption[] {
  const options = tree.categories.map((c) => ({
    value: String(c.id),
    label: c.name + (c.useYn ? '' : ' (비노출)'),
    missing: false,
  }))
  if (preserveMissing && selected !== '' && !options.some((o) => o.value === selected)) {
    options.push({ value: selected, label: '삭제된 카테고리 #' + selected, missing: true })
  }
  return options
}

/**
 * FAQ 폼의 카테고리 선택값. 옛 화면은 선택 박스를 다시 그릴 때 이전 값이 남아 있으면 유지하고, 아니면 브라우저가 첫 옵션을 골랐다.
 * controlled 폼에서는 그 결과를 직접 계산한다. preserveMissing이면(FAQ 모달이 열려 있음) 사라진 값도 유지한다(categoryOptions가 임시 옵션을 그린다).
 */
export function resolveCategorySelection(tree: FaqTree, selected: string, preserveMissing: boolean): string {
  if (tree.categories.some((c) => String(c.id) === selected)) {
    return selected
  }
  if (preserveMissing && selected !== '') {
    return selected
  }
  return tree.categories.length > 0 ? String(tree.categories[0].id) : ''
}

/** 미저장 판정 기준값 — 폼을 채울 때 쓴 모델 값으로 만든다(DOM을 읽지 않는다). */
export function categorySnapshot(form: CategoryForm): string {
  return JSON.stringify([form.name, form.useYn, form.siteScope])
}

/** 답변 편집기는 값이 아니라 editor.isChanged()로 따로 판정한다. */
export function faqSnapshot(form: FaqForm): string {
  return JSON.stringify([form.categoryId, form.question, form.useYn])
}
