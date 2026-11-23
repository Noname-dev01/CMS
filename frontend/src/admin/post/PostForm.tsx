/** 게시글 작성·수정 폼. 본문은 편집기(빈 호스트에 Quill이 직접 그린다)가 소유하고 나머지는 controlled다. */
import type { RefObject } from 'react'
import type { MyBoard, PostDetail } from '../../api/post'
import { coverTypeNote, imagesAllowed, periodAllowed, pinPolicy } from './board'
import type { DateTimeInput, PostForm as Form } from './form'
import { periodText } from './format'

interface Props {
  visible: boolean
  board: MyBoard | null
  /** 수정 중인 글(작성이면 null) */
  detail: PostDetail | null
  form: Form
  imageIds: number[]
  editorBusy: boolean
  editorHostRef: RefObject<HTMLDivElement | null>
  onForm: (patch: Partial<Form>) => void
}

const hiddenIf = (hide: boolean) => (hide ? ' d-none' : '')
const numbers = (count: number) => Array.from({ length: count }, (_, i) => String(i).padStart(2, '0'))
const HOURS = numbers(24)
const MINUTES = numbers(60)

function TimeSelect({ id, label, placeholder, options, value, className, onChange }: {
  id: string; label: string; placeholder: string; options: string[]; value: string; className: string; onChange: (value: string) => void
}) {
  return (
    <select className={className} id={id} aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{placeholder}</option>
      {options.map((o) => <option key={o} value={o}>{o}</option>)}
    </select>
  )
}

// 날짜와 시·분을 나눠 받는다 — datetime-local 달력은 날짜를 골라도 바깥을 클릭할 때까지 열려 있다(배너 화면과 같은 방식).
function DateTimeFields({ prefix, label, value, onChange }: { prefix: string; label: string; value: DateTimeInput; onChange: (value: DateTimeInput) => void }) {
  return (
    <div className="form-row">
      <div className="col-7">
        <input type="date" className="form-control" id={prefix + 'Date'} aria-label={label + ' 날짜'} value={value.date} onChange={(e) => onChange({ ...value, date: e.target.value })} />
      </div>
      <div className="col-5 d-flex">
        <TimeSelect id={prefix + 'Hour'} label={label + ' 시'} placeholder="시" options={HOURS} className="form-control mr-1" value={value.hour} onChange={(hour) => onChange({ ...value, hour })} />
        <TimeSelect id={prefix + 'Minute'} label={label + ' 분'} placeholder="분" options={MINUTES} className="form-control" value={value.minute} onChange={(minute) => onChange({ ...value, minute })} />
      </div>
    </div>
  )
}

function CoverChoice({ id, selected, onSelect }: { id: number | null; selected: boolean; onSelect: (id: number | null) => void }) {
  return (
    <label className={'cover-choice' + (selected ? ' is-selected' : '')}>
      <input type="radio" name="coverImage" value={id === null ? '' : String(id)} checked={selected} onChange={() => onSelect(id)} />
      {id === null ? <span>없음</span> : <img src={'/content-images/' + id} alt={'본문 이미지 ' + id} loading="lazy" />}
    </label>
  )
}

export function PostForm({ visible, board, detail, form, imageIds, editorBusy, editorHostRef, onForm }: Props) {
  const imagesOn = imagesAllowed(board)
  const periodOn = periodAllowed(board)
  const typeNote = coverTypeNote(board)
  const pin = pinPolicy(board, !!detail?.pinned)
  const hasPeriod = !!(detail && (detail.publishStart || detail.publishEnd))
  const selectCover = (cover: number | null) => onForm({ cover, coverUserChanged: true })

  return (
    // 제목에서 Enter를 눌러도 저장·페이지 이동이 일어나지 않게 막는다(저장은 [저장] 버튼만)
    <form id="postForm" className={hiddenIf(!visible).trim()} onSubmit={(e) => e.preventDefault()}>
      <div className="form-group">
        <label className="form-label" htmlFor="formTitle">제목</label>
        <input type="text" className="form-control" id="formTitle" maxLength={200} value={form.title} onChange={(e) => onForm({ title: e.target.value })} />
      </div>
      <div className="form-group">
        <label className="form-label">본문</label>
        {/* 편집기 호스트: React가 자식을 관리하지 않는다 — PostManage가 #formContentEditor를 만들어 넣고 Quill이 툴바를 끼워 넣는다 */}
        <div className="post-editor" ref={editorHostRef} />
        <small id="editorBusy" className={'form-text text-muted' + hiddenIf(!editorBusy)}>이미지를 올리는 중입니다. 끝난 뒤 저장할 수 있습니다.</small>
        <small id="imagePolicyNote" className={'form-text text-muted' + hiddenIf(imagesOn)}>이 게시판은 새 본문 이미지를 허용하지 않습니다. 이미 들어 있는 이미지는 유지됩니다.</small>
        {/* 유형 안내는 대표 묶음 밖에 둔다 — 본문에 이미지가 없어 묶음이 숨어 있을 때도 보여야 한다 */}
        <small id="coverTypeNote" className={'form-text text-muted' + hiddenIf(typeNote === '')}>{typeNote}</small>
      </div>
      <div id="coverGroup" className={'form-group' + hiddenIf(imageIds.length === 0)}>
        <label className="form-label">대표 이미지</label>
        <small className="form-text text-muted mb-2">본문에 들어 있는 이미지 중 하나를 대표로 지정합니다. 큰 이미지는 저장할 때 썸네일이 만들어지며, 손상된 이미지나 CMYK 등은 대표로 쓸 수 없습니다.</small>
        <div id="coverChoices" className="cover-choices" role="radiogroup" aria-label="대표 이미지">
          {imageIds.length > 0 && <CoverChoice id={null} selected={form.cover === null} onSelect={selectCover} />}
          {imageIds.map((id) => <CoverChoice key={id} id={id} selected={form.cover === id} onSelect={selectCover} />)}
        </div>
      </div>
      <div className="form-group">
        <div className="custom-control custom-switch">
          <input type="checkbox" className="custom-control-input" id="formUseYn" checked={form.useYn} onChange={(e) => onForm({ useYn: e.target.checked })} />
          <label className="custom-control-label" htmlFor="formUseYn">노출</label>
        </div>
        <small className="form-text text-muted">노출 여부와 아래 노출 기간을 <strong>둘 다</strong> 만족해야 공개됩니다.</small>
      </div>
      <div id="pinGroup" className={'form-group' + hiddenIf(!pin.visible)}>
        <div className="custom-control custom-switch">
          <input type="checkbox" className="custom-control-input" id="formPinned" checked={form.pinned} onChange={(e) => onForm({ pinned: e.target.checked })} />
          <label className="custom-control-label" htmlFor="formPinned">상단 고정</label>
        </div>
        <small id="pinNote" className="form-text text-muted">{pin.note}</small>
      </div>
      <div id="periodLocked" className={'form-group' + hiddenIf(periodOn)}>
        <small className="form-text text-muted">이 게시판은 예약 게시를 허용하지 않습니다. 이미 설정된 기간은 그대로 적용됩니다.</small>
        <div className="d-flex align-items-center mt-1">
          <span className="mr-2">노출 기간: <strong id="periodLockedText">
            {form.periodClear ? '해제 예정 (저장하면 제한 없음)' : periodText(detail?.publishStart, detail?.publishEnd)}
          </strong></span>
          <button type="button" className={'btn btn-outline-secondary btn-sm' + hiddenIf(!hasPeriod || form.periodClear)} id="btnClearPeriod" onClick={() => onForm({ periodClear: true })}>기간 해제</button>
          <button type="button" className={'btn btn-outline-secondary btn-sm' + hiddenIf(!form.periodClear)} id="btnUndoClearPeriod" onClick={() => onForm({ periodClear: false })}>해제 취소</button>
        </div>
      </div>
      <div id="periodFields" className={hiddenIf(!periodOn).trim()}>
        <div className="form-group">
          <label htmlFor="formStartDate">노출 시작 (선택, 비우면 바로 시작 · 시·분을 비우면 00:00)</label>
          <DateTimeFields prefix="formStart" label="노출 시작" value={form.start} onChange={(start) => onForm({ start })} />
        </div>
        <div className="form-group">
          <label htmlFor="formEndDate">노출 종료 (선택, 비우면 계속 노출 · 이 시각 전까지 노출 · 시·분을 비우면 00:00)</label>
          <DateTimeFields prefix="formEnd" label="노출 종료" value={form.end} onChange={(end) => onForm({ end })} />
          <small className="form-text text-muted">한국 시간 기준이며 분 단위입니다. 시작과 종료를 모두 비우면 기간 제한이 없습니다.</small>
        </div>
      </div>
    </form>
  )
}
