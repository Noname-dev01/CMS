/**
 * 게시글 화면의 조회 규칙(PLAN-react-admin-posts.md 결정 2). 조회만 TanStack Query에 맡기고 쓰기는 화면이 직접 한다.
 * 옛 화면처럼 사용자가 일으킨 조회만 나가고(자동 재조회·재시도 없음), 다시 볼 때마다 새로 읽는다(보관하지 않음).
 */
import { QueryClient, type QueryKey } from '@tanstack/react-query'
import { errorMessage, type HttpResult } from '../../api/http'

export const FORBIDDEN_MESSAGE = '권한이 없습니다. 권한이 변경되었을 수 있으니 페이지를 새로고침해 주세요.'
export const NETWORK_MESSAGE = '네트워크 연결을 확인해주세요.'

export function createPostQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        staleTime: 0,
        gcTime: 0,
        // 기본값 'online'은 오프라인이면 요청을 멈춰 둔다 — 멈추지 않고 실패를 안내한다
        networkMode: 'always',
      },
    },
  })
}

/**
 * 지금 보는 조회를 다시 읽는다. invalidate만 부르면 <b>첫 조회가 진행 중일 때</b> 그 요청을 그대로 쓰므로(5.107.1 query.ts fetch)
 * 쓰기 전 응답이 화면에 남을 수 있다 — 먼저 취소한다.
 */
export async function refresh(client: QueryClient, queryKey: QueryKey): Promise<void> {
  await client.cancelQueries({ queryKey, exact: true })
  await client.invalidateQueries({ queryKey, exact: true })
}

/** 실패 응답을 사용자 문구로 바꾼다. */
export function failureMessage(result: HttpResult, fallback: string): string {
  return result.kind === 'network' ? NETWORK_MESSAGE : errorMessage(result, fallback, FORBIDDEN_MESSAGE)
}

/** 2xx(본문까지 읽음)가 아니면 사용자 문구로 던진다. */
export function bodyOrThrow<T>(result: HttpResult, fallback: string): T {
  if (result.kind === 'network' || !result.ok) {
    throw new Error(failureMessage(result, fallback))
  }
  if (result.bodyError) {
    throw new Error(fallback)
  }
  return result.body as T
}

/** 본문이 필요 없는 쓰기의 성공 여부. */
export function isOk(result: HttpResult): boolean {
  return result.kind === 'http' && result.ok
}
