import { describe, expect, it } from 'vitest'
import type { FaqTree } from '../../api/faq'
import {
  categoryOptions,
  categorySnapshot,
  classifyWrite,
  faqSnapshot,
  formLocked,
  interactionLocked,
  isDirty,
  moveCategory,
  locate,
  moveFaq,
  moveSummary,
  resolveCategorySelection,
  serverOrder,
} from './state'

const tree: FaqTree = {
  version: 3,
  categories: [
    { id: 10, name: '배송', ord: 0, useYn: true, siteId: null, version: 1, faqs: [
      { id: 1, categoryId: 10, question: 'a', ord: 0, useYn: true },
      { id: 2, categoryId: 10, question: 'b', ord: 1, useYn: true },
      { id: 3, categoryId: 10, question: 'c', ord: 2, useYn: false },
    ] },
    { id: 20, name: '결제', ord: 1, useYn: false, siteId: 5, version: 2, faqs: [] },
  ],
}

describe('순서 초안', () => {
  it('서버 트리에서 초안을 만들면 초안이 없는 상태다', () => {
    expect(serverOrder(tree)).toEqual([{ id: 10, faqIds: [1, 2, 3] }, { id: 20, faqIds: [] }])
    expect(isDirty(tree, serverOrder(tree))).toBe(false)
    expect(isDirty(null, [])).toBe(false)
  })

  it('카테고리를 옮기면 초안이 생기고 원본은 바뀌지 않는다', () => {
    const order = serverOrder(tree)
    const moved = moveCategory(order, 0, 1)
    expect(moved.map((g) => g.id)).toEqual([20, 10])
    expect(order.map((g) => g.id)).toEqual([10, 20])
    expect(isDirty(tree, moved)).toBe(true)
  })

  it('같은 카테고리 안에서 FAQ를 옮긴다', () => {
    const moved = moveFaq(serverOrder(tree), 10, 2, 0)
    expect(moved[0].faqIds).toEqual([3, 1, 2])
  })

  it('범위 밖·제자리 이동은 같은 초안을 그대로 돌려준다', () => {
    const order = serverOrder(tree)
    expect(moveCategory(order, 0, -1)).toBe(order)
    expect(moveCategory(order, 1, 2)).toBe(order)
    expect(moveCategory(order, 1, 1)).toBe(order)
    expect(moveFaq(order, 10, 0, 3)).toBe(order)
    expect(moveFaq(order, 99, 0, 1)).toBe(order)
  })

  it('옮겼다가 되돌리면 초안이 없는 상태로 돌아온다', () => {
    const back = moveFaq(moveFaq(serverOrder(tree), 10, 0, 1), 10, 1, 0)
    expect(isDirty(tree, back)).toBe(false)
  })
})

describe('잠금 판정', () => {
  const idle = { busy: false, loadingDetail: false, syncFailed: false }

  it('목록 조작은 쓰기·상세 조회·동기화 실패 중에 막힌다', () => {
    expect(interactionLocked(idle)).toBe(false)
    expect(interactionLocked({ ...idle, busy: true })).toBe(true)
    expect(interactionLocked({ ...idle, loadingDetail: true })).toBe(true)
    expect(interactionLocked({ ...idle, syncFailed: true })).toBe(true)
  })

  it('폼은 초안이 있는 동안에도 막힌다(재조회가 초안을 지우므로)', () => {
    expect(formLocked(idle, false)).toBe(false)
    expect(formLocked(idle, true)).toBe(true)
  })
})

describe('쓰기 결과 분기', () => {
  it('헤더를 받기 전 실패는 결과 불명이다', () => {
    expect(classifyWrite({ kind: 'network' })).toBe('unknown')
  })

  it('2xx는 본문을 읽지 못해도 성공이다', () => {
    expect(classifyWrite({ kind: 'http', status: 200, ok: true, body: null, bodyError: true })).toBe('ok')
    expect(classifyWrite({ kind: 'http', status: 204, ok: true, body: null, bodyError: false })).toBe('ok')
  })

  it('409는 재조회가 필요한 충돌, 그 밖의 실패는 거절이다', () => {
    expect(classifyWrite({ kind: 'http', status: 409, ok: false, body: null, bodyError: false })).toBe('conflict')
    expect(classifyWrite({ kind: 'http', status: 400, ok: false, body: null, bodyError: false })).toBe('rejected')
    expect(classifyWrite({ kind: 'http', status: 403, ok: false, body: null, bodyError: false })).toBe('rejected')
  })
})

describe('FAQ 카테고리 선택지', () => {
  it('비노출 카테고리는 이름 뒤에 표시한다', () => {
    expect(categoryOptions(tree, '10', false)).toEqual([
      { value: '10', label: '배송', missing: false },
      { value: '20', label: '결제 (비노출)', missing: false },
    ])
  })

  it('선택했던 카테고리가 사라지면 FAQ 모달이 열려 있을 때만 임시 옵션으로 보존한다', () => {
    const shrunk: FaqTree = { ...tree, categories: [tree.categories[0]] }
    expect(categoryOptions(shrunk, '20', true)).toEqual([
      { value: '10', label: '배송', missing: false },
      { value: '20', label: '삭제된 카테고리 #20', missing: true },
    ])
    expect(categoryOptions(shrunk, '20', false)).toEqual([{ value: '10', label: '배송', missing: false }])
    expect(categoryOptions(shrunk, '', true)).toEqual([{ value: '10', label: '배송', missing: false }])
  })
})

describe('FAQ 카테고리 선택값', () => {
  it('트리에 있는 값은 그대로 둔다', () => {
    expect(resolveCategorySelection(tree, '20', false)).toBe('20')
  })

  it('없는 값은 보존할 때만 유지하고, 아니면 첫 카테고리(옛 선택 박스의 브라우저 기본 선택)로 바꾼다', () => {
    expect(resolveCategorySelection(tree, '99', true)).toBe('99')
    expect(resolveCategorySelection(tree, '99', false)).toBe('10')
    expect(resolveCategorySelection(tree, '', true)).toBe('10')
    expect(resolveCategorySelection({ version: 0, categories: [] }, '', false)).toBe('')
  })
})

describe('미저장 판정 기준값', () => {
  it('같은 필드 값이면 같은 기준값이다', () => {
    expect(categorySnapshot({ name: 'a', useYn: true, siteScope: 'COMMON' }))
      .toBe(categorySnapshot({ name: 'a', useYn: true, siteScope: 'COMMON' }))
    expect(faqSnapshot({ categoryId: '10', question: 'q', useYn: true }))
      .not.toBe(faqSnapshot({ categoryId: '10', question: 'q', useYn: false }))
  })
})

describe('이동 대상 찾기와 안내 문구', () => {
  const named = {
    version: 1,
    categories: [
      { id: 1, name: '카테고리1', faqs: [{ id: 11, question: '질문A' }, { id: 12, question: '질문B' }] },
      { id: 2, name: '카테고리2', faqs: [{ id: 21, question: '질문X' }] },
    ],
  } as unknown as FaqTree
  const order = [{ id: 2, faqIds: [21] }, { id: 1, faqIds: [12, 11] }]

  it('초안에서의 위치를 ID로 찾는다', () => {
    expect(locate(order, { kind: 'category', id: 1 })).toEqual({ cat: 1, index: 1, total: 2 })
    expect(locate(order, { kind: 'faq', id: 11 })).toEqual({ cat: 1, index: 1, total: 2 })
    expect(locate(order, { kind: 'faq', id: 99 })).toBeNull()
    expect(locate(order, { kind: 'category', id: 11 })).toBeNull()
  })

  it('이름과 초안에서의 순서를 읽고, 없는 항목이면 비운다', () => {
    expect(moveSummary(named, order, { kind: 'category', id: 1 })).toBe('카테고리1: 2개 중 2번째로 이동')
    expect(moveSummary(named, order, { kind: 'faq', id: 12 })).toBe('질문B: 2개 중 1번째로 이동')
    expect(moveSummary(named, order, { kind: 'faq', id: 99 })).toBe('')
  })
})
