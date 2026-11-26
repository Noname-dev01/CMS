import type { DropPosition } from './tree'

/** 행의 위 1/4 = 앞, 아래 1/4 = 뒤, 가운데 = 안. */
export function dropPosition(rowTop: number, rowHeight: number, y: number): DropPosition {
  const ratio = (y - rowTop) / rowHeight
  if (ratio < 0.25) return 'before'
  return ratio >= 0.75 ? 'after' : 'inside'
}
