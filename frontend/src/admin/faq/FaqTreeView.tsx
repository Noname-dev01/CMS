/**
 * 카테고리 카드·FAQ 행 목록. 드래그 이벤트는 옛 화면처럼 목록 요소 하나(#faqTree)에서 위임으로 받는다.
 * 이름·질문은 사용자 입력이라 JSX 텍스트로만 넣는다(React가 이스케이프한다 — HTML로 해석하지 않는다).
 */
import type { DragEvent } from 'react'
import { scopeLabel, type ScopeSite } from '../shared/siteScope'
import type { View } from './FaqManage'

export interface DropMark {
  kind: 'category' | 'faq'
  /** category면 카드의 카테고리 ID, faq면 행이 속한 카테고리 ID */
  cat: number
  index: number
  position: 'before' | 'after'
}

interface Props {
  view: View
  sites: ScopeSite[]
  canUpdate: boolean
  interactionLocked: boolean
  onCategoryClick: (id: number) => void
  onFaqClick: (id: number) => void
  onMoveCategory: (from: number, to: number) => void
  onMoveFaq: (categoryId: number, from: number, to: number) => void
  onDragStart: (event: DragEvent) => void
  onDragOver: (event: DragEvent) => void
  onDrop: (event: DragEvent) => void
  onDragEnd: (event: DragEvent) => void
}

function dropClass(mark: DropMark | null, kind: DropMark['kind'], cat: number, index: number): string {
  if (!mark || mark.kind !== kind || mark.index !== index || mark.cat !== cat) return ''
  return mark.position === 'before' ? ' faq-drop-before' : ' faq-drop-after'
}

function Handle({ kind, cat, index, canUpdate, locked }: { kind: 'category' | 'faq'; cat: number; index: number; canUpdate: boolean; locked: boolean }) {
  const usable = canUpdate && !locked
  return (
    <span
      className="faq-handle"
      data-handle={kind}
      data-cat={String(cat)}
      data-index={String(index)}
      draggable={usable}
      aria-disabled={usable ? 'false' : 'true'}
      title={canUpdate ? '끌어서 순서 변경' : '순서를 바꿀 권한이 없습니다'}
    >⠿</span>
  )
}

function MoveButton({ label, glyph, disabled, onClick }: { label: string; glyph: string; disabled: boolean; onClick: () => void }) {
  return (
    <button type="button" className="btn btn-sm btn-outline-secondary faq-move-btn" aria-label={label} disabled={disabled} onClick={onClick}>{glyph}</button>
  )
}

export function FaqTreeView(props: Props) {
  const { view, sites, canUpdate, interactionLocked } = props
  const server = view.server
  const locked = interactionLocked || !canUpdate

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
      return (
        <div
          key={category.id}
          className={'faq-cat' + (view.editingCategoryId === category.id ? ' is-editing' : '') + dropClass(view.dropMark, 'category', category.id, catIndex)}
          data-drop="category"
          data-index={String(catIndex)}
          data-cat-id={String(category.id)}
        >
          <div className="faq-cat-head">
            <Handle kind="category" cat={category.id} index={catIndex} canUpdate={canUpdate} locked={interactionLocked} />
            <span className="faq-cat-name" onClick={() => props.onCategoryClick(category.id)}>{category.name}</span>
            <span className={'badge ' + (common ? 'badge-light border' : 'badge-info') + ' mr-2'}>{scopeLabel(sites, view.scopeExtras, category.siteId)}</span>
            {!category.useYn && <span className="badge badge-warning mr-2">비노출</span>}
            <MoveButton label="카테고리 위로" glyph="▲" disabled={locked || catIndex === 0} onClick={() => props.onMoveCategory(catIndex, catIndex - 1)} />
            <MoveButton label="카테고리 아래로" glyph="▼" disabled={locked || catIndex === view.order.length - 1} onClick={() => props.onMoveCategory(catIndex, catIndex + 1)} />
          </div>
          {group.faqIds.length === 0 && <div className="faq-empty-cat">FAQ가 없습니다.</div>}
          {group.faqIds.map((faqId, faqIndex) => {
            const faq = faqs.get(faqId)
            if (!faq) return null
            return (
              <div
                key={faq.id}
                className={'faq-row' + (view.editingFaq !== null && view.editingFaq.id === faq.id ? ' is-editing' : '') + dropClass(view.dropMark, 'faq', category.id, faqIndex)}
                data-drop="faq"
                data-cat={String(category.id)}
                data-index={String(faqIndex)}
                data-faq-id={String(faq.id)}
              >
                <Handle kind="faq" cat={category.id} index={faqIndex} canUpdate={canUpdate} locked={interactionLocked} />
                <span className="faq-row-question" onClick={() => props.onFaqClick(faq.id)}>{faq.question}</span>
                {!faq.useYn
                  ? <span className="badge badge-warning mr-2">비노출</span>
                  : !category.useYn && <span className="badge badge-secondary mr-2">카테고리 숨김</span>}
                <MoveButton label="FAQ 위로" glyph="▲" disabled={locked || faqIndex === 0} onClick={() => props.onMoveFaq(category.id, faqIndex, faqIndex - 1)} />
                <MoveButton label="FAQ 아래로" glyph="▼" disabled={locked || faqIndex === group.faqIds.length - 1} onClick={() => props.onMoveFaq(category.id, faqIndex, faqIndex + 1)} />
              </div>
            )
          })}
        </div>
      )
    })
  }

  return (
    <div id="faqTree" onDragStart={props.onDragStart} onDragOver={props.onDragOver} onDrop={props.onDrop} onDragEnd={props.onDragEnd}>
      {content}
    </div>
  )
}
