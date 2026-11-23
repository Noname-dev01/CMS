/** 게시글 보기 모드. 본문만 서버가 정리한 HTML이고 나머지는 모두 텍스트다. */
import type { ReactNode } from 'react'
import type { PostDetail } from '../../api/post'
import { ServerHtml } from '../shared/ServerHtml'
import { coverThumbUrl, dateText, periodText, STATUS_LABELS } from './format'

function Item({ label, id, children }: { label: string; id: string; children: ReactNode }) {
  return (
    <div className="detail-item">
      <span className="label">{label}</span>
      <div className="value" id={id}>{children}</div>
    </div>
  )
}

export function PostView({ detail, visible }: { detail: PostDetail | null; visible: boolean }) {
  const cover = coverThumbUrl(detail?.coverImageId)
  return (
    <div id="detailContent" className={'detail-grid' + (visible ? '' : ' d-none')}>
      {detail && (
        <>
          <Item label="제목" id="viewTitle">{detail.title || '-'}</Item>
          <Item label="작성자" id="viewAuthor">{detail.authorId || '-'}</Item>
          <Item label="노출 여부" id="viewUseYn">{detail.useYn ? '노출' : '비노출'}</Item>
          <Item label="상단 고정" id="viewPinned">{detail.pinned ? '예' : '아니오'}</Item>
          <Item label="노출 상태" id="viewStatus">{STATUS_LABELS.get(detail.status) ?? '-'}</Item>
          <Item label="노출 기간" id="viewPeriod">{periodText(detail.publishStart, detail.publishEnd)}</Item>
          <Item label="조회수" id="viewViews">{detail.viewCount == null ? '-' : String(detail.viewCount)}</Item>
          <Item label="작성일" id="viewCreateDate">{dateText(detail.createDate)}</Item>
          <Item label="수정일" id="viewUpdateDate">{dateText(detail.updateDate)}</Item>
          <Item label="최종 수정자" id="viewUpdater">{detail.updaterId || '-'}</Item>
          <Item label="대표 이미지" id="viewCover">
            {cover === null ? '없음' : <div className="cover-view"><img src={cover} alt="대표 이미지" /></div>}
          </Item>
          <div className="detail-item">
            <span className="label">본문</span>
            <ServerHtml id="viewContent" className="value post-content-view" html={detail.content} />
          </div>
        </>
      )}
    </div>
  )
}
