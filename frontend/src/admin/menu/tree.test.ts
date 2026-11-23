import { describe, expect, it } from 'vitest'
import type { MenuTreeNode } from '../../api/menu'
import { buildStructureRequest, canMove, countDraftChanges, findNode, indexBase, moveNode, toNodes, type DropPosition, type MenuNode } from './tree'

/** 서버 트리 노드. 최상위·레거시 행은 서버 JSON(non_null)에서 upMenuNo·ord 키가 생략된다. */
function server(menuNo: number, children: MenuTreeNode[] = [], data: Partial<MenuTreeNode['data']> = {}): MenuTreeNode {
  return { id: String(menuNo), text: 'm' + menuNo, data: { menuNo, useYn: true, ...data }, children }
}

/** 1 > (2 > (3)), 4, 5 — 깊이 3짜리 하나와 최상위 둘 */
const TREE: MenuTreeNode[] = [
  server(1, [server(2, [server(3, [], { upMenuNo: 2, ord: 0 })], { upMenuNo: 1, ord: 0 })], { ord: 0 }),
  server(4, [], { ord: 1 }),
  server(5, [], { ord: 2 }),
]

type Shape = number | [number, Shape[]]
const shape = (nodes: MenuNode[]): Shape[] => nodes.map((n) => (n.children.length === 0 ? n.menuNo : [n.menuNo, shape(n.children)]))
const flat = (count: number) => toNodes(Array.from({ length: count }, (_, i) => server(i + 1, [], { ord: i })))
const move = (nodes: MenuNode[], menuNo: number, target: number, position: DropPosition) => moveNode(nodes, menuNo, target, position)

describe('toNodes', () => {
  it('이름·사용 여부·노출 안내를 옮기고 children이 없으면 빈 배열이다', () => {
    const [node] = toNodes([{ id: '9', text: '<b>x</b>', data: { menuNo: 9, useYn: false, exposureLabel: '관리자 전용' } }])
    expect(node).toEqual({ menuNo: 9, name: '<b>x</b>', useYn: false, exposureLabel: '관리자 전용', children: [] })
    expect(findNode(toNodes(TREE), 3)?.name).toBe('m3')
    expect(findNode(toNodes(TREE), 99)).toBeNull()
  })
})

describe('canMove — 서버 구조 규칙과 같은 범위', () => {
  const nodes = toNodes(TREE)

  it('자기 자신·자손 아래로는 옮길 수 없다', () => {
    expect(canMove(nodes, 1, 1)).toBe(false)
    expect(canMove(nodes, 1, 3)).toBe(false)
    expect(canMove(nodes, 2, 3)).toBe(false)
  })

  it('부모가 바뀌면 새 부모 깊이 + 서브트리 높이가 3 이하여야 한다', () => {
    expect(canMove(nodes, 4, 2)).toBe(true)
    expect(canMove(nodes, 4, 3)).toBe(false)
    expect(canMove(nodes, 2, 4)).toBe(true)
    expect(canMove(nodes, 1, 4)).toBe(false)
    expect(canMove(nodes, 3, null)).toBe(true)
  })

  it('같은 부모 안 재정렬은 기존 4단 메뉴도 허용하고, 4단을 다른 부모로 옮겨 4단이 되는 것은 거부한다', () => {
    const deep = toNodes([server(1, [server(2, [server(3, [server(4), server(6)]), server(5)])])])
    expect(canMove(deep, 4, 3)).toBe(true)
    expect(canMove(deep, 4, 5)).toBe(false)
  })

  it('없는 메뉴·없는 부모는 거부한다', () => {
    expect(canMove(nodes, 99, null)).toBe(false)
    expect(canMove(nodes, 4, 99)).toBe(false)
  })
})

describe('moveNode', () => {
  it('같은 부모 안에서 아래로 옮겨도 한 칸 어긋나지 않는다', () => {
    expect(shape(move(flat(4), 1, 3, 'before'))).toEqual([2, 1, 3, 4])
    expect(shape(move(flat(4), 1, 3, 'after'))).toEqual([2, 3, 1, 4])
    expect(shape(move(flat(4), 1, 4, 'after'))).toEqual([2, 3, 4, 1])
  })

  it('같은 부모 안에서 위로 옮긴다', () => {
    expect(shape(move(flat(4), 4, 2, 'before'))).toEqual([1, 4, 2, 3])
    expect(shape(move(flat(4), 4, 2, 'after'))).toEqual([1, 2, 4, 3])
  })

  it('안으로 옮기면 마지막 자식이 되고, 서브트리는 통째로 움직인다', () => {
    expect(shape(move(toNodes(TREE), 4, 1, 'inside'))).toEqual([[1, [[2, [3]], 4]], 5])
    expect(shape(move(toNodes(TREE), 2, 5, 'inside'))).toEqual([1, 4, [5, [[2, [3]]]]])
    expect(shape(move(toNodes(TREE), 3, 4, 'before'))).toEqual([[1, [2]], 3, 4, 5])
  })

  it('자기 자신·이미 그 자리·허용되지 않는 이동은 같은 배열을 돌려준다(초안이 생기지 않는다)', () => {
    const nodes = toNodes(TREE)
    for (const position of ['before', 'after', 'inside'] as const) {
      expect(move(nodes, 4, 4, position)).toBe(nodes)
    }
    expect(move(nodes, 4, 5, 'before')).toBe(nodes)
    expect(move(nodes, 5, 4, 'after')).toBe(nodes)
    expect(move(nodes, 2, 1, 'inside')).toBe(nodes)
    expect(move(nodes, 1, 3, 'inside')).toBe(nodes)
    expect(move(nodes, 4, 3, 'inside')).toBe(nodes)
    expect(move(nodes, 1, 2, 'before')).toBe(nodes)
  })

  it('입력 트리를 바꾸지 않는다', () => {
    const nodes = toNodes(TREE)
    const before = JSON.stringify(nodes)
    move(nodes, 2, 5, 'inside')
    expect(JSON.stringify(nodes)).toBe(before)
  })
})

describe('countDraftChanges — 실제로 옮긴 메뉴 수', () => {
  it('한 메뉴를 옮기면 형제 위치가 밀려도 1건이다', () => {
    const tree = Array.from({ length: 5 }, (_, i) => server(i + 1, [], { ord: i }))
    const base = indexBase(tree)
    expect(countDraftChanges(base, toNodes(tree))).toBe(0)
    expect(countDraftChanges(base, move(toNodes(tree), 5, 1, 'before'))).toBe(1)
    expect(countDraftChanges(base, move(toNodes(tree), 1, 5, 'after'))).toBe(1)
  })

  it('부모가 바뀐 메뉴를 세고, 되돌리면 0건이다', () => {
    const base = indexBase(TREE)
    const moved = move(toNodes(TREE), 4, 1, 'inside')
    expect(countDraftChanges(base, moved)).toBe(1)
    expect(countDraftChanges(base, move(moved, 4, 5, 'before'))).toBe(0)
  })

  it('둘을 옮기면 2건이다', () => {
    const tree = Array.from({ length: 4 }, (_, i) => server(i + 1))
    const moved = move(move(toNodes(tree), 1, 4, 'after'), 2, 1, 'after')
    expect(shape(moved)).toEqual([3, 4, 1, 2])
    expect(countDraftChanges(indexBase(tree), moved)).toBe(2)
  })
})

describe('buildStructureRequest', () => {
  it('전체 메뉴를 깊이 우선으로 담고, 생략된 base 값은 null로 명시한다', () => {
    const tree = [server(1, [server(2, [], { upMenuNo: 1, ord: 100 })]), server(3, [], { ord: 7 })]
    const draft = move(toNodes(tree), 3, 1, 'inside')
    expect(buildStructureRequest(5, indexBase(tree), draft)).toEqual({
      siteId: 5,
      menus: [
        { menuNo: 1, baseUpMenuNo: null, baseOrd: null, upMenuNo: null },
        { menuNo: 2, baseUpMenuNo: 1, baseOrd: 100, upMenuNo: 1 },
        { menuNo: 3, baseUpMenuNo: null, baseOrd: 7, upMenuNo: 1 },
      ],
    })
  })
})
