import type { CsrfProvider } from '../../api/http'

/**
 * 관리자 head 조각(admin/fragments/head)이 렌더링한 CSRF 메타 태그를 요청할 때마다 읽는다(토큰을 붙잡아 두지 않는다).
 * 헤더 이름 메타가 비어 있으면 Spring Security 기본값 X-CSRF-TOKEN.
 */
export const metaCsrf: CsrfProvider = () => {
  const token = document.querySelector<HTMLMetaElement>('meta[name="_csrf"]')
  const header = document.querySelector<HTMLMetaElement>('meta[name="_csrf_header"]')
  if (!token || !header) {
    return null
  }
  return { header: header.content || 'X-CSRF-TOKEN', token: token.content }
}
