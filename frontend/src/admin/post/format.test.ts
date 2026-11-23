import { describe, expect, it } from 'vitest'
import { coverThumbUrl, fileSizeText, pageWindow, periodText, summaryPages } from './format'

describe('표시 형식', () => {
  it('기간', () => {
    expect(periodText(undefined, undefined)).toBe('제한 없음')
    expect(periodText('2026-01-02T03:04:00', undefined)).toBe('2026-01-02 03:04 ~ …')
    expect(periodText(undefined, '2026-01-02T03:04:00')).toBe('… ~ 2026-01-02 03:04')
  })

  it('파일 크기', () => {
    expect(fileSizeText(undefined)).toBe('-')
    expect(fileSizeText(1023)).toBe('1023 B')
    expect(fileSizeText(2048)).toBe('2.0 KB')
    expect(fileSizeText(1572864)).toBe('1.5 MB')
  })

  it('페이지 묶음은 현재 앞 2쪽부터 최대 5쪽이다', () => {
    expect(pageWindow(0, 10)).toEqual([0, 1, 2, 3, 4])
    expect(pageWindow(9, 10)).toEqual([7, 8, 9])
    expect(pageWindow(0, 1)).toEqual([])
  })

  it('요약은 글이 없으면 0 / 1 페이지다', () => {
    expect(summaryPages(0, 0)).toBe('0 / 1 페이지')
    expect(summaryPages(1, 3)).toBe('2 / 3 페이지')
  })

  it('대표 주소는 1 이상의 정수일 때만 만든다', () => {
    expect(coverThumbUrl(12)).toBe('/content-images/12?size=thumb')
    for (const bad of [undefined, 0, -1, 1.5]) expect(coverThumbUrl(bad)).toBeNull()
  })
})
