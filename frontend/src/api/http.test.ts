import { describe, expect, it } from 'vitest'
import { createHttpClient, errorMessage, type CsrfToken } from './http'

type Call = { url: string; init: RequestInit }

function stubFetch(respond: (call: Call) => Response | Promise<Response>) {
  const calls: Call[] = []
  const fetchFn = (async (url: string, init: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return respond(call)
  }) as unknown as typeof fetch
  return { calls, fetchFn }
}

const noCsrf = () => null

describe('createHttpClient', () => {
  it('2xx JSON 응답은 본문을 읽어 돌려준다', async () => {
    const { fetchFn } = stubFetch(() => new Response('{"id":7}', { status: 201 }))
    const http = createHttpClient(noCsrf, () => fetchFn)

    const result = await http.request('/x', { method: 'POST', json: {} })

    expect(result).toEqual({ kind: 'http', status: 201, ok: true, body: { id: 7 }, bodyError: false })
  })

  it('204는 본문을 읽지 않고 body = null로 성공이다', async () => {
    const { fetchFn } = stubFetch(() => new Response(null, { status: 204 }))
    const http = createHttpClient(noCsrf, () => fetchFn)

    const result = await http.request('/x', { method: 'DELETE' })

    expect(result).toEqual({ kind: 'http', status: 204, ok: true, body: null, bodyError: false })
  })

  it('2xx 뒤 본문을 읽지 못해도 HTTP 응답(성공)으로 돌려준다 — 네트워크 실패와 구분된다', async () => {
    const { fetchFn } = stubFetch(() => new Response('{"id":', { status: 200 }))
    const http = createHttpClient(noCsrf, () => fetchFn)

    const result = await http.request('/x', { method: 'PUT', json: {} })

    expect(result).toEqual({ kind: 'http', status: 200, ok: true, body: null, bodyError: true })
  })

  it('응답 헤더를 받기 전에 fetch가 실패하면 network다', async () => {
    const { fetchFn } = stubFetch(() => { throw new TypeError('Failed to fetch') })
    const http = createHttpClient(noCsrf, () => fetchFn)

    expect(await http.request('/x', { method: 'POST', json: {} })).toEqual({ kind: 'network' })
  })

  it('쓰기 요청은 호출 시점의 CSRF 토큰으로 헤더를 만든다', async () => {
    let token: CsrfToken = { header: 'X-CSRF-TOKEN', token: 'first' }
    const { calls, fetchFn } = stubFetch(() => new Response(null, { status: 204 }))
    const http = createHttpClient(() => token, () => fetchFn)

    await http.request('/a', { method: 'DELETE' })
    token = { header: 'X-XSRF', token: 'second' }
    await http.request('/b', { method: 'POST', json: { n: 1 } })

    expect(calls[0].init.headers).toEqual({ 'X-CSRF-TOKEN': 'first' })
    expect(calls[1].init.headers).toEqual({ 'Content-Type': 'application/json', 'X-XSRF': 'second' })
    expect(calls[1].init.body).toBe('{"n":1}')
  })

  it('GET은 method 없이(fetch 기본값) Accept JSON만 보내고 CSRF 헤더를 싣지 않는다', async () => {
    // 옛 화면과 같은 요청 모양이다 — 브라우저 시험의 fetch 가로채기가 "options.method가 없으면 GET"으로 상세 조회를 식별한다
    const { calls, fetchFn } = stubFetch(() => new Response('{}', { status: 200 }))
    const http = createHttpClient(() => ({ header: 'X-CSRF-TOKEN', token: 't' }), () => fetchFn)

    await http.request('/tree')

    expect(calls[0].init).toEqual({ headers: { Accept: 'application/json' } })
  })

  it('fetch 함수는 요청할 때마다 다시 찾는다 — 실행 중 바꿔치기한 fetch가 쓰인다', async () => {
    let current = stubFetch(() => new Response(null, { status: 204 })).fetchFn
    const http = createHttpClient(noCsrf, () => current)
    await http.request('/a', { method: 'DELETE' })

    const replaced = stubFetch(() => new Response(null, { status: 204 }))
    current = replaced.fetchFn
    await http.request('/b', { method: 'DELETE' })

    expect(replaced.calls.map((c) => c.url)).toEqual(['/b'])
  })

  it('PATCH도 쓰기 요청이다 — CSRF 헤더와 JSON 본문을 싣는다', async () => {
    const { calls, fetchFn } = stubFetch(() => new Response('{}', { status: 200 }))
    const http = createHttpClient(() => ({ header: 'X-CSRF-TOKEN', token: 't' }), () => fetchFn)

    await http.request('/x', { method: 'PATCH', json: { useYn: false } })

    expect(calls[0].init).toEqual({
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 't' },
      body: '{"useYn":false}',
    })
  })

  it('skipSuccessBody면 2xx 본문이 끝나지 않아도 헤더만으로 성공을 돌려준다', async () => {
    const { fetchFn } = stubFetch(() => new Response(new ReadableStream(), { status: 201 }))
    const http = createHttpClient(noCsrf, () => fetchFn)

    const result = await http.request('/x', { method: 'POST', json: {}, skipSuccessBody: true })

    expect(result).toEqual({ kind: 'http', status: 201, ok: true, body: null, bodyError: false })
  })

  it('skipSuccessBody여도 실패 응답은 본문을 읽는다(오류 문구)', async () => {
    const { fetchFn } = stubFetch(() => new Response('{"message":"충돌"}', { status: 409 }))
    const http = createHttpClient(noCsrf, () => fetchFn)

    const result = await http.request('/x', { method: 'PUT', json: {}, skipSuccessBody: true })

    expect(result).toEqual({ kind: 'http', status: 409, ok: false, body: { message: '충돌' }, bodyError: false })
  })
})

describe('errorMessage', () => {
  it('403은 서버 메시지 대신 고정 한국어 문구다(메서드 보안 403의 message는 영문)', () => {
    expect(errorMessage({ kind: 'http', status: 403, ok: false, body: { message: 'Access Denied' }, bodyError: false }, '기본'))
      .toBe('이 작업을 수행할 권한이 없습니다.')
  })

  it('그 밖의 오류는 본문 message, 없으면 기본 문구다', () => {
    expect(errorMessage({ kind: 'http', status: 409, ok: false, body: { message: '낡은 화면' }, bodyError: false }, '기본')).toBe('낡은 화면')
    expect(errorMessage({ kind: 'http', status: 500, ok: false, body: null, bodyError: true }, '기본')).toBe('기본')
  })
})
