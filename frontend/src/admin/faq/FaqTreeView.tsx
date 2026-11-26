/**
 * 카테고리 카드·FAQ 행 목록과 순서 이동(포인터 드래그 — 마우스·터치·펜, ▲▼ 버튼). 이동은 초안만 바꾸고 서버를 호출하지 않는다.
 * 이름·질문은 사용자 입력이라 JSX 텍스트로만 넣는다(React가 이스케이프한다 — HTML로 해석하지 않는다).
 *
 * 드래그는 메뉴 편집(MenuTree)과 같은 방식이다: 포인터 하나만 소유하고(dragReducer) 캡처 없이 window 리스너로 받아 대상을 좌표로 찾으며,
 * 놓을 때는 마지막에 표시한 대상을 쓴다. 놓기 규칙만 다르다 — 같은 종류(FAQ는 같은 카테고리)의 대상 위치로 들어간다.
 */
import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { scrollAt } from '../shared/dragScroll'
import { dragReducer, IDLE, type DragInput, type DragState } from '../shared/dragState'
import { scopeLabel, type ScopeSite } from '../shared/siteScope'
import type { View } from './FaqManage'
import { itemName, locate, type OrderItem } from './state'

/** 놓을 대상(끌고 있는 것과 같은 종류). 위로 끌면 대상 앞, 아래로 끌면 대상 뒤에 선을 그린다. */
interface DropMark {
  item: OrderItem
  position: 'before' | 'after'
}

type Step = 'up' | 'down'

interface Props {
  view: View
  sites: ScopeSite[]
  canUpdate: boolean
  interactionLocked: boolean
  onCategoryClick: (id: number) => void
  onFaqClick: (id: number) => void
  /** 드래그를 시작하는 순간의 재검사(최신 값). 확정할 때는 onMove가 다시 검사한다 */
  canMove: () => boolean
  onMove: (item: OrderItem, to: number) => void
}

function same(a: OrderItem | undefined, kind: OrderItem['kind'], id: number): boolean {
  return a !== undefined && a.kind === kind && a.id === id
}

function Handle({ canUpdate, usable, onPointerDown }: { canUpdate: boolean; usable: boolean; onPointerDown: (event: ReactPointerEvent) => void }) {
  return (
    <span
      className="faq-handle"
      aria-disabled={usable ? 'false' : 'true'}
      title={canUpdate ? '끌어서 순서 변경' : '순서를 바꿀 권한이 없습니다'}
      onPointerDown={onPointerDown}
    >⠿</span>
  )
}

/** 끝에 닿은 버튼을 disabled로 만들면 그 순간 포커스를 잃으므로 aria-disabled로 알리고 눌러도 무시한다(목록 잠금·권한 없음은 disabled). */
function MoveButton({ step, label, locked, atEnd, onClick }: { step: Step; label: string; locked: boolean; atEnd: boolean; onClick: () => void }) {
  return (
    <button type="button" className="btn btn-sm btn-outline-secondary faq-move-btn" data-step={step} aria-label={label}
      disabled={locked} aria-disabled={locked || atEnd} onClick={onClick}>{step === 'up' ? '▲' : '▼'}</button>
  )
}

export function FaqTreeView(props: Props) {
  const { view, sites, canUpdate, interactionLocked } = props
  const server = view.server
  const locked = interactionLocked || !canUpdate
  const usable = !locked

  const [mark, setMark] = useState<DropMark | null>(null)
  const [dragging, setDragging] = useState<OrderItem | null>(null)

  const root = useRef<HTMLDivElement>(null)
  const ghost = useRef<HTMLDivElement>(null)
  const drag = useRef<DragState<OrderItem>>(IDLE)
  const markRef = useRef<DropMark | null>(null)
  const frame = useRef(0)
  const detach = useRef<(() => void) | null>(null)
  // 이동을 그린 뒤 포커스를 되돌릴 버튼
  const refocus = useRef<{ item: OrderItem; step: Step } | null>(null)
  // window 리스너가 렌더링 시점 값을 붙잡지 않도록 최신 props를 ref로 읽는다
  const latest = useRef(props)
  latest.current = props

  const showMark = (next: DropMark | null) => {
    const current = markRef.current
    if (current?.item.id === next?.item.id && current?.position === next?.position) return
    markRef.current = next
    setMark(next)
  }

  /** 좌표 아래에서 놓을 수 있는 대상을 찾는다. 종류·카테고리가 다르거나 제자리면 표시가 없다. */
  const updateMark = (state: Extract<DragState<OrderItem>, { phase: 'dragging' }>) => {
    const { item } = state
    const el = document.elementFromPoint(state.x, state.y)?.closest<HTMLElement>(`[data-drop="${item.kind}"]`) ?? null
    const order = latest.current.view.order
    const from = locate(order, item)
    if (!el || !from || !root.current?.contains(el)) {
      showMark(null)
      return
    }
    const target: OrderItem = { kind: item.kind, id: Number(item.kind === 'faq' ? el.dataset.faqId : el.dataset.catId) }
    const to = locate(order, target)
    if (!to || target.id === item.id || (item.kind === 'faq' && to.cat !== from.cat)) {
      showMark(null)   // FAQ는 같은 카테고리 안에서만 끈다
      return
    }
    showMark({ item: target, position: to.index < from.index ? 'before' : 'after' })
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
    document.documentElement.classList.remove('faq-dragging')
    showMark(null)
    setDragging(null)
  }

  const dispatch = (input: DragInput<OrderItem>) => {
    const before = drag.current
    const next = dragReducer(before, input)
    if (next === before) return
    drag.current = next
    if (next.phase === 'idle') {
      const target = markRef.current
      finish()
      if (input.type === 'up' && before.phase === 'dragging' && target) {
        const to = locate(latest.current.view.order, target.item)
        if (to) latest.current.onMove(before.item, to.index)
      }
      return
    }
    if (next.phase === 'dragging') {
      if (before.phase !== 'dragging') {
        document.documentElement.classList.add('faq-dragging')
        setDragging(next.item)
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
    const onPointerMove = (e: PointerEvent) => dispatch({
      type: 'move', pointerId: e.pointerId, pointerType: e.pointerType, buttons: e.buttons,
      x: e.clientX, y: e.clientY, canDrag: latest.current.canMove(),
    })
    const onUp = (e: PointerEvent) => dispatch({ type: 'up', pointerId: e.pointerId })
    const onCancel = (e: PointerEvent) => dispatch({ type: 'pointercancel', pointerId: e.pointerId })
    const abort = () => dispatch({ type: 'abort' })
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') abort() }
    const onVisibility = () => { if (document.hidden) abort() }
    // 포인터가 멈춰 있어도 휠 등으로 스크롤되면 아래의 행이 바뀐다(scroll은 버블링하지 않아 캡처로 받는다)
    const onScroll = () => { if (drag.current.phase === 'dragging') updateMark(drag.current) }
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onCancel)
    window.addEventListener('blur', abort)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('keydown', onKey)
    document.addEventListener('visibilitychange', onVisibility)
    detach.current = () => {
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onCancel)
      window.removeEventListener('blur', abort)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('keydown', onKey)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }

  const onPointerDown = (event: ReactPointerEvent, item: OrderItem) => {
    if (drag.current.phase !== 'idle' || !latest.current.canMove()) return
    dispatch({ type: 'down', pointerId: event.pointerId, pointerType: event.pointerType, button: event.button, item, x: event.clientX, y: event.clientY })
    // 전이가 거부되면(오른쪽 버튼 등) 리스너를 달지 않는다
    if ((drag.current as DragState<OrderItem>).phase === 'pending') attach()
  }

  const onStep = (item: OrderItem, step: Step, atEnd: boolean) => {
    const at = locate(latest.current.view.order, item)
    if (atEnd || !at) return
    refocus.current = { item, step }
    latest.current.onMove(item, at.index + (step === 'up' ? -1 : 1))
  }

  // 순서가 바뀌면 React가 행의 DOM을 옮겨 포커스를 잃을 수 있다 — 누른 버튼으로 되돌린다
  useLayoutEffect(() => {
    const focus = refocus.current
    refocus.current = null
    if (!focus) return
    const selector = focus.item.kind === 'faq'
      ? `.faq-row[data-faq-id="${focus.item.id}"]`
      : `.faq-cat[data-cat-id="${focus.item.id}"] > .faq-cat-head`
    const line = root.current?.querySelector<HTMLElement>(selector)
    line?.scrollIntoView({ block: 'nearest' })
    line?.querySelector<HTMLElement>(`.faq-move-btn[data-step="${focus.step}"]`)?.focus({ preventScroll: true })
  }, [view.order])

  // 옮길 수 없는 상태가 되거나(재조회·요청 시작) 언마운트되면 진행 중인 드래그를 취소한다
  useEffect(() => {
    if (!usable) dispatch({ type: 'abort' })
  }, [usable])
  useEffect(() => () => {
    drag.current = IDLE
    cancelAnimationFrame(frame.current)
    detach.current?.()
    document.documentElement.classList.remove('faq-dragging')
  }, [])

  const rowClass = (kind: OrderItem['kind'], id: number) =>
    (same(mark?.item, kind, id) ? ' faq-drop-' + mark?.position : '') + (same(dragging ?? undefined, kind, id) ? ' is-dragging' : '')

  const moveButtons = (item: OrderItem, prefix: string, index: number, total: number) => (['up', 'down'] as const).map((step) => {
    const atEnd = step === 'up' ? index === 0 : index === total - 1
    return <MoveButton key={step} step={step} label={prefix + (step === 'up' ? ' 위로' : ' 아래로')} locked={locked} atEnd={atEnd} onClick={() => onStep(item, step, atEnd)} />
  })

  let content
  if (!server) {
    content = (
      <p className="text-muted mb-0">
        {view.initFailed ? '목록을 불러오지 못했습니다. [새로고침]을 눌러 다시 시도하세요.' : '불러오는 중...'}
      </p>
    )
  } else if (view.order.length === 0) {
    content = <p className="text-muted mb-0">등록된 카테고리가 없습니다. 오른쪽에서 카테고리를 먼저 등록하세요.</p>
  } else {
    const categories = new Map(server.categories.map((c) => [c.id, c]))
    const faqs = new Map(server.categories.flatMap((c) => c.faqs).map((f) => [f.id, f]))
    content = view.order.map((group, catIndex) => {
      const category = categories.get(group.id)
      if (!category) return null
      const common = category.siteId === null
      const categoryItem: OrderItem = { kind: 'category', id: category.id }
      return (
        <div
          key={category.id}
          className={'faq-cat' + (view.editingCategoryId === category.id ? ' is-editing' : '') + rowClass('category', category.id)}
          data-drop="category"
          data-cat-id={String(category.id)}
        >
          <div className="faq-cat-head">
            <Handle canUpdate={canUpdate} usable={usable} onPointerDown={(event) => onPointerDown(event, categoryItem)} />
            <span className="faq-cat-name" onClick={() => props.onCategoryClick(category.id)}>{category.name}</span>
            <span className={'badge ' + (common ? 'badge-light border' : 'badge-info') + ' mr-2'}>{scopeLabel(sites, view.scopeExtras, category.siteId)}</span>
            {!category.useYn && <span className="badge badge-warning mr-2">비노출</span>}
            {moveButtons(categoryItem, '카테고리', catIndex, view.order.length)}
          </div>
          {group.faqIds.length === 0 && <div className="faq-empty-cat">FAQ가 없습니다.</div>}
          {group.faqIds.map((faqId, faqIndex) => {
            const faq = faqs.get(faqId)
            if (!faq) return null
            const faqItem: OrderItem = { kind: 'faq', id: faq.id }
            return (
              <div
                key={faq.id}
                className={'faq-row' + (view.editingFaq !== null && view.editingFaq.id === faq.id ? ' is-editing' : '') + rowClass('faq', faq.id)}
                data-drop="faq"
                data-cat={String(category.id)}
                data-faq-id={String(faq.id)}
              >
                <Handle canUpdate={canUpdate} usable={usable} onPointerDown={(event) => onPointerDown(event, faqItem)} />
                <span className="faq-row-question" onClick={() => props.onFaqClick(faq.id)}>{faq.question}</span>
                {!faq.useYn
                  ? <span className="badge badge-warning mr-2">비노출</span>
                  : !category.useYn && <span className="badge badge-secondary mr-2">카테고리 숨김</span>}
                {moveButtons(faqItem, 'FAQ', faqIndex, group.faqIds.length)}
              </div>
            )
          })}
        </div>
      )
    })
  }

  return (
    <div id="faqTree" ref={root}>
      {content}
      <div ref={ghost} className={'faq-drag-ghost' + (dragging === null ? ' d-none' : '')} aria-hidden="true">
        {dragging !== null && server ? itemName(server, dragging) : ''}
      </div>
    </div>
  )
}
