/** 게시글 화면의 표시 형식(순수). */
import { minuteText } from './form'

export function periodText(start: string | undefined, end: string | undefined): string {
  if (!start && !end) {
    return '제한 없음'
  }
  return (minuteText(start) || '…') + ' ~ ' + (minuteText(end) || '…')
}

const DATE_FORMAT = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })

export function dateText(value: string | undefined): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : DATE_FORMAT.format(date)
}

export function fileSizeText(bytes: number | undefined): string {
  if (bytes === undefined || bytes === null) {
    return '-'
  }
  if (bytes < 1024) {
    return bytes + ' B'
  }
  if (bytes < 1024 * 1024) {
    return (bytes / 1024).toFixed(1) + ' KB'
  }
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

/** 페이지 버튼으로 보일 0부터의 쪽 번호. 한 쪽 이하면 비운다. */
export function pageWindow(page: number, totalPages: number): number[] {
  if (!totalPages || totalPages <= 1) {
    return []
  }
  const start = Math.max(0, page - 2)
  const end = Math.min(totalPages - 1, start + 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
}

export function summaryPages(page: number, totalPages: number): string {
  return (totalPages === 0 ? 0 : page + 1) + ' / ' + Math.max(totalPages, 1) + ' 페이지'
}

/** 보기 모드의 대표 이미지 주소. 정수가 아니면 만들지 않는다. */
export function coverThumbUrl(id: number | undefined): string | null {
  return id !== undefined && Number.isSafeInteger(id) && id >= 1 ? '/content-images/' + id + '?size=thumb' : null
}

export const STATUS_LABELS = new Map([['ACTIVE', '노출 중'], ['SCHEDULED', '예약됨'], ['EXPIRED', '만료'], ['HIDDEN', '비노출']])
const STATUS_CLASSES = new Map([
  ['ACTIVE', 'badge-status-active'], ['SCHEDULED', 'badge-status-scheduled'], ['EXPIRED', 'badge-status-expired'], ['HIDDEN', 'badge-status-disabled'],
])

export function statusBadge(status: string): { label: string; className: string } {
  return { label: STATUS_LABELS.get(status) ?? String(status || '-'), className: 'badge-status ' + (STATUS_CLASSES.get(status) ?? 'badge-status-disabled') }
}
