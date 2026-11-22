/**
 * 관리자 API용 fetch 래퍼. 화면·React·DOM 전역에 의존하지 않는다(새 프로젝트·모바일 재사용 대상 — PLAN-react-admin-faq.md 결정 5).
 *
 * 반환 계약: 예외를 던지지 않고 "응답 헤더를 받았는가"로 나눈다. 기존 화면의 쓰기 판정(2xx면 본문을 읽지 못해도 성공 →
 * 목록 재조회, 헤더 전 실패면 결과 불명)을 그대로 지키기 위해서다.
 */
export type HttpResult =
  | { kind: 'network' }
  | { kind: 'http'; status: number; ok: boolean; body: unknown; bodyError: boolean }

export type HttpResponse = Extract<HttpResult, { kind: 'http' }>

export interface CsrfToken {
  header: string
  token: string
}

/** 요청할 때마다 부른다 — 토큰을 붙잡아 두지 않는다. */
export type CsrfProvider = () => CsrfToken | null

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  json?: unknown
}

export interface HttpClient {
  request(url: string, options?: RequestOptions): Promise<HttpResult>
}

/**
 * @param csrf  쓰기 요청의 CSRF 토큰 제공자
 * @param fetchFn  요청할 때마다 fetch 구현을 돌려준다(기본: 그 시점의 전역 fetch — 실행 중 바꿔치기도 반영된다)
 */
export function createHttpClient(csrf: CsrfProvider, fetchFn: () => typeof fetch = () => globalThis.fetch): HttpClient {
  return {
    async request(url, options = {}) {
      const method = options.method ?? 'GET'
      const headers: Record<string, string> = {}
      if (method === 'GET') {
        headers.Accept = 'application/json'
      } else {
        if (options.json !== undefined) {
          headers['Content-Type'] = 'application/json'
        }
        const token = csrf()
        if (token) {
          headers[token.header] = token.token
        }
      }
      // GET은 method를 싣지 않는다(fetch 기본값) — 옛 화면과 같은 요청 모양(브라우저 시험의 fetch 가로채기가 이 모양으로 GET을 식별한다)
      const init: RequestInit = method === 'GET' ? { headers } : { method, headers }
      if (options.json !== undefined) {
        init.body = JSON.stringify(options.json)
      }

      let response: Response
      try {
        response = await fetchFn()(url, init)
      } catch {
        return { kind: 'network' }
      }
      if (response.status === 204) {
        return { kind: 'http', status: 204, ok: true, body: null, bodyError: false }
      }
      try {
        const body: unknown = await response.json()
        return { kind: 'http', status: response.status, ok: response.ok, body, bodyError: false }
      } catch {
        return { kind: 'http', status: response.status, ok: response.ok, body: null, bodyError: true }
      }
    },
  }
}

/** 실패 응답의 사용자 문구. 403은 메서드 보안 메시지가 영문이라 고정 문구를 쓴다. */
export function errorMessage(result: HttpResponse, fallback: string): string {
  if (result.status === 403) {
    return '이 작업을 수행할 권한이 없습니다.'
  }
  const body = result.body
  if (body !== null && typeof body === 'object' && 'message' in body && typeof body.message === 'string' && body.message !== '') {
    return body.message
  }
  return fallback
}
