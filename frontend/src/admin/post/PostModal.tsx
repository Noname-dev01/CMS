/**
 * 게시글 상세·작성·수정 모달. Bootstrap 4 jQuery 모달이 #postDetailModal의 class·style·aria-hidden을 직접 바꾸므로 그 속성은 정적 값만 둔다.
 * 항상 마운트한다(편집기가 페이지에 하나). 옛 화면처럼 배경·ESC로 닫히고 미저장 확인은 없다.
 */
import type { ReactNode, RefObject } from 'react'
import type { MyBoard } from '../../api/post'
import { actionAllowed } from './board'

export type ModalMode = 'view' | 'edit' | 'create' | null

interface Props {
  modalRef: RefObject<HTMLDivElement | null>
  board: MyBoard | null
  mode: ModalMode
  modeLabel: string
  loading: boolean
  /** 보기 화면을 그릴 상세가 있는가 */
  hasDetail: boolean
  alert: string | null
  success: string | null
  saveDisabled: boolean
  cancelDisabled: boolean
  onDelete: () => void
  onEdit: () => void
  onSave: () => void
  onCancel: () => void
  children: ReactNode
}

const hiddenIf = (hide: boolean) => (hide ? ' d-none' : '')

export function PostModal(props: Props) {
  const { board, mode } = props
  const viewing = mode === 'view' && props.hasDetail
  const editing = mode === 'edit' || mode === 'create'
  return (
    <div ref={props.modalRef} className="modal fade admin-detail-modal" id="postDetailModal" tabIndex={-1} role="dialog" aria-labelledby="postDetailModalLabel" aria-hidden="true">
      <div className="modal-dialog modal-lg modal-dialog-centered" role="document">
        <div className="modal-content">
          <div className="modal-header">
            <div className="detail-hero">
              <div className="detail-hero-copy">
                <h5 className="modal-title" id="postDetailModalLabel">게시글</h5>
                <p id="modalModeLabel">{props.modeLabel}</p>
              </div>
            </div>
            <button type="button" className="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span>
            </button>
          </div>
          <div className="modal-body">
            <div id="detailAlert" className={'alert alert-danger' + hiddenIf(props.alert === null)} role="alert">{props.alert ?? ''}</div>
            <div id="detailSuccess" className={'alert alert-success' + hiddenIf(props.success === null)} role="alert">{props.success ?? ''}</div>
            <div id="detailLoading" className={'detail-loading' + hiddenIf(!props.loading)}>상세 정보를 불러오는 중입니다.</div>
            {props.children}
          </div>
          <div className="modal-footer">
            <button type="button" className={'btn btn-danger' + hiddenIf(!viewing || !actionAllowed(board, 'DELETE'))} id="btnDelete" onClick={props.onDelete}>삭제</button>
            <button type="button" className={'btn btn-primary' + hiddenIf(!viewing || !actionAllowed(board, 'UPDATE'))} id="btnEditMode" onClick={props.onEdit}>
              <i className="fas fa-pen fa-sm mr-1"></i>수정
            </button>
            <button type="button" className={'btn btn-primary' + hiddenIf(!editing)} id="btnSave" disabled={props.saveDisabled} onClick={props.onSave}>저장</button>
            <button type="button" className={'btn btn-light border' + hiddenIf(!editing)} id="btnCancelEdit" disabled={props.cancelDisabled} onClick={props.onCancel}>취소</button>
            <button type="button" className="btn btn-secondary" data-dismiss="modal">닫기</button>
          </div>
        </div>
      </div>
    </div>
  )
}
