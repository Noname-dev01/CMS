/** 포인터 드래그의 순수 상태 전이(메뉴 편집·FAQ 순서 편집이 함께 쓴다 — PLAN-react-admin-menu.md 결정 1). 포인터 하나만 소유하고 다른 포인터의 이벤트는 무시한다. */

/** 이만큼 넘게 움직여야 드래그다(그 전에 놓으면 평범한 클릭). */
const THRESHOLD = 5

/** item은 끌고 있는 항목의 화면별 식별자다. 위치가 아니라 ID를 담는다(끄는 동안 목록이 바뀔 수 있다). */
export type DragState<T> =
  | { phase: 'idle' }
  /** x·y는 누른 자리 */
  | { phase: 'pending'; pointerId: number; item: T; x: number; y: number }
  /** x·y는 마지막 포인터 자리 */
  | { phase: 'dragging'; pointerId: number; item: T; x: number; y: number }

export type DragInput<T> =
  | { type: 'down'; pointerId: number; pointerType: string; button: number; item: T; x: number; y: number }
  | { type: 'move'; pointerId: number; pointerType: string; buttons: number; x: number; y: number; canDrag: boolean }
  | { type: 'up'; pointerId: number }
  | { type: 'pointercancel'; pointerId: number }
  /** Escape·창 비활성화·문서 숨김·트리 교체·언마운트 */
  | { type: 'abort' }

export const IDLE: DragState<never> = { phase: 'idle' }

export function dragReducer<T>(state: DragState<T>, input: DragInput<T>): DragState<T> {
  if (input.type === 'abort') return IDLE
  if (input.type === 'down') {
    if (state.phase !== 'idle' || (input.pointerType === 'mouse' && input.button !== 0)) return state
    return { phase: 'pending', pointerId: input.pointerId, item: input.item, x: input.x, y: input.y }
  }
  if (state.phase === 'idle' || input.pointerId !== state.pointerId) return state
  if (input.type !== 'move') return IDLE
  // 마우스인데 버튼이 떨어져 있다 — 창 밖에서 놓아 pointerup을 받지 못했다
  if (input.pointerType === 'mouse' && (input.buttons & 1) === 0) return IDLE
  if (state.phase === 'dragging') return { ...state, x: input.x, y: input.y }
  if (Math.hypot(input.x - state.x, input.y - state.y) <= THRESHOLD) return state
  return input.canDrag ? { ...state, phase: 'dragging', x: input.x, y: input.y } : IDLE
}
