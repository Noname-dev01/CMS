import { describe, expect, it } from 'vitest'
import { escapeForConfirm } from './confirmText'
import { filterIcons, iconNameOf } from './icons'
import { allowsExternal, menuUrlError } from './menuUrl'

const site = (siteType: 'ADMIN' | 'PUBLIC', allowExternalLink: boolean) =>
  ({ id: 2, siteKey: 'k', siteName: 'n', siteType, allowExternalLink, useYn: true })

describe('escapeForConfirm', () => {
  it('평범한 이름은 그대로다', () => {
    expect(escapeForConfirm('회원 관리 A')).toBe('회원 관리 A')
  })

  it('역슬래시·개행·탭·제어문자·줄 구분자·보이지 않는 공백을 가역 이스케이프한다', () => {
    expect(escapeForConfirm('a\\b')).toBe('a\\\\b')
    expect(escapeForConfirm('a\r\n\tb')).toBe('a\\r\\n\\tb')
    expect(escapeForConfirm(String.fromCharCode(0x00, 0x7f, 0x9f))).toBe('\\u0000\\u007f\\u009f')
    expect(escapeForConfirm(String.fromCharCode(0x2028, 0x2029))).toBe('\\u2028\\u2029')
    expect(escapeForConfirm(String.fromCharCode(0xa0, 0xfeff))).toBe('\\u00a0\\ufeff')
  })
})

describe('menuUrlError', () => {
  const admin = site('ADMIN', true)
  const closed = site('PUBLIC', false)
  const open = site('PUBLIC', true)

  it('빈 값과 같은 출처 경로는 통과한다', () => {
    expect(menuUrlError('', closed)).toBeNull()
    expect(menuUrlError('/faq?a=1', closed)).toBeNull()
  })

  it('외부 주소는 외부 링크를 허용한 공개 사이트에서만 통과한다', () => {
    expect(allowsExternal(admin)).toBe(false)
    expect(allowsExternal(null)).toBe(false)
    expect(menuUrlError('https://example.com/a', open)).toBeNull()
    const message = '이 사이트의 메뉴에는 외부 링크를 사용할 수 없습니다. /로 시작하는 내부 경로를 입력하세요.'
    expect(menuUrlError('https://example.com', closed)).toBe(message)
    expect(menuUrlError('https://example.com', admin)).toBe(message)
  })

  it('그 밖의 형식은 사이트 정책에 맞는 문구로 거부한다', () => {
    for (const bad of ['//evil', '/\\evil', '/a b', 'javascript:alert(1)', 'faq']) {
      expect(menuUrlError(bad, closed)).toBe('메뉴 URL은 /로 시작하는 사이트 내부 경로여야 합니다.')
    }
    expect(menuUrlError('https://user@host', open)).toBe('메뉴 URL은 /로 시작하는 내부 경로 또는 http(s):// 주소여야 합니다.')
  })
})

describe('아이콘', () => {
  it('클래스 문자열에서 이름을 뽑는다(fa-fw 제외)', () => {
    expect(iconNameOf('fas fa-fw fa-users')).toBe('users')
    expect(iconNameOf('')).toBe('')
  })

  it('검색은 공백을 다듬고 대소문자를 가리지 않는 부분 일치다', () => {
    expect(filterIcons(' ROCKET ')).toEqual(['rocket'])
    expect(filterIcons('').length).toBe(98)
    expect(filterIcons('없는아이콘')).toEqual([])
  })
})
