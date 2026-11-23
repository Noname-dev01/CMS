/**
 * 휴지통 카드 — 그 게시판의 삭제(DELETE) 권한이 있을 때만 보인다(서버 403이 최종). 내용은 열렸을 때만 마운트해 열 때마다 새로 조회한다.
 * 복원 진행 잠금은 여기가 아니라 PostManage에 있다 — 닫았다 다시 열거나 게시판을 바꿔도 요청이 끝날 때까지 유지돼야 한다.
 */
import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { DeletedPost, MyBoard, PageOf, PostApi } from '../../api/post'
import { dateText, summaryPages } from './format'
import { Pagination } from './Pagination'
import { bodyOrThrow } from './queries'

export interface TrashState {
  open: boolean
  page: number
  alert: string | null
  success: string | null
}

export const CLOSED_TRASH: TrashState = { open: false, page: 0, alert: null, success: null }

export const trashKey = (boardId: number, page: number) => ['trash', boardId, page]

interface Props {
  api: PostApi
  board: MyBoard
  trash: TrashState
  visible: boolean
  restoringId: number | null
  onToggle: () => void
  onPage: (page: number) => void
  onRestore: (item: DeletedPost) => void
}

function TrashBody({ api, board, trash, restoringId, onPage, onRestore }: Props) {
  const query = useQuery({
    queryKey: trashKey(board.boardId, trash.page),
    queryFn: async ({ signal }) => bodyOrThrow<PageOf<DeletedPost>>(await api.trash(board.boardId, trash.page, signal), '휴지통 조회에 실패했습니다.'),
  })
  const outOfRange = query.data !== undefined && query.data.totalPages > 0 && query.data.page >= query.data.totalPages
  const [last, setLast] = useState<PageOf<DeletedPost> | null>(null)
  useEffect(() => {
    if (!query.data || query.isFetching) return
    if (outOfRange) {
      onPage(query.data.totalPages - 1)   // 복원으로 현재 페이지가 비었다 — 마지막 유효 페이지로 다시 읽는다
    } else {
      setLast(query.data)
    }
  }, [query.data, query.isFetching, outOfRange])

  const loading = query.isPending || query.isFetching || outOfRange
  const failed = !loading && query.isError
  const data = !outOfRange && query.data ? query.data : last
  const items = !loading && !failed && data ? data.content : []
  const alert = trash.alert ?? (failed ? query.error.message : null)
  const success = alert === null ? trash.success : null

  return (
    <>
      <div id="trashAlert" className={'alert alert-danger' + (alert === null ? ' d-none' : '')} role="alert">{alert ?? ''}</div>
      <div id="trashSuccess" className={'alert alert-success' + (success === null ? ' d-none' : '')} role="status">{success ?? ''}</div>
      <div className="summary-bar">
        <div className="summary-text" id="trashSummary">
          {data ? '삭제된 게시글 ' + data.totalElements + '건 · ' + summaryPages(data.page, data.totalPages) : '삭제된 게시글 0건'}
        </div>
      </div>
      <div className="table-responsive">
        <table className="table table-hover mb-0">
          <thead>
            <tr>
              <th>ID</th>
              <th>제목</th>
              <th>작성자</th>
              <th>작성일</th>
              <th>삭제일</th>
              <th>삭제자</th>
              <th className="text-center">복원</th>
            </tr>
          </thead>
          <tbody id="trashTableBody">
            {loading && <tr><td colSpan={7} className="text-center text-muted py-4">조회 중입니다.</td></tr>}
            {failed && <tr><td colSpan={7} className="text-center text-danger py-4">조회에 실패했습니다.</td></tr>}
            {items.map((item) => (
              <tr key={item.id}>
                <td>{item.id}</td>
                <td>{item.title}</td>
                <td>{item.authorId || '-'}</td>
                <td>{dateText(item.createDate)}</td>
                <td>{dateText(item.deletedAt)}</td>
                {/* 삭제자가 없는 글은 V5 이전에 삭제된 글이다 — 감사 로그가 원본 */}
                <td>{item.deletedBy || '-'}</td>
                <td className="text-center">
                  <button type="button" className="btn btn-outline-primary btn-sm js-restore" data-id={item.id} data-title={item.title}
                    disabled={restoringId === item.id} onClick={() => onRestore(item)}>복원</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div id="trashEmpty" className={'empty-state mt-3' + (!loading && !failed && data && items.length === 0 ? '' : ' d-none')}>삭제된 게시글이 없습니다.</div>
      <Pagination id="trashPagination" label="휴지통 페이지 목록" page={failed ? null : data} onPage={onPage} />
    </>
  )
}

export function TrashPanel(props: Props) {
  const { trash, visible, onToggle } = props
  return (
    <div className={'card shadow mb-0 mt-4 admin-panel' + (visible ? '' : ' d-none')} id="trashPanel">
      <div className="card-header d-flex justify-content-between align-items-center">
        <div>
          <h6>휴지통</h6>
          <p>삭제한 게시글을 되살립니다. 복원한 글은 비노출·상단 고정 해제 상태이니 공개하려면 복원 뒤 노출을 켜세요.</p>
        </div>
        <button type="button" className="btn btn-outline-secondary btn-sm" id="btnToggleTrash" onClick={onToggle}>{trash.open ? '휴지통 닫기' : '휴지통 보기'}</button>
      </div>
      <div className={'card-body' + (trash.open ? '' : ' d-none')} id="trashBody">
        {trash.open && <TrashBody {...props} />}
      </div>
    </div>
  )
}
