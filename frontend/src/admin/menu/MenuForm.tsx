/** 메뉴 정보 폼. 값은 MenuManage의 모델이 소유한다(controlled). */
import type { RefObject } from 'react'
import type { MenuFields } from '../../api/menu'
import { iconNameOf } from './icons'

interface Props {
  form: MenuFields
  error: string | null
  modeLabel: string
  urlHelp: string
  /** null이면 노출 안내를 숨긴다(관리자 사이드바 개념이라 관리자 사이트에서만 보인다) */
  exposure: string | null
  /** 초안이 있거나 사이트 전환 중 */
  locked: boolean
  showDeactivate: boolean
  showPermanentDelete: boolean
  urlRef: RefObject<HTMLInputElement | null>
  onChange: (patch: Partial<MenuFields>) => void
  onIconPick: () => void
  onSave: () => void
  onDeactivate: () => void
  onPermanentDelete: () => void
  onReset: () => void
}

export function MenuForm(props: Props) {
  const { form, locked, onChange } = props
  return (
    <div className="form-container">
      <div className="card-header"><h6>메뉴 정보</h6></div>
      <div className="card-body">
        <div id="formAlert" className={'alert alert-danger' + (props.error === null ? ' d-none' : '')} role="alert">{props.error ?? ''}</div>
        <div id="formModeLabel" className="form-mode-label">{props.modeLabel}</div>
        <form id="menuForm" onSubmit={(event) => event.preventDefault()}>
          <div className="form-group">
            <label htmlFor="menuName">메뉴명</label>
            <input type="text" className="form-control" id="menuName" name="menuName" disabled={locked}
              value={form.menuName} onChange={(e) => onChange({ menuName: e.target.value })} />
          </div>
          <div className="form-group">
            <label htmlFor="menuUrl">URL</label>
            <input type="text" className="form-control" id="menuUrl" name="menuUrl" aria-describedby="menuUrlHelp" disabled={locked}
              ref={props.urlRef} value={form.menuUrl} onChange={(e) => onChange({ menuUrl: e.target.value })} />
            <small id="menuUrlHelp" className="form-text text-muted">{props.urlHelp}</small>
          </div>
          <div className="form-group">
            <label htmlFor="menuIcon">아이콘</label>
            {/* 값은 hidden에 클래스 문자열(예: "fas fa-fw fa-users")로 담고, 사용자는 모달에서 아이콘을 골라 지정한다 */}
            <input type="hidden" id="menuIcon" name="menuIcon" value={form.menuIcon} disabled={locked} readOnly />
            <div className="d-flex align-items-center flex-wrap">
              <span className="icon-preview mr-3" id="menuIconPreview" aria-hidden="true">
                {form.menuIcon === '' ? null : <i className={form.menuIcon} />}
              </span>
              <span className="text-muted small mr-3" id="menuIconLabel">{iconNameOf(form.menuIcon) || '선택 안 함'}</span>
              <button type="button" className="btn btn-outline-secondary btn-sm text-nowrap mr-2" id="btnIconPick" disabled={locked} onClick={props.onIconPick}>아이콘 선택</button>
              <button type="button" className="btn btn-outline-secondary btn-sm text-nowrap" id="btnIconClear" disabled={locked} onClick={() => onChange({ menuIcon: '' })}>지우기</button>
            </div>
          </div>
          <div className="form-group">
            <label htmlFor="menuDesc">설명</label>
            <textarea className="form-control" id="menuDesc" name="menuDesc" rows={3} disabled={locked}
              value={form.menuDesc} onChange={(e) => onChange({ menuDesc: e.target.value })} />
          </div>
          <div className={'form-group' + (props.exposure === null ? ' d-none' : '')} id="menuExposureGroup">
            <label>노출 대상 <small className="text-muted">(MANAGER 기준, 읽기 전용 — 권한관리와 메뉴 위치로 정해집니다)</small></label>
            <p className="form-control-plaintext mb-0" id="menuExposure">{props.exposure ?? '-'}</p>
          </div>
          <div className="form-group">
            <div className="custom-control custom-switch">
              <input type="checkbox" className="custom-control-input" id="menuUseYn" name="menuUseYn" disabled={locked}
                checked={form.useYn} onChange={(e) => onChange({ useYn: e.target.checked })} />
              <label className="custom-control-label" htmlFor="menuUseYn">사용 여부</label>
            </div>
          </div>
          <div className="btn-container">
            <button type="button" className="btn btn-primary" id="btnSave" disabled={locked} onClick={props.onSave}>저장</button>
            {/* 활성 메뉴는 [비활성화], 비활성 메뉴는 [영구삭제]만 보인다 */}
            <button type="button" className={'btn btn-warning' + (props.showDeactivate ? '' : ' d-none')} id="btnDeactivate" disabled={locked} onClick={props.onDeactivate}>비활성화</button>
            <button type="button" className={'btn btn-danger' + (props.showPermanentDelete ? '' : ' d-none')} id="btnPermanentDelete" disabled={locked} onClick={props.onPermanentDelete}>영구삭제</button>
            <button type="button" className="btn btn-secondary" id="btnReset" disabled={locked} onClick={props.onReset}>초기화</button>
          </div>
        </form>
      </div>
    </div>
  )
}
