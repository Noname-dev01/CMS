/**
 * 카테고리·FAQ 등록·수정 모달(한 모달에 두 폼, 한 번에 하나만 보인다). 네이티브 <dialog>이고 여닫기는 FaqManage가 showModal()·close()로 한다
 * (PLAN-dialog-faq-modal.md). open 속성은 브라우저가, data-state는 FaqManage가 직접 쓴다. 모양은 Bootstrap의 .modal 배치 + .admin-dialog(admin-manage.css).
 * 모달·두 폼·편집기 호스트는 항상 마운트하고 모드 전환은 d-none으로만 한다(수명주기 계약).
 */
import { useEffect, useRef, type FormEvent, type RefObject } from 'react'
import { scopeOptions, type ScopeSite } from '../shared/siteScope'
import type { View } from './FaqManage'
import { categoryOptions, type CategoryForm, type FaqForm } from './state'

interface Props {
  view: View
  sites: ScopeSite[]
  modalRef: RefObject<HTMLDialogElement | null>
  editorHostRef: RefObject<HTMLDivElement | null>
  formLocked: boolean
  canCreate: boolean
  canUpdate: boolean
  canDelete: boolean
  onCategoryForm: (patch: Partial<CategoryForm>) => void
  onFaqForm: (patch: Partial<FaqForm>) => void
  onCategorySubmit: (event: FormEvent) => void
  onFaqSubmit: (event: FormEvent) => void
  onCategoryDelete: () => void
  onFaqDelete: () => void
  /** ×·[닫기] — 미저장 입력이 있으면 확인한다 */
  onRequestClose: () => void
  /** 브라우저가 닫은 뒤(close 이벤트) */
  onClosed: () => void
}

const hidden = (hide: boolean) => (hide ? ' d-none' : '')

export function FaqModal(props: Props) {
  const { view, formLocked } = props
  const category = view.modalMode === 'category'
  const editingCategory = view.editingCategoryId !== null
  const editingFaq = view.editingFaq !== null
  const showing = view.modalState === 'open'
  const faqCategoryOptions = view.server ? categoryOptions(view.server, view.faqForm.categoryId, showing && !category) : []

  // 초기 포커스를 × 대신 컨테이너에 둔다(Enter를 누른 채 열 때 키 반복이 ×를 누르지 않게). React의 autoFocus prop은 속성을 남기지 않아 직접 쓴다.
  const content = useRef<HTMLDivElement>(null)
  useEffect(() => content.current?.setAttribute('autofocus', ''), [])

  return (
    <dialog
      ref={props.modalRef}
      className="modal admin-dialog admin-detail-modal"
      id="faqModal"
      aria-labelledby="catFormTitle faqFormTitle"
      // ESC·배경 클릭으로 닫히지 않는다(입력 보호). closedby를 모르는 브라우저는 FaqManage의 keydown 차단과 아래 onCancel이 막는다.
      closedby="none"
      onCancel={(event) => event.preventDefault()}
      onClose={props.onClosed}
      // 처음 값만 React가 쓴다(prop이 바뀌지 않아 다시 쓰지 않는다). 이후 전환은 FaqManage.setModalState가 직접 쓴다.
      data-state="closed"
    >
      <div className="modal-dialog modal-lg modal-dialog-centered" role="document">
        <div className="modal-content" ref={content} tabIndex={-1}>
          <div className="modal-header">
            <div className="detail-hero">
              <div className="detail-hero-copy">
                <h5 className={'modal-title' + hidden(!category)} id="catFormTitle">{view.catTitle}</h5>
                <h5 className={'modal-title' + hidden(category)} id="faqFormTitle">{view.faqTitle}</h5>
              </div>
            </div>
            <button type="button" className="close" aria-label="Close" id="btnModalCloseX" disabled={view.writing} onClick={props.onRequestClose}>
              <span aria-hidden="true">&times;</span>
            </button>
          </div>
          <div className="modal-body">
            <div id="modalAlert" className={'alert alert-danger' + hidden(view.modalAlert === null)} role="alert">{view.modalAlert ?? ''}</div>

            <div id="catSection" className={hidden(!category).trim()}>
              <form id="catForm" onSubmit={props.onCategorySubmit}>
                <fieldset id="catFieldset" disabled={formLocked}>
                  <div className="form-group">
                    <label htmlFor="catName">이름</label>
                    <input type="text" className="form-control" id="catName" maxLength={50} required
                      value={view.catForm.name} onChange={(e) => props.onCategoryForm({ name: e.target.value })} />
                  </div>
                  <div className="form-group">
                    <label htmlFor="catSiteScope">표시 대상</label>
                    <select className="form-control" id="catSiteScope" aria-describedby="catSiteScopeHint"
                      value={view.catForm.siteScope} onChange={(e) => props.onCategoryForm({ siteScope: e.target.value })}>
                      {scopeOptions(props.sites, view.scopeExtras).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                    </select>
                    <small className="form-text text-muted" id="catSiteScopeHint">공통은 모든 공개 사이트에, 사이트를 고르면 그 사이트에서만 보입니다. 이 카테고리의 FAQ가 따라갑니다.</small>
                  </div>
                  <div className="form-group form-check">
                    <input type="checkbox" className="form-check-input" id="catUse"
                      checked={view.catForm.useYn} onChange={(e) => props.onCategoryForm({ useYn: e.target.checked })} />
                    <label className="form-check-label" htmlFor="catUse">노출 (끄면 이 카테고리의 FAQ가 모두 공개되지 않음)</label>
                  </div>
                </fieldset>
              </form>
            </div>

            <div id="faqSection" className={hidden(category).trim()}>
              <form id="faqForm" onSubmit={props.onFaqSubmit}>
                <fieldset id="faqFieldset" disabled={formLocked}>
                  <div className="form-group">
                    <label htmlFor="faqCategory">카테고리</label>
                    <select className="form-control" id="faqCategory" required
                      value={view.faqForm.categoryId} onChange={(e) => props.onFaqForm({ categoryId: e.target.value })}>
                      {faqCategoryOptions.map((o) => (
                        <option key={o.value} value={o.value} data-missing={o.missing ? 'true' : undefined}>{o.label}</option>
                      ))}
                    </select>
                    <small className="form-text text-muted" id="faqCategoryHint">수정에서 카테고리를 바꾸면 선택한 카테고리의 맨 끝으로 이동합니다.</small>
                  </div>
                  <div className="form-group">
                    <label htmlFor="faqQuestion">질문</label>
                    <input type="text" className="form-control" id="faqQuestion" maxLength={300} required
                      value={view.faqForm.question} onChange={(e) => props.onFaqForm({ question: e.target.value })} />
                  </div>
                  <div className="form-group">
                    <label>답변</label>
                    {/* 편집기 호스트: React가 자식을 관리하지 않는다 — FaqManage가 #faqAnswerEditor를 만들어 넣고 Quill이 툴바를 끼워 넣는다 */}
                    <div className="faq-editor" ref={props.editorHostRef} />
                    <small className="form-text text-muted">보이는 글자 5,000자 이하. 이미지는 넣을 수 없습니다.</small>
                  </div>
                  <div className="form-group form-check">
                    <input type="checkbox" className="form-check-input" id="faqUse"
                      checked={view.faqForm.useYn} onChange={(e) => props.onFaqForm({ useYn: e.target.checked })} />
                    <label className="form-check-label" htmlFor="faqUse">노출</label>
                  </div>
                </fieldset>
              </form>
            </div>
          </div>
          <div className="modal-footer">
            <button type="button" className={'btn btn-danger mr-auto' + hidden(!category || !editingCategory || !props.canDelete)} id="btnCatDelete" onClick={props.onCategoryDelete}>삭제</button>
            <button type="button" className={'btn btn-danger mr-auto' + hidden(category || !editingFaq || !props.canDelete)} id="btnFaqDelete" onClick={props.onFaqDelete}>삭제</button>
            <button type="button" className="btn btn-secondary" id="btnModalClose" disabled={view.writing} onClick={props.onRequestClose}>닫기</button>
            <button type="submit" className={'btn btn-primary' + hidden(!category || (editingCategory ? !props.canUpdate : !props.canCreate))} id="btnCatSave" form="catForm">{editingCategory ? '저장' : '등록'}</button>
            <button type="submit" className={'btn btn-primary' + hidden(category || (editingFaq ? !props.canUpdate : !props.canCreate))} id="btnFaqSave" form="faqForm">{editingFaq ? '저장' : '등록'}</button>
          </div>
        </div>
      </div>
    </dialog>
  )
}
