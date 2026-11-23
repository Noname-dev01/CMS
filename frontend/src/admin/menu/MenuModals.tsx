/**
 * 영구삭제 확인·아이콘 선택 모달. Bootstrap 4 jQuery 모달이 모달 요소의 class·style·aria-hidden을 직접 바꾸므로 그 속성들은 마운트 때 한 번
 * 정해지는 정적 값만 둔다(PLAN-react-admin-menu.md C4). 항상 마운트하고 열기·닫기는 MenuManage가 $().modal()로 한다.
 */
import type { RefObject } from 'react'
import { filterIcons, iconNameOf } from './icons'

interface DeleteProps {
  modalRef: RefObject<HTMLDivElement | null>
  inputRef: RefObject<HTMLInputElement | null>
  error: string | null
  confirmText: string
  input: string
  confirmEnabled: boolean
  onInput: (value: string) => void
  onCancel: () => void
  onConfirm: () => void
}

/** 메뉴명(확인 문자열)을 직접 입력해야 [영구삭제]가 켜진다. 배경 클릭·ESC로 닫히지 않는다. */
export function PermanentDeleteModal(props: DeleteProps) {
  return (
    <div ref={props.modalRef} className="modal fade" id="permanentDeleteModal" tabIndex={-1} role="dialog"
      aria-labelledby="permanentDeleteModalLabel" aria-hidden="true" data-backdrop="static" data-keyboard="false">
      <div className="modal-dialog modal-dialog-centered" role="document">
        <div className="modal-content">
          <div className="modal-header"><h5 className="modal-title" id="permanentDeleteModalLabel">메뉴 영구삭제</h5></div>
          <div className="modal-body">
            <div id="permanentDeleteError" className={'alert alert-danger' + (props.error === null ? ' d-none' : '')} role="alert">{props.error ?? ''}</div>
            <p className="mb-2">
              <strong>영구 삭제되며 복구할 수 없습니다.</strong>{' '}
              비활성 상태이고 하위 메뉴가 없는 메뉴만 삭제할 수 있습니다.
            </p>
            <p className="mb-2">계속하려면 아래 메뉴명을 그대로 입력하세요.</p>
            <p className="mb-2"><code id="permanentDeleteConfirmText" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{props.confirmText}</code></p>
            <input ref={props.inputRef} type="text" className="form-control" id="permanentDeleteInput" autoComplete="off" spellCheck={false}
              aria-label="삭제할 메뉴명 확인 입력" value={props.input} onChange={(e) => props.onInput(e.target.value)} />
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" id="btnPermanentDeleteCancel" onClick={props.onCancel}>취소</button>
            <button type="button" className="btn btn-danger" id="btnPermanentDeleteConfirm" disabled={!props.confirmEnabled} onClick={props.onConfirm}>영구삭제</button>
          </div>
        </div>
      </div>
    </div>
  )
}

interface IconProps {
  modalRef: RefObject<HTMLDivElement | null>
  keyword: string
  /** 지금 폼의 아이콘 값(클래스 문자열) */
  value: string
  onKeyword: (value: string) => void
  onPick: (name: string) => void
}

export function IconPickerModal(props: IconProps) {
  const names = filterIcons(props.keyword)
  const current = iconNameOf(props.value)
  return (
    <div ref={props.modalRef} className="modal fade" id="iconPickerModal" tabIndex={-1} role="dialog" aria-labelledby="iconPickerModalLabel" aria-hidden="true">
      <div className="modal-dialog modal-lg" role="document">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title" id="iconPickerModalLabel">아이콘 선택</h5>
            <button className="close" type="button" data-dismiss="modal" aria-label="닫기"><span aria-hidden="true">&times;</span></button>
          </div>
          <div className="modal-body">
            <input type="text" className="form-control mb-3" id="iconSearch" placeholder="아이콘 검색 (예: user, chart)" autoComplete="off"
              aria-label="아이콘 검색" value={props.keyword} onChange={(e) => props.onKeyword(e.target.value)} />
            <div id="iconGrid" className="icon-grid">
              {names.map((name) => (
                <button key={name} type="button" className={'icon-item' + (name === current ? ' selected' : '')} title={name} data-icon={name}
                  onClick={() => props.onPick(name)}>
                  <i className={'fas fa-fw fa-' + name} /><span>{name}</span>
                </button>
              ))}
            </div>
            <p id="iconEmpty" className={'text-muted small mb-0' + (names.length > 0 ? ' d-none' : '')}>검색 결과가 없습니다.</p>
          </div>
        </div>
      </div>
    </div>
  )
}
