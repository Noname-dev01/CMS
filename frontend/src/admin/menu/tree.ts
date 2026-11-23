/** 메뉴 트리의 순수 로직: 서버 트리 변환, 이동 허용 판정·이동 계산, 반영 대기 건수, 반영 요청. */
import type { MenuStructure, MenuTreeNode } from '../../api/menu'

/** 메뉴 최대 깊이(최상위=1). 서버 MenuService.MAX_MENU_DEPTH와 같다 — 서버가 최종 판정하고 화면은 UX 보조다. */
export const MAX_DEPTH = 3

export interface MenuNode {
  menuNo: number
  name: string
  useYn: boolean
  exposureLabel: string | null
  children: MenuNode[]
}

/** 트리 조회 시점의 구조. upMenuNo·ord는 반영 요청의 base, parentNo·index는 변경 건수의 기준이다. */
export interface BaseEntry {
  upMenuNo: number | null
  ord: number | null
  parentNo: number | null
  index: number
}

export type BaseStructure = ReadonlyMap<number, BaseEntry>
export type DropPosition = 'before' | 'after' | 'inside'
/** 버튼 이동 한 걸음: 같은 부모 안 위·아래, 상위 메뉴 밖으로, 바로 위 형제 안으로 */
export type MoveStep = 'up' | 'down' | 'out' | 'in'

export interface MoveTarget {
  menuNo: number
  position: DropPosition
}

export function toNodes(server: MenuTreeNode[]): MenuNode[] {
  return server.map((node) => ({
    menuNo: node.data.menuNo,
    name: node.text,
    useYn: node.data.useYn !== false,
    exposureLabel: node.data.exposureLabel ?? null,
    children: toNodes(node.children ?? []),
  }))
}

export function indexBase(server: MenuTreeNode[]): BaseStructure {
  const base = new Map<number, BaseEntry>()
  const walk = (nodes: MenuTreeNode[], parentNo: number | null) => {
    nodes.forEach((node, index) => {
      base.set(node.data.menuNo, { upMenuNo: node.data.upMenuNo ?? null, ord: node.data.ord ?? null, parentNo, index })
      walk(node.children ?? [], node.data.menuNo)
    })
  }
  walk(server, null)
  return base
}

interface Located {
  node: MenuNode
  parentNo: number | null
  /** 최상위 = 1 */
  depth: number
  index: number
}

function locate(nodes: MenuNode[], menuNo: number, parentNo: number | null = null, depth = 1): Located | null {
  for (const [index, node] of nodes.entries()) {
    const found = node.menuNo === menuNo ? { node, parentNo, depth, index } : locate(node.children, menuNo, node.menuNo, depth + 1)
    if (found) return found
  }
  return null
}

export function findNode(nodes: MenuNode[], menuNo: number): MenuNode | null {
  return locate(nodes, menuNo)?.node ?? null
}

function siblingsIn(nodes: MenuNode[], parentNo: number | null): MenuNode[] {
  return parentNo === null ? nodes : findNode(nodes, parentNo)?.children ?? []
}

function height(node: MenuNode): number {
  return 1 + Math.max(0, ...node.children.map(height))
}

/**
 * 같은 부모 안 재정렬은 깊이와 무관하게 허용한다(서버도 ord만 바뀐 행은 검증하지 않는다 — 기존 4단 메뉴). 부모가 바뀌면 순환(자기·자손 아래)과
 * 깊이(새 부모 깊이 + 이동 서브트리 높이)를 막는다. 비활성 부모 아래 활성 메뉴는 서버가 반영 때 400으로 판정한다.
 */
export function canMove(nodes: MenuNode[], menuNo: number, newParentNo: number | null): boolean {
  const source = locate(nodes, menuNo)
  if (!source) return false
  if (newParentNo === source.parentNo) return true
  if (newParentNo === null) return height(source.node) <= MAX_DEPTH
  if (locate([source.node], newParentNo)) return false
  const parent = locate(nodes, newParentNo)
  return parent !== null && parent.depth + height(source.node) <= MAX_DEPTH
}

/** 대상 행의 앞·뒤·안에 놓을 수 있는가(드래그 중 표시와 놓을 때의 검사가 같은 함수를 쓴다). */
export function canDrop(nodes: MenuNode[], menuNo: number, targetNo: number, position: DropPosition): boolean {
  const target = locate(nodes, targetNo)
  return menuNo !== targetNo && target !== null && canMove(nodes, menuNo, position === 'inside' ? targetNo : target.parentNo)
}

function remove(nodes: MenuNode[], menuNo: number): MenuNode[] {
  return nodes
    .filter((node) => node.menuNo !== menuNo)
    .map((node) => ({ ...node, children: remove(node.children, menuNo) }))
}

function insert(nodes: MenuNode[], moved: MenuNode, targetNo: number, position: DropPosition): MenuNode[] {
  return nodes.flatMap((node) => {
    if (node.menuNo !== targetNo) {
      return [{ ...node, children: insert(node.children, moved, targetNo, position) }]
    }
    if (position === 'inside') return [{ ...node, children: [...node.children, moved] }]
    return position === 'before' ? [moved, node] : [node, moved]
  })
}

/** 떼어 낸 뒤 대상을 다시 찾아 넣는다. 허용되지 않거나 자리가 그대로면 같은 배열을 돌려준다. 입력은 바꾸지 않는다. */
export function moveNode(nodes: MenuNode[], menuNo: number, targetNo: number, position: DropPosition): MenuNode[] {
  const source = locate(nodes, menuNo)
  if (!source || !canDrop(nodes, menuNo, targetNo, position)) return nodes
  const moved = insert(remove(nodes, menuNo), source.node, targetNo, position)
  const after = locate(moved, menuNo)
  return after?.parentNo === source.parentNo && after.index === source.index ? nodes : moved
}

/** 버튼 이동의 놓을 자리. 갈 곳이 없거나 드래그와 같은 규칙(moveNode → canMove)이 거부하면 null. */
export function stepTarget(nodes: MenuNode[], menuNo: number, step: MoveStep): MoveTarget | null {
  const source = locate(nodes, menuNo)
  if (!source) return null
  const siblings = siblingsIn(nodes, source.parentNo)
  const anchorNo = step === 'out' ? source.parentNo : siblings[source.index + (step === 'down' ? 1 : -1)]?.menuNo ?? null
  if (anchorNo === null) return null
  const position: DropPosition = step === 'up' ? 'before' : step === 'in' ? 'inside' : 'after'
  return moveNode(nodes, menuNo, anchorNo, position) === nodes ? null : { menuNo: anchorNo, position }
}

/** 이동 결과 안내(live region). */
export function moveSummary(nodes: MenuNode[], menuNo: number): string {
  const moved = locate(nodes, menuNo)
  if (!moved) return ''
  const parent = moved.parentNo === null ? null : findNode(nodes, moved.parentNo)
  const total = siblingsIn(nodes, moved.parentNo).length
  return `${moved.node.name}: ${parent === null ? '최상위' : parent.name + ' 아래'} ${moved.index + 1}번째로 이동(전체 ${total}개)`
}

interface FlatEntry {
  menuNo: number
  parentNo: number | null
}

function flatten(nodes: MenuNode[], parentNo: number | null = null): FlatEntry[] {
  return nodes.flatMap((node) => [{ menuNo: node.menuNo, parentNo }, ...flatten(node.children, node.menuNo)])
}

/** 가장 긴 증가 부분 수열의 길이 — 같은 부모 안에서 "자리를 지킨 메뉴" 수를 구하는 데 쓴다(O(n²), 메뉴는 소규모). */
function longestIncreasingLength(sequence: number[]): number {
  const best: number[] = []
  sequence.forEach((value, i) => {
    best[i] = 1 + Math.max(0, ...sequence.slice(0, i).map((prev, j) => (prev < value ? best[j] : 0)))
  })
  return Math.max(0, ...best)
}

/**
 * 반영 대기 건수 = 실제로 옮긴 메뉴 수: 부모가 바뀐 메뉴 + 같은 부모 안에서 상대 순서가 바뀐 메뉴. 한 메뉴를 옮기면 다른 형제의 위치가 전부
 * 밀리지만 그것까지 세면 부풀려지므로, 같은 부모에 남은 메뉴는 기준 위치의 최장 증가 부분 수열을 제외한 나머지만 센다.
 */
export function countDraftChanges(base: BaseStructure, nodes: MenuNode[]): number {
  let changed = 0
  const stayersByParent = new Map<number | null, number[]>()
  for (const entry of flatten(nodes)) {
    const before = base.get(entry.menuNo)
    if (!before || before.parentNo !== entry.parentNo) {
      changed++
    } else {
      stayersByParent.set(entry.parentNo, [...(stayersByParent.get(entry.parentNo) ?? []), before.index])
    }
  }
  for (const indexes of stayersByParent.values()) {
    changed += indexes.length - longestIncreasingLength(indexes)
  }
  return changed
}

/** 최종 구조: 전체 메뉴(비활성 포함), 같은 부모 안의 배열 순서가 새 순서. */
export function buildStructureRequest(siteId: number, base: BaseStructure, nodes: MenuNode[]): MenuStructure {
  return {
    siteId,
    menus: flatten(nodes).map(({ menuNo, parentNo }) => ({
      menuNo,
      baseUpMenuNo: base.get(menuNo)?.upMenuNo ?? null,
      baseOrd: base.get(menuNo)?.ord ?? null,
      upMenuNo: parentNo,
    })),
  }
}
