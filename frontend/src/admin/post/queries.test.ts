import { onlineManager, QueryObserver } from '@tanstack/react-query'
import { afterEach, describe, expect, it } from 'vitest'
import { createPostQueryClient, refresh } from './queries'

interface Call {
  signal: AbortSignal
  resolve: (value: string) => void
  reject: (error: Error) => void
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 5))

function observe(client = createPostQueryClient()) {
  const calls: Call[] = []
  const observer = new QueryObserver<string>(client, {
    queryKey: ['posts'],
    queryFn: ({ signal }) => new Promise((resolve, reject) => calls.push({ signal, resolve, reject })),
  })
  const stop = observer.subscribe(() => {})
  return { client, calls, observer, stop }
}

describe('게시글 화면의 조회 규칙', () => {
  afterEach(() => onlineManager.setOnline(true))

  it('첫 조회가 진행 중이어도 refresh는 새 요청을 보내고, 늦게 온 이전 응답을 버린다', async () => {
    const { client, calls, observer, stop } = observe()
    await tick()
    void refresh(client, ['posts'])
    await tick()
    expect(calls).toHaveLength(2)
    expect(calls[0]?.signal.aborted).toBe(true)
    calls[1]?.resolve('저장 후')
    await tick()
    calls[0]?.resolve('저장 전')
    await tick()
    expect(observer.getCurrentResult().data).toBe('저장 후')
    stop()
    client.clear()
  })

  it('이미 데이터가 있어도 refresh는 다시 읽는다', async () => {
    const { client, calls, observer, stop } = observe()
    await tick()
    calls[0]?.resolve('처음')
    await tick()
    void refresh(client, ['posts'])
    await tick()
    expect(observer.getCurrentResult().isFetching).toBe(true)
    calls[1]?.resolve('다음')
    await tick()
    expect(observer.getCurrentResult().data).toBe('다음')
    stop()
    client.clear()
  })

  it('다시 시도하지 않고 바로 실패한다', async () => {
    const { client, calls, observer, stop } = observe()
    await tick()
    calls[0]?.reject(new Error('실패'))
    await tick()
    expect(calls).toHaveLength(1)
    expect(observer.getCurrentResult().isError).toBe(true)
    stop()
    client.clear()
  })

  it('오프라인으로 보여도 요청을 멈추지 않는다(실패는 네트워크 안내로 이어진다)', async () => {
    onlineManager.setOnline(false)
    const { client, calls, stop } = observe()
    await tick()
    expect(calls).toHaveLength(1)
    stop()
    client.clear()
  })

  it('구독이 끝나면 요청을 취소하고 결과를 보관하지 않는다', async () => {
    const { client, calls, stop } = observe()
    await tick()
    stop()
    await tick()
    expect(calls[0]?.signal.aborted).toBe(true)
    expect(client.getQueryCache().getAll()).toHaveLength(0)
  })

  it('같은 키를 다시 보면 보관한 값이 아니라 새로 읽는다', async () => {
    const { client, calls, stop } = observe()
    await tick()
    calls[0]?.resolve('처음')
    await tick()
    const again = observe(client)
    await tick()
    expect(again.calls).toHaveLength(1)
    stop()
    again.stop()
    client.clear()
  })
})
