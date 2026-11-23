/** 포인터 드래그의 순수 상태 전이(PLAN-react-admin-menu.md 결정 1). 포인터 하나만 소유하고 다른 포인터의 이벤트는 무시한다. */
import type { DropPosition } from './tree'

/** 이만큼 넘게 움직여야 드래그다(그 전에 놓으면 평범한 클릭). */
const THRESHOLD = 5

export type DragState =
  | { phase: 'idle' }
  /** x·y는 누른 자리 */
  | { phase: 'pending'; pointerId: number; menuNo: number; x: number; y: number }
  /** x·y는 마지막 포인터 자리 */
  | { phase: 'dragging'; pointerId: number; menuNo: number; x: number; y: number }

export type DragInput =
  | { type: 'down'; pointerId: number; pointerType: string; button: number; menuNo: number; x: number; y: number }
  | { type: 'move'; pointerId: number; pointerType: string; buttons: number; x: number; y: number; canDrag: boolean }
  | { type: 'up'; pointerId: number }
  | { type: 'pointercancel'; pointerId: number }
  /** Escape·창 비활성화·문서 숨김·트리 교체·언마운트 */
  | { type: 'abort' }

export const IDLE: DragState = { phase: 'idle' }

export function dragReducer(state: DragState, input: DragInput): DragState {
  if (input.type === 'abort') return IDLE
  if (input.type === 'down') {
    if (state.phase !== 'idle' || (input.pointerType === 'mouse' && input.button !== 0)) return state
    return { phase: 'pending', pointerId: input.pointerId, menuNo: input.menuNo, x: input.x, y: input.y }
  }
  if (state.phase === 'idle' || input.pointerId !== state.pointerId) return state
  if (input.type !== 'move') return IDLE
  // 마우스인데 버튼이 떨어져 있다 — 창 밖에서 놓아 pointerup을 받지 못했다
  if (input.pointerType === 'mouse' && (input.buttons & 1) === 0) return IDLE
  if (state.phase === 'dragging') return { ...state, x: input.x, y: input.y }
  if (Math.hypot(input.x - state.x, input.y - state.y) <= THRESHOLD) return state
  return input.canDrag ? { ...state, phase: 'dragging', x: input.x, y: input.y } : IDLE
}

/** 행의 위 1/4 = 앞, 아래 1/4 = 뒤, 가운데 = 안. */
export function dropPosition(rowTop: number, rowHeight: number, y: number): DropPosition {
  const ratio = (y - rowTop) / rowHeight
  if (ratio < 0.25) return 'before'
  return ratio >= 0.75 ? 'after' : 'inside'
}
