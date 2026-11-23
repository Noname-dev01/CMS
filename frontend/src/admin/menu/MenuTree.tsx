/**
 * 메뉴 트리와 이동(포인터 드래그 — PLAN-react-admin-menu.md 결정 1, 선택한 행의 이동 버튼 — 이슈 #302). 이동은 초안만 바꾸고 서버를 호출하지 않는다.
 *
 * - 포인터 하나만 소유하고(dragReducer) 캡처를 쓰지 않는다 — 누른 뒤 window 리스너로 받고 대상은 좌표로 찾는다.
 * - 표시(mark)와 놓은 결과가 같도록 놓을 때는 마지막에 표시한 대상을 쓴다. 스크롤되면(자동·휠) 마지막 좌표로 다시 계산한다.
 * - 트리가 교체되면 부모가 key를 바꿔 다시 마운트한다(접힘 상태 초기화 + 진행 중 드래그 취소).
 */
import { useEffect, useLayoutEffect, useRef, useState, type MouseEvent, type PointerEvent as ReactPointerEvent } from 'react'
import { dragReducer, dropPosition, IDLE, type DragInput, type DragState } from './dragState'
import { findNode, moveNode, stepTarget, type DropPosition, type MenuNode, type MoveStep, type MoveTarget } from './tree'

/** 스크롤 영역의 보이는 위·아래 가장자리에서 이 거리 안이면 자동 스크롤한다. */
const EDGE = 40
const SCROLL_STEP = 8

/**
 * 포인터가 가장자리에 있는 스크롤 영역을 안쪽부터 한 걸음 움직인다. 움직였으면 true. 이 화면에서 실제로 스크롤되는 것은 트리 상자가 아니라
 * #content-wrapper와 문서다(상자의 부모 높이가 auto라 상자는 내용만큼 커진다).
 */
function scrollAt(tree: HTMLElement | null, y: number): boolean {
  const areas: Element[] = []
  for (let el = tree?.parentElement ?? null; el; el = el.parentElement) {
    if (el === document.scrollingElement || /auto|scroll/.test(getComputedStyle(el).overflowY)) areas.push(el)
  }
  return areas.some((area) => {
    const rect = area.getBoundingClientRect()
    const isDocument = area === document.scrollingElement
    const top = isDocument ? 0 : Math.max(0, rect.top)
    const bottom = isDocument ? window.innerHeight : Math.min(window.innerHeight, rect.bottom)
    const step = y < top + EDGE ? -SCROLL_STEP : y > bottom - EDGE ? SCROLL_STEP : 0
    const before = area.scrollTop
    area.scrollTop += step
    return area.scrollTop !== before
  })
}

const MOVE_BUTTONS: { step: MoveStep; label: string; icon: string }[] = [
  { step: 'up', label: '위로', icon: 'fa-arrow-up' },
  { step: 'down', label: '아래로', icon: 'fa-arrow-down' },
  { step: 'out', label: '상위 메뉴 밖으로', icon: 'fa-arrow-left' },
  { step: 'in', label: '바로 위 메뉴 안으로', icon: 'fa-arrow-right' },
]

interface Props {
  nodes: MenuNode[]
  selected: number | null
  /** 지금 드래그할 수 있는가(렌더링용) */
  draggable: boolean
  /** 드래그를 시작·확정하는 순간의 재검사(최신 값) */
  canDrag: () => boolean
  onSelect: (menuNo: number) => void
  onMove: (menuNo: number, targetNo: number, position: DropPosition) => void
}

export function MenuTree(props: Props) {
  const { nodes, selected, draggable } = props
  const [collapsed, setCollapsed] = useState<ReadonlySet<number>>(new Set())
  const [mark, setMark] = useState<MoveTarget | null>(null)
  const [draggingNo, setDraggingNo] = useState<number | null>(null)

  const root = useRef<HTMLDivElement>(null)
  const ghost = useRef<HTMLDivElement>(null)
  const drag = useRef<DragState>(IDLE)
  const markRef = useRef<MoveTarget | null>(null)
  const frame = useRef(0)
  const detach = useRef<(() => void) | null>(null)
  // 드래그가 끝난 자리의 click이 선택으로 이어지지 않게 한다(다음 pointerdown에서 푼다)
  const suppressClick = useRef(false)
  // 이동을 그린 뒤 포커스를 둘 자리(step이 null이면 드래그 — 라벨)
  const refocus = useRef<{ menuNo: number; step: MoveStep | null } | null>(null)
  // window 리스너가 렌더링 시점 값을 붙잡지 않도록 최신 props를 ref로 읽는다
  const latest = useRef(props)
  latest.current = props

  const showMark = (next: MoveTarget | null) => {
    const current = markRef.current
    if (current?.menuNo === next?.menuNo && current?.position === next?.position) return
    markRef.current = next
    setMark(next)
  }

  /** 좌표 아래의 행에서 놓을 수 있는 위치를 찾는다. 허용되지 않거나 자리가 그대로면 표시가 없다. */
  const updateMark = (state: Extract<DragState, { phase: 'dragging' }>) => {
    const row = document.elementFromPoint(state.x, state.y)?.closest<HTMLElement>('.menu-node-row') ?? null
    if (!row || !root.current?.contains(row)) {
      showMark(null)
      return
    }
    const rect = row.getBoundingClientRect()
    const next: MoveTarget = { menuNo: Number(row.dataset.menuNo), position: dropPosition(rect.top, rect.height, state.y) }
    const tree = latest.current.nodes
    showMark(moveNode(tree, state.menuNo, next.menuNo, next.position) === tree ? null : next)
  }

  const autoScroll = () => {
    frame.current = 0
    const state = drag.current
    if (state.phase !== 'dragging' || !scrollAt(root.current, state.y)) return
    updateMark(state)
    frame.current = requestAnimationFrame(autoScroll)
  }

  const finish = () => {
    cancelAnimationFrame(frame.current)
    frame.current = 0
    detach.current?.()
    detach.current = null
    document.documentElement.classList.remove('menu-dragging')
    showMark(null)
    setDraggingNo(null)
  }

  const commitMove = (menuNo: number, target: MoveTarget, step: MoveStep | null) => {
    if (target.position === 'inside') {
      // 접힌 메뉴 안으로 옮기면 펼친다 — 옮긴 메뉴와 포커스가 화면에서 사라지지 않게 한다
      setCollapsed((current) => {
        const next = new Set(current)
        return next.delete(target.menuNo) ? next : current
      })
    }
    refocus.current = { menuNo, step }
    latest.current.onMove(menuNo, target.menuNo, target.position)
  }

  const onMoveButton = (menuNo: number, step: MoveStep) => {
    const target = latest.current.canDrag() ? stepTarget(latest.current.nodes, menuNo, step) : null
    if (target) commitMove(menuNo, target, step)
  }

  // 부모가 바뀌면 행이 새로 만들어지고 형제 순서가 바뀌면 DOM이 옮겨져 포커스를 잃을 수 있다.
  // 버튼은 같은 버튼으로, 드래그는 포커스를 잃었을 때만 라벨로 되돌린다.
  useLayoutEffect(() => {
    const focus = refocus.current
    refocus.current = null
    if (!focus) return
    const row = root.current?.querySelector<HTMLElement>(`.menu-node-row[data-menu-no="${focus.menuNo}"]`)
    if (!row) return
    if (focus.step === null) {
      if (document.activeElement === document.body) row.querySelector<HTMLElement>('.menu-node-label')?.focus({ preventScroll: true })
      return
    }
    row.scrollIntoView({ block: 'nearest' })
    row.querySelector<HTMLElement>(`.menu-move-btn[data-step="${focus.step}"]`)?.focus({ preventScroll: true })
  }, [nodes])

  const dispatch = (input: DragInput) => {
    const before = drag.current
    const next = dragReducer(before, input)
    if (next === before) return
    drag.current = next
    if (next.phase === 'idle') {
      const target = markRef.current
      finish()
      if (input.type === 'up' && before.phase === 'dragging' && target && latest.current.canDrag()) {
        commitMove(before.menuNo, target, null)
      }
      return
    }
    if (next.phase === 'dragging') {
      if (before.phase !== 'dragging') {
        suppressClick.current = true
        document.documentElement.classList.add('menu-dragging')
        setDraggingNo(next.menuNo)
      }
      if (ghost.current) {
        ghost.current.style.transform = `translate(${next.x + 12}px, ${next.y + 12}px)`
      }
      updateMark(next)
      if (frame.current === 0) {
        frame.current = requestAnimationFrame(autoScroll)
      }
    }
  }

  const attach = () => {
    const onMove = (e: PointerEvent) => dispatch({
      type: 'move', pointerId: e.pointerId, pointerType: e.pointerType, buttons: e.buttons,
      x: e.clientX, y: e.clientY, canDrag: latest.current.canDrag(),
    })
    const onUp = (e: PointerEvent) => dispatch({ type: 'up', pointerId: e.pointerId })
    const onCancel = (e: PointerEvent) => dispatch({ type: 'pointercancel', pointerId: e.pointerId })
    const abort = () => dispatch({ type: 'abort' })
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') abort() }
    const onVisibility = () => { if (document.hidden) abort() }
    // 포인터가 멈춰 있어도 휠 등으로 스크롤되면 아래의 행이 바뀐다(scroll은 버블링하지 않아 캡처로 받는다)
    const onScroll = () => { if (drag.current.phase === 'dragging') updateMark(drag.current) }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onCancel)
    window.addEventListener('blur', abort)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('keydown', onKey)
    document.addEventListener('visibilitychange', onVisibility)
    detach.current = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onCancel)
      window.removeEventListener('blur', abort)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('keydown', onKey)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }

  const onPointerDown = (event: ReactPointerEvent, menuNo: number) => {
    if (drag.current.phase !== 'idle') return
    suppressClick.current = false
    if (!latest.current.canDrag()) return
    dispatch({
      type: 'down', pointerId: event.pointerId, pointerType: event.pointerType, button: event.button,
      menuNo, x: event.clientX, y: event.clientY,
    })
    // 전이가 거부되면(오른쪽 버튼 등) 리스너를 달지 않는다
    if ((drag.current as DragState).phase === 'pending') attach()
  }

  const onClick = (event: MouseEvent, menuNo: number) => {
    // 드래그에서 이어진 click만 무시한다 — 키보드(Enter) click은 detail이 0이다
    if (suppressClick.current && event.detail !== 0) return
    props.onSelect(menuNo)
  }

  // 드래그할 수 없는 상태가 되거나(재조회·요청 시작) 언마운트되면 진행 중인 드래그를 취소한다
  useEffect(() => {
    if (!draggable) dispatch({ type: 'abort' })
  }, [draggable])
  useEffect(() => () => {
    drag.current = IDLE
    cancelAnimationFrame(frame.current)
    detach.current?.()
    document.documentElement.classList.remove('menu-dragging')
  }, [])

  const toggle = (menuNo: number) => {
    const next = new Set(collapsed)
    if (!next.delete(menuNo)) next.add(menuNo)
    setCollapsed(next)
  }

  const renderNodes = (list: MenuNode[]) => (
    <ul className="menu-tree-list">
      {list.map((node) => {
        const open = !collapsed.has(node.menuNo)
        const dropClass = mark?.menuNo === node.menuNo ? ' menu-drop-' + mark.position : ''
        return (
          <li key={node.menuNo} className="menu-node">
            <div className={'menu-node-row' + dropClass + (draggingNo === node.menuNo ? ' is-dragging' : '')} data-menu-no={node.menuNo}>
              {node.children.length > 0 ? (
                <button type="button" className="menu-node-toggle" aria-expanded={open}
                  aria-label={node.name + (open ? ' 접기' : ' 펼치기')} onClick={() => toggle(node.menuNo)}>
                  <i className={'fas fa-fw ' + (open ? 'fa-caret-down' : 'fa-caret-right')} aria-hidden="true" />
                </button>
              ) : <span className="menu-node-toggle" />}
              <button type="button"
                className={'menu-node-label' + (selected === node.menuNo ? ' is-selected' : '')
                  + (node.useYn ? '' : ' menu-inactive') + (draggable ? ' is-draggable' : '')}
                aria-current={selected === node.menuNo ? 'true' : undefined}
                onPointerDown={(event) => onPointerDown(event, node.menuNo)}
                onClick={(event) => onClick(event, node.menuNo)}>
                <i className="fas fa-folder" aria-hidden="true" />
                <span>{node.name}</span>
              </button>
              {draggable && selected === node.menuNo ? (
                <span className="menu-move-buttons">
                  {/* disabled로 만들면 끝에 닿는 순간 포커스를 잃으므로 aria-disabled로 알리고 눌러도 무시한다 */}
                  {MOVE_BUTTONS.map(({ step, label, icon }) => (
                    <button key={step} type="button" className="menu-move-btn" data-step={step} title={label}
                      aria-label={node.name + ' ' + label} aria-disabled={stepTarget(nodes, node.menuNo, step) === null}
                      onClick={() => onMoveButton(node.menuNo, step)}>
                      <i className={'fas fa-fw ' + icon} aria-hidden="true" />
                    </button>
                  ))}
                </span>
              ) : null}
            </div>
            {node.children.length > 0 && open ? renderNodes(node.children) : null}
          </li>
        )
      })}
    </ul>
  )

  return (
    <div id="menuTree" ref={root}>
      {renderNodes(nodes)}
      <div ref={ghost} className={'menu-drag-ghost' + (draggingNo === null ? ' d-none' : '')} aria-hidden="true">
        {draggingNo === null ? '' : findNode(nodes, draggingNo)?.name}
      </div>
    </div>
  )
}
