/** 게시글 목록 카드. 조회 상태는 호출자가 정하고 여기서는 그리기만 한다. */
import type { PageOf, PostSummary } from '../../api/post'
import { dateText, periodText, statusBadge, summaryPages } from './format'
import { Pagination } from './Pagination'

export type ListStatus = 'loading' | 'error' | 'ready'

interface Props {
  status: ListStatus
  /** 마지막으로 성공한 응답 — 조회 중에는 요약·페이지 버튼에 직전 값을 남긴다 */
  data: PageOf<PostSummary> | null
  onOpen: (id: number) => void
  onPage: (page: number) => void
}

function Row({ item, onOpen }: { item: PostSummary; onOpen: (id: number) => void }) {
  const badge = statusBadge(item.status)
  return (
    <tr>
      <td>{item.id}</td>
      <td>
        {item.pinned && <span className="badge badge-primary mr-1">고정</span>}
        <button type="button" className="btn btn-link p-0 cell-link js-detail" data-id={item.id} onClick={() => onOpen(item.id)}>{item.title}</button>
      </td>
      <td>{item.authorId || '-'}</td>
      {/* 최종 수정자가 없는 글은 V5 이전에 만들어져 이후 손대지 않은 글이다 */}
      <td>{item.updaterId || '-'}</td>
      <td><span className={badge.className}>{badge.label}</span></td>
      <td>{periodText(item.publishStart, item.publishEnd)}</td>
      <td>{item.viewCount == null ? '-' : item.viewCount}</td>
      <td>{dateText(item.createDate)}</td>
      <td className="text-center">
        <button type="button" className="btn btn-outline-primary btn-sm js-detail" data-id={item.id} onClick={() => onOpen(item.id)}>상세</button>
      </td>
    </tr>
  )
}

export function PostList({ status, data, onOpen, onPage }: Props) {
  const items = status === 'ready' && data ? data.content : []
  return (
    <div className="card shadow mb-0 admin-panel">
      <div className="card-header">
        <div>
          <h6>게시글 목록</h6>
          <p>제목을 클릭하면 상세 정보를 모달에서 확인할 수 있습니다.</p>
        </div>
      </div>
      <div className="card-body">
        <div className="summary-bar">
          <div className="summary-text" id="summaryText">
            총 <strong>{data?.totalElements ?? 0}</strong>건{data && ' · ' + summaryPages(data.page, data.totalPages)}
          </div>
        </div>

        <div className="table-responsive">
          <table className="table table-hover mb-0">
            <thead>
              <tr>
                <th>ID</th>
                <th>제목</th>
                <th>작성자</th>
                <th>최종 수정자</th>
                <th>노출 상태</th>
                <th>노출 기간</th>
                <th>조회수</th>
                <th>작성일</th>
                <th className="text-center">상세</th>
              </tr>
            </thead>
            <tbody id="postTableBody">
              {status === 'loading' && <tr><td colSpan={9} className="text-center text-muted py-4">조회 중입니다.</td></tr>}
              {status === 'error' && <tr><td colSpan={9} className="text-center text-danger py-4">조회에 실패했습니다.</td></tr>}
              {items.map((item) => <Row key={item.id} item={item} onOpen={onOpen} />)}
            </tbody>
          </table>
        </div>

        <div id="emptyState" className={'empty-state mt-3' + (status === 'ready' && items.length === 0 ? '' : ' d-none')}>조회 결과가 없습니다.</div>

        <Pagination id="pagination" label="게시글 페이지 목록" page={status === 'error' ? null : data} onPage={onPage} />
      </div>
    </div>
  )
}
