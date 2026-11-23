/** 저장 전 메뉴 URL 안내. 서버 SafeUrls(@SafeMenuUrl)·SiteLinkPolicy가 최종 판정한다 — 정규식은 SafeUrls와 함께 바꾼다. */
import type { Site } from '../../api/site'

const SAME_ORIGIN_PATH = /^\/(?![\/\\])[^\s\\\x00-\x1f\x7f]*$/
const EXTERNAL_HTTP_URL = /^https?:\/\/[^\s\\\x00-\x1f\x7f\/?#@]+(?:[\/?#][^\s\\\x00-\x1f\x7f]*)?$/i

export function allowsExternal(site: Site | null): boolean {
  return site !== null && site.siteType === 'PUBLIC' && site.allowExternalLink === true
}

/** 선택한 사이트의 링크 정책으로 만든 안내 문구. null이면 통과. */
export function menuUrlError(url: string, site: Site | null): string | null {
  if (url === '' || SAME_ORIGIN_PATH.test(url)) return null
  if (EXTERNAL_HTTP_URL.test(url)) {
    return allowsExternal(site) ? null : '이 사이트의 메뉴에는 외부 링크를 사용할 수 없습니다. /로 시작하는 내부 경로를 입력하세요.'
  }
  return allowsExternal(site)
    ? '메뉴 URL은 /로 시작하는 내부 경로 또는 http(s):// 주소여야 합니다.'
    : '메뉴 URL은 /로 시작하는 사이트 내부 경로여야 합니다.'
}
