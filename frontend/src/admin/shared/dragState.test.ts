import { describe, expect, it } from 'vitest'
import { dragReducer, IDLE, type DragInput, type DragState } from './dragState'

type Input = DragInput<number>

const down = (over: Partial<Extract<Input, { type: 'down' }>> = {}): Input =>
  ({ type: 'down', pointerId: 1, pointerType: 'mouse', button: 0, item: 7, x: 10, y: 10, ...over })
const moveTo = (x: number, y: number, over: Partial<Extract<Input, { type: 'move' }>> = {}): Input =>
  ({ type: 'move', pointerId: 1, pointerType: 'mouse', buttons: 1, x, y, canDrag: true, ...over })
const run = (...inputs: Input[]): DragState<number> => inputs.reduce<DragState<number>>(dragReducer, IDLE)

describe('dragReducer', () => {
  it('누르고 5px 넘게 움직여야 드래그다', () => {
    expect(run(down()).phase).toBe('pending')
    expect(run(down(), moveTo(13, 14)).phase).toBe('pending')
    expect(run(down(), moveTo(10, 16))).toEqual({ phase: 'dragging', pointerId: 1, item: 7, x: 10, y: 16 })
  })

  it('마우스는 왼쪽 버튼만 시작한다', () => {
    expect(run(down({ button: 2 }))).toBe(IDLE)
  })

  it('시작하는 순간 드래그할 수 없는 상태면 취소한다', () => {
    expect(run(down(), moveTo(30, 30, { canDrag: false }))).toBe(IDLE)
  })

  it('진행 중에는 다른 포인터의 누름·이동·놓음·취소를 무시한다', () => {
    const dragging = run(down(), moveTo(30, 30))
    expect(dragReducer(dragging, down({ pointerId: 2, pointerType: 'touch', item: 8 }))).toBe(dragging)
    expect(dragReducer(dragging, moveTo(90, 90, { pointerId: 2, pointerType: 'touch', buttons: 0 }))).toBe(dragging)
    expect(dragReducer(dragging, { type: 'up', pointerId: 2 })).toBe(dragging)
    expect(dragReducer(dragging, { type: 'pointercancel', pointerId: 2 })).toBe(dragging)
    const pending = run(down())
    expect(dragReducer(pending, down({ pointerId: 2, item: 8 }))).toBe(pending)
  })

  it('마우스가 버튼 없이 움직이면(창 밖에서 놓음) 취소하고 새 드래그를 받는다', () => {
    const cancelled = run(down(), moveTo(30, 30), moveTo(40, 40, { buttons: 0 }))
    expect(cancelled).toBe(IDLE)
    expect(dragReducer(cancelled, down({ item: 9 })).phase).toBe('pending')
  })

  it('터치는 버튼 값으로 취소하지 않는다', () => {
    expect(run(down({ pointerType: 'touch' }), moveTo(30, 30, { pointerType: 'touch', buttons: 0 })).phase).toBe('dragging')
  })

  it('놓음·취소·중단은 idle로 돌아간다', () => {
    const dragging = run(down(), moveTo(30, 30))
    expect(dragReducer(dragging, { type: 'up', pointerId: 1 })).toBe(IDLE)
    expect(dragReducer(dragging, { type: 'pointercancel', pointerId: 1 })).toBe(IDLE)
    expect(dragReducer(dragging, { type: 'abort' })).toBe(IDLE)
  })
})
