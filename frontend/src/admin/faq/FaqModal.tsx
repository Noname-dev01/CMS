/**
 * 카테고리·FAQ 등록·수정 모달(한 모달에 두 폼, 한 번에 하나만 보인다). Bootstrap 4 jQuery 모달이 #faqModal의 class·style·aria-hidden을
 * 직접 바꾸므로 그 속성들은 마운트 때 한 번 정해지는 정적 값만 둔다(PLAN-react-admin-faq.md C6). data-state는 FaqManage가 직접 쓴다.
 * 모달·두 폼·편집기 호스트는 항상 마운트하고 모드 전환은 d-none으로만 한다(수명주기 계약).
 */
import type { FormEvent, RefObject } from 'react'
import { scopeOptions, type ScopeSite } from '../shared/siteScope'
import type { View } from './FaqManage'
import { categoryOptions, type CategoryForm, type FaqForm } from './state'

interface Props {
  view: View
  sites: ScopeSite[]
  modalRef: RefObject<HTMLDivElement | null>
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
}

const hidden = (hide: boolean) => (hide ? ' d-none' : '')

export function FaqModal(props: Props) {
  const { view, formLocked } = props
  const category = view.modalMode === 'category'
  const editingCategory = view.editingCategoryId !== null
  const editingFaq = view.editingFaq !== null
  const showing = view.modalState === 'opening' || view.modalState === 'open'
  const faqCategoryOptions = view.server ? categoryOptions(view.server, view.faqForm.categoryId, showing && !category) : []

  return (
    <div
      ref={props.modalRef}
      className="modal fade admin-detail-modal"
      id="faqModal"
      tabIndex={-1}
      role="dialog"
      aria-labelledby="catFormTitle faqFormTitle"
      aria-hidden="true"
      data-backdrop="static"
      data-keyboard="false"
      // 처음 값만 React가 쓴다(prop이 바뀌지 않아 다시 쓰지 않는다). 이후 전환은 FaqManage.setModalState가 직접 쓴다.
      data-state="closed"
    >
      <div className="modal-dialog modal-lg modal-dialog-centered" role="document">
        <div className="modal-content">
          <div className="modal-header">
            <div className="detail-hero">
              <div className="detail-hero-copy">
                <h5 className={'modal-title' + hidden(!category)} id="catFormTitle">{view.catTitle}</h5>
                <h5 className={'modal-title' + hidden(category)} id="faqFormTitle">{view.faqTitle}</h5>
              </div>
            </div>
            <button type="button" className="close" data-dismiss="modal" aria-label="Close" id="btnModalCloseX" disabled={view.writing}>
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
            <button type="button" className="btn btn-secondary" data-dismiss="modal" id="btnModalClose" disabled={view.writing}>닫기</button>
            <button type="submit" className={'btn btn-primary' + hidden(!category || (editingCategory ? !props.canUpdate : !props.canCreate))} id="btnCatSave" form="catForm">{editingCategory ? '저장' : '등록'}</button>
            <button type="submit" className={'btn btn-primary' + hidden(category || (editingFaq ? !props.canUpdate : !props.canCreate))} id="btnFaqSave" form="faqForm">{editingFaq ? '저장' : '등록'}</button>
          </div>
        </div>
      </div>
    </div>
  )
}
