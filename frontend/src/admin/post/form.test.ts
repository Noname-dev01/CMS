import { describe, expect, it } from 'vitest'
import type { PostDetail } from '../../api/post'
import { buildCreateBody, buildEditBody, emptyForm, formOf, minuteText, readPeriod, reconcileCover, type PostForm } from './form'

const detail = (patch: Partial<PostDetail> = {}): PostDetail => ({
  id: 1, boardId: 2, title: '제목', content: '<p>본문</p>', useYn: true, pinned: false, viewCount: 0, status: 'ACTIVE', ...patch,
})
const editor = (changed = false, html = '<p>새 본문</p>') => ({ getHtml: () => html, isChanged: () => changed })
const form = (patch: Partial<PostForm> = {}): PostForm => ({ ...emptyForm(), title: '제목', ...patch })

describe('기간 읽기', () => {
  it('날짜가 비면 null, 시·분이 비면 00:00이다', () => {
    expect(readPeriod(form({ start: { date: '2026-01-02', hour: '', minute: '' } }))).toEqual({ start: '2026-01-02T00:00', end: null })
  })

  it('시·분만 고르고 날짜를 비우면 그쪽 이름으로 오류다', () => {
    expect(() => readPeriod(form({ start: { date: '', hour: '09', minute: '' } }))).toThrow('노출 시작 날짜를 선택해 주세요.')
    expect(() => readPeriod(form({ end: { date: '', hour: '', minute: '05' } }))).toThrow('노출 종료 날짜를 선택해 주세요.')
  })

  it('서버 시각은 분까지만 쓴다(키가 없으면 빈 값)', () => {
    expect(minuteText('2026-01-02T03:04:59')).toBe('2026-01-02 03:04')
    expect(minuteText(undefined)).toBe('')
    expect(formOf(detail({ publishStart: '2026-01-02T03:04:00' })).start).toEqual({ date: '2026-01-02', hour: '03', minute: '04' })
    expect(formOf(detail()).end).toEqual({ date: '', hour: '', minute: '' })
  })
})

describe('생성 본문', () => {
  it('고르지 않은 고정·대표·기간은 키가 없다. 제목은 다듬는다', () => {
    expect(buildCreateBody(form({ title: '  글 ' }), editor(), true)).toEqual({ title: '글', content: '<p>새 본문</p>', contentFormat: 'HTML', useYn: true })
  })

  it('고른 값만 싣는다', () => {
    const body = buildCreateBody(form({ pinned: true, cover: 7, end: { date: '2026-01-02', hour: '03', minute: '' } }), editor(), true)
    expect(body.pinned).toBe(true)
    expect(body.coverImageId).toBe(7)
    expect(body.publishPeriod).toEqual({ start: null, end: '2026-01-02T03:00' })
  })

  it('예약을 끈 게시판은 입력이 남아 있어도 기간을 보내지 않는다', () => {
    expect('publishPeriod' in buildCreateBody(form({ start: { date: '2026-01-02', hour: '', minute: '' } }), editor(), false)).toBe(false)
  })
})

describe('수정 본문', () => {
  it('바뀐 것이 없으면 빈 객체다', () => {
    const d = detail({ publishStart: '2026-01-02T03:04:30', coverImageId: 5, pinned: true })
    expect(buildEditBody(formOf(d), d, editor(), true)).toEqual({})
  })

  it('본문은 편집기가 바뀌었을 때만 형식 표식과 함께 싣는다', () => {
    expect(buildEditBody(formOf(detail()), detail(), editor(true), true)).toEqual({ content: '<p>새 본문</p>', contentFormat: 'HTML' })
  })

  it('대표는 사용자가 직접 바꾼 경우에만 싣는다 — 직접 없음은 명시 null, 자동 없음은 키 없음', () => {
    const d = detail({ coverImageId: 5 })
    expect(buildEditBody({ ...formOf(d), cover: null, coverUserChanged: true }, d, editor(), true)).toEqual({ coverImageId: null })
    expect(buildEditBody({ ...formOf(d), cover: null }, d, editor(), true)).toEqual({})
    expect(buildEditBody({ ...formOf(d), coverUserChanged: true }, d, editor(), true)).toEqual({})
    expect(buildEditBody({ ...formOf(detail()), cover: 9, coverUserChanged: true }, detail(), editor(), true)).toEqual({ coverImageId: 9 })
  })

  it('기간은 한쪽만 바뀌어도 통째로 싣고, 둘 다 비우면 둘 다 null이다', () => {
    const d = detail({ publishStart: '2026-01-02T03:04:00' })
    expect(buildEditBody({ ...formOf(d), end: { date: '2026-02-01', hour: '', minute: '' } }, d, editor(), true))
      .toEqual({ publishPeriod: { start: '2026-01-02T03:04', end: '2026-02-01T00:00' } })
    expect(buildEditBody({ ...formOf(d), start: { date: '', hour: '', minute: '' } }, d, editor(), true))
      .toEqual({ publishPeriod: { start: null, end: null } })
  })

  it('예약을 끈 게시판은 해제를 골랐을 때만 비운 기간을 싣는다', () => {
    const d = detail({ publishEnd: '2026-01-02T03:04:00' })
    expect(buildEditBody(formOf(d), d, editor(), false)).toEqual({})
    expect(buildEditBody({ ...formOf(d), periodClear: true }, d, editor(), false)).toEqual({ publishPeriod: { start: null, end: null } })
  })

  it('고정은 값이 바뀐 경우에만 싣는다', () => {
    const d = detail({ pinned: true })
    expect(buildEditBody({ ...formOf(d), pinned: false }, d, editor(), true)).toEqual({ pinned: false })
  })
})

describe('대표 선택 정리', () => {
  it('고른 이미지가 본문에서 사라지면 없음이 되고 사용자 선택 표시는 그대로다', () => {
    expect(reconcileCover({ ...form(), cover: 3, coverUserChanged: true }, [1])).toMatchObject({ cover: null, coverUserChanged: true })
    const same = form({ cover: 1 })
    expect(reconcileCover(same, [1])).toBe(same)
  })
})
