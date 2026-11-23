/**
 * 게시글 폼 모델과 요청 본문(순수). 수정은 바뀐 필드만 보낸다 — 낡은 화면이 다른 관리자가 바꾼 대표·기간·고정을 되돌리지 않게 하는 계약이다.
 */
import type { PostCreateBody, PostDetail, PostUpdateBody, PublishPeriod } from '../../api/post'

/** 날짜(date)와 시·분(select)을 나눠 받는다. 비면 ''. */
export interface DateTimeInput {
  date: string
  hour: string
  minute: string
}

export interface PostForm {
  title: string
  useYn: boolean
  pinned: boolean
  start: DateTimeInput
  end: DateTimeInput
  /** 예약을 끈 게시판에서 [기간 해제]를 골랐는가 */
  periodClear: boolean
  /** 지금 선택된 대표 이미지(없음 = null) */
  cover: number | null
  /** 사용자가 직접 고른 적이 있는가 — 본문에서 지워 자동으로 없음이 된 것은 사용자 선택이 아니다 */
  coverUserChanged: boolean
}

/** 본문은 편집기가 소유한다. */
export interface BodySource {
  getHtml(): string
  /** 편집기를 채운 뒤 바뀌었는가(서버 정리본과 편집기 출력은 표기가 달라 직접 비교할 수 없다) */
  isChanged(): boolean
}

const EMPTY_TIME: DateTimeInput = { date: '', hour: '', minute: '' }

export function emptyForm(): PostForm {
  return { title: '', useYn: true, pinned: false, start: EMPTY_TIME, end: EMPTY_TIME, periodClear: false, cover: null, coverUserChanged: false }
}

/** 서버 값("yyyy-MM-ddTHH:mm:ss") → "yyyy-MM-dd HH:mm". 없으면 "". */
export function minuteText(value: string | null | undefined): string {
  return value ? value.replace('T', ' ').substring(0, 16) : ''
}

function timeInput(value: string | undefined): DateTimeInput {
  const text = minuteText(value)
  return text === '' ? EMPTY_TIME : { date: text.substring(0, 10), hour: text.substring(11, 13), minute: text.substring(14, 16) }
}

export function formOf(detail: PostDetail): PostForm {
  return {
    title: detail.title || '',
    useYn: !!detail.useYn,
    pinned: !!detail.pinned,
    start: timeInput(detail.publishStart),
    end: timeInput(detail.publishEnd),
    periodClear: false,
    cover: detail.coverImageId ?? null,
    coverUserChanged: false,
  }
}

function readTime(input: DateTimeInput, label: string): string | null {
  if (input.date === '') {
    if (input.hour !== '' || input.minute !== '') {
      // 조용히 무시하면 기간이 의도와 다르게 저장된다
      throw new Error(label + ' 날짜를 선택해 주세요.')
    }
    return null
  }
  return input.date + 'T' + (input.hour || '00') + ':' + (input.minute || '00')
}

export function readPeriod(form: PostForm): PublishPeriod {
  return { start: readTime(form.start, '노출 시작'), end: readTime(form.end, '노출 종료') }
}

export function buildCreateBody(form: PostForm, editor: BodySource, periodAllowed: boolean): PostCreateBody {
  const body: PostCreateBody = { title: form.title.trim(), content: editor.getHtml(), contentFormat: 'HTML', useYn: form.useYn }
  if (form.pinned) {
    body.pinned = true
  }
  if (form.cover !== null) {
    body.coverImageId = form.cover
  }
  const period = periodAllowed ? readPeriod(form) : { start: null, end: null }
  if (period.start !== null || period.end !== null) {
    body.publishPeriod = period
  }
  return body
}

export function buildEditBody(form: PostForm, detail: PostDetail, editor: BodySource, periodAllowed: boolean): PostUpdateBody {
  const body: PostUpdateBody = {}
  const title = form.title.trim()
  if (title !== (detail.title || '')) {
    body.title = title
  }
  if (editor.isChanged()) {
    body.content = editor.getHtml()
    body.contentFormat = 'HTML'
  }
  if (form.useYn !== !!detail.useYn) {
    body.useYn = form.useYn
  }
  if (form.pinned !== !!detail.pinned) {
    body.pinned = form.pinned
  }
  if (form.coverUserChanged && form.cover !== (detail.coverImageId ?? null)) {
    body.coverImageId = form.cover
  }
  if (periodAllowed) {
    const period = readPeriod(form)
    if (minuteText(period.start) !== minuteText(detail.publishStart) || minuteText(period.end) !== minuteText(detail.publishEnd)) {
      body.publishPeriod = period
    }
  } else if (form.periodClear) {
    body.publishPeriod = { start: null, end: null }
  }
  return body
}

/** 고른 대표가 본문에서 사라지면 없음으로 바꾼다(서버의 자동 해제와 같은 결과). 그대로면 같은 객체. */
export function reconcileCover(form: PostForm, imageIds: number[]): PostForm {
  return form.cover !== null && !imageIds.includes(form.cover) ? { ...form, cover: null } : form
}
