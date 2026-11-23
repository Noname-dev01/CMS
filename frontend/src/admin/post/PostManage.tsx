/**
 * 게시글 관리 화면(React 아일랜드). 옛 인라인 스크립트(templates/admin/board/posts.html, 2026-11-23까지)의 동작을 옮긴 것이다 —
 * DOM 계약(id·클래스·d-none·disabled·confirm 문구)은 PostScreenBrowserTest가 고정한다.
 *
 * 상태 원칙(PLAN-react-admin-posts.md 결정 2·4):
 * - 조회는 TanStack Query가 맡고, 쓰기는 여기서 직접 한다. 쓰기 요청은 중단하지 않으며 끝난 뒤 모달 세션이 그대로일 때만 모달에 반영한다.
 * - 모달을 열 때마다 세션을 새로 만든다(동일성 토큰 + 그 모달의 게시판). 모달이 열린 동안 API 경로의 게시판은 세션의 것만 쓴다.
 * - 쓰기 뒤 목록 갱신은 시작 시점의 조건이 아니라 끝난 시점의 게시판·검색 폼 값으로 다시 읽는다.
 */
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal, flushSync } from 'react-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { contentImagesUrl, type DeletedPost, type MyBoard, type PageOf, type PostApi, type PostDetail, type PostListParams, type PostSummary } from '../../api/post'
import { Attachments } from './Attachments'
import { actionAllowed, deleteErrorMessage, imagesAllowed, periodAllowed } from './board'
import { buildCreateBody, buildEditBody, emptyForm, formOf, reconcileCover, type PostForm as Form } from './form'
import { PostForm } from './PostForm'
import { PostList, type ListStatus } from './PostList'
import { PostModal, type ModalMode } from './PostModal'
import { PostView } from './PostView'
import { bodyOrThrow, failureMessage, isOk, refresh } from './queries'
import { CLOSED_TRASH, TrashPanel, trashKey, type TrashState } from './TrashPanel'

/** 모달을 한 번 연 것. 같은 글을 다시 열어도 다른 세션이다. */
interface Session {
  id: number
  board: MyBoard
  /** 열 때의 글 번호(작성이면 null) */
  postId: number | null
}

interface Search {
  keyword: string
  useYn: string
}

interface View {
  boards: MyBoard[] | null
  board: MyBoard | null
  search: Search
  params: PostListParams | null
  boardsError: string | null
  trash: TrashState
  restoringId: number | null
  session: Session | null
  mode: ModalMode
  modeLabel: string
  /** 저장 응답 — 조회한 상세보다 우선한다 */
  saved: PostDetail | null
  form: Form
  imageIds: number[]
  editorBusy: boolean
  alert: string | null
  success: string | null
  /** 저장이 진행 중인 세션(다른 글의 저장은 이 모달을 잠그지 않는다) */
  saving: Session | null
}

const EMPTY_SEARCH: Search = { keyword: '', useYn: '' }

const INITIAL: View = {
  boards: null,
  board: null,
  search: EMPTY_SEARCH,
  params: null,
  boardsError: null,
  trash: CLOSED_TRASH,
  restoringId: null,
  session: null,
  mode: null,
  modeLabel: '게시글 정보를 확인·수정합니다.',
  saved: null,
  form: emptyForm(),
  imageIds: [],
  editorBusy: false,
  alert: null,
  success: null,
  saving: null,
}

const postsKey = (boardId: number, params: PostListParams) => ['posts', boardId, params.keyword, params.useYn, params.page]

export interface PostManageProps {
  api: PostApi
  /** 미디어 라이브러리 버튼 표시용 — 저장 시 서버가 다시 판정한다 */
  canPickLibrary: boolean
  /** 모달을 그릴 곳 — 옛 화면처럼 #wrapper 밖(body 직속)에 둔다 */
  modalContainer: HTMLElement
}

export function PostManage({ api, canPickLibrary, modalContainer }: PostManageProps) {
  const client = useQueryClient()
  const [view, setView] = useState<View>(INITIAL)
  const viewRef = useRef<View>(INITIAL)
  // 이벤트 핸들러·비동기 흐름이 즉시 최신 값을 읽도록 같은 값을 ref에 동기 반영한다
  const update = useCallback((patch: Partial<View>) => {
    viewRef.current = { ...viewRef.current, ...patch }
    setView(viewRef.current)
  }, [])

  const sessionSeq = useRef(0)
  const restoring = useRef(false)
  const modalEl = useRef<HTMLDivElement>(null)
  const editorHost = useRef<HTMLDivElement>(null)
  const editor = useRef<NoticeEditorInstance | null>(null)

  const isCurrent = (session: Session) => viewRef.current.session === session

  // ===================== 목록 =====================

  const { board, params, session } = view
  const listQuery = useQuery({
    queryKey: board && params ? postsKey(board.boardId, params) : ['posts'],
    enabled: board !== null && params !== null,
    queryFn: async ({ signal }) => {
      if (!board || !params) throw new Error('게시판이 선택되지 않았습니다.')
      return bodyOrThrow<PageOf<PostSummary>>(await api.list(board.boardId, params, signal), '게시글 목록 조회에 실패했습니다.')
    },
  })

  /** 지금 게시판·검색 폼 값으로 그 페이지를 다시 읽는다(제출하지 않은 입력도 실린다 — 옛 화면과 같음). */
  const loadPosts = (page: number) => {
    const v = viewRef.current
    if (!v.board) return
    const next: PostListParams = { ...v.search, page }
    const current = v.params
    if (current && current.keyword === next.keyword && current.useYn === next.useYn && current.page === next.page) {
      void refresh(client, postsKey(v.board.boardId, next))
    } else {
      update({ params: next })
    }
  }
  const reloadPosts = () => loadPosts(viewRef.current.params?.page ?? 0)

  const listData = listQuery.data
  const listOutOfRange = listData !== undefined && listData.totalPages > 0 && listData.page >= listData.totalPages
  const [lastList, setLastList] = useState<PageOf<PostSummary> | null>(null)
  useEffect(() => {
    if (!listData || listQuery.isFetching) return
    if (listOutOfRange) {
      loadPosts(listData.totalPages - 1)   // 삭제 등으로 총 페이지가 줄었다 — 마지막 유효 페이지로 다시 읽는다
    } else {
      setLastList(listData)
    }
  }, [listData, listQuery.isFetching, listOutOfRange])

  const listStatus: ListStatus = params === null || listQuery.isPending || listQuery.isFetching || listOutOfRange ? 'loading' : listQuery.isError ? 'error' : 'ready'
  const pageAlert = view.boardsError ?? (listStatus === 'error' && listQuery.error ? listQuery.error.message : null)

  // ===================== 게시판 선택 =====================

  const selectBoard = (next: MyBoard) => {
    update({ board: next, search: EMPTY_SEARCH, params: { ...EMPTY_SEARCH, page: 0 }, trash: CLOSED_TRASH })
    editor.current?.setUploadUrl(contentImagesUrl(next.boardId))
  }

  // ===================== 상세·모달 =====================

  const detailQuery = useQuery({
    queryKey: ['post', session?.id ?? 0, session?.board.boardId ?? 0, session?.postId ?? 0],
    enabled: session !== null && session.postId !== null,
    staleTime: Infinity,
    queryFn: async ({ signal }) => {
      if (!session || session.postId === null) throw new Error('게시글이 선택되지 않았습니다.')
      return bodyOrThrow<PostDetail>(await api.get(session.board.boardId, session.postId, signal), '게시글 상세 조회에 실패했습니다.')
    },
  })
  const detail: PostDetail | null = session === null ? null : view.saved ?? detailQuery.data ?? null
  const detailRef = useRef<PostDetail | null>(null)
  detailRef.current = detail

  const showModal = () => {
    if (modalEl.current) {
      $(modalEl.current).modal('show')
    }
  }

  const hideModal = () => {
    if (modalEl.current) {
      $(modalEl.current).modal('hide')
    }
  }

  /** 새 세션으로 모달을 연다. effect 안에서 부르지 않는다(flushSync가 동작하지 않는다). */
  const openModal = (nextBoard: MyBoard, postId: number | null, mode: ModalMode, modeLabel: string, prepare?: () => Partial<View>) => {
    const ed = editor.current
    ed?.invalidate()   // 다른 글로 전환할 때 진행 중인 본문 이미지 업로드를 버린다
    ed?.setUploadUrl(contentImagesUrl(nextBoard.boardId))
    const next: Session = { id: ++sessionSeq.current, board: nextBoard, postId }
    flushSync(() => update({ session: next, mode, modeLabel, saved: null, alert: null, success: null, ...prepare?.() }))
    showModal()
  }

  /** 편집기와 폼을 채운다. 본문은 교체(삽입 아님 — 업로드 무효화·undo 이력 초기화 포함)다. */
  const fillForm = (forBoard: MyBoard, source: PostDetail | null): Partial<View> => {
    const ed = editor.current
    ed?.setHtml(source?.content ?? '')
    ed?.setImageUploadEnabled(imagesAllowed(forBoard))
    const imageIds = ed?.getImageIds() ?? []
    return { form: reconcileCover(source ? formOf(source) : emptyForm(), imageIds), imageIds }
  }

  const openCreate = () => {
    const current = viewRef.current.board
    if (!current || !actionAllowed(current, 'CREATE')) return
    openModal(current, null, 'create', '새 게시글을 작성합니다. (' + current.name + ')', () => fillForm(current, null))
  }

  const openDetail = (id: number) => {
    const current = viewRef.current.board
    if (!current) return
    openModal(current, id, 'view', '게시글 정보를 확인·수정합니다. (' + current.name + ')')
  }

  const onEdit = () => {
    const s = viewRef.current.session
    const current = detailRef.current
    if (!s || !current) return
    update({ mode: 'edit', alert: null, success: null, ...fillForm(s.board, current) })
  }

  const onCancel = () => {
    const v = viewRef.current
    update({ alert: null })
    editor.current?.invalidate()
    if (v.mode === 'edit' && detailRef.current) {
      update({ mode: 'view' })
    } else {
      hideModal()
    }
  }

  const onSave = async () => {
    const { session: s, mode, form } = viewRef.current
    const ed = editor.current
    if (!s || !ed || viewRef.current.saving === s) return
    update({ alert: null, success: null })
    if (ed.isBusy()) {
      // 업로드가 끝나기 전에 저장하면 그 이미지가 본문·참조에서 빠진다
      update({ alert: '이미지 업로드가 끝난 뒤 저장해 주세요.' })
      return
    }
    const boardId = s.board.boardId
    const allowed = periodAllowed(s.board)
    const original = detailRef.current
    update({ saving: s })
    try {
      let data: PostDetail
      if (mode === 'create') {
        data = bodyOrThrow<PostDetail>(await api.create(boardId, buildCreateBody(form, ed, allowed)), '게시글 생성에 실패했습니다.')
      } else {
        if (!original) return
        const body = buildEditBody(form, original, ed, allowed)
        if (Object.keys(body).length === 0) {
          throw new Error('변경된 내용이 없습니다.')
        }
        data = bodyOrThrow<PostDetail>(await api.update(boardId, original.id, body), '게시글 수정에 실패했습니다.')
      }
      reloadPosts()
      if (isCurrent(s)) {
        update({ saved: data, mode: 'view', success: '저장되었습니다. (' + data.title + ')' })
      }
    } catch (error) {
      if (isCurrent(s)) {
        update({ alert: error instanceof Error && error.message ? error.message : '저장 중 오류가 발생했습니다.' })
      }
    } finally {
      if (viewRef.current.saving === s) {
        update({ saving: null })
      }
    }
  }

  const onDelete = async () => {
    const s = viewRef.current.session
    const current = detailRef.current
    if (!s || !current || !window.confirm('선택한 게시글을 삭제하시겠습니까?')) return
    update({ alert: null, success: null })
    const result = await api.remove(s.board.boardId, current.id)
    if (!isOk(result)) {
      if (isCurrent(s)) {
        const status = result.kind === 'http' ? result.status : null
        update({ alert: deleteErrorMessage(failureMessage(result, '게시글 삭제에 실패했습니다.'), status, s.board) })
      }
      return
    }
    reloadPosts()
    // 휴지통이 열려 있으면 방금 삭제한 글이 거기 나타나도록 함께 다시 읽는다(닫혀 있으면 열 때 새로 조회한다)
    if (viewRef.current.trash.open && viewRef.current.board === s.board) {
      reloadTrash()
    }
    if (isCurrent(s)) {
      hideModal()
    }
  }

  // ===================== 휴지통 =====================

  const setTrash = (patch: Partial<TrashState>) => update({ trash: { ...viewRef.current.trash, ...patch } })

  const reloadTrash = () => {
    const v = viewRef.current
    if (v.board) {
      void refresh(client, trashKey(v.board.boardId, v.trash.page))
    }
  }

  const onToggleTrash = () => update({ trash: viewRef.current.trash.open ? CLOSED_TRASH : { ...CLOSED_TRASH, open: true } })

  const onRestore = async (item: DeletedPost) => {
    const current = viewRef.current.board
    if (restoring.current || !current) return
    if (!window.confirm('「' + item.title + '」 글을 복원하시겠습니까?\n비노출·상단 고정 해제 상태로 복원되며, 공개하려면 복원 뒤 노출을 켜야 합니다.')) return
    restoring.current = true
    update({ restoringId: item.id })
    setTrash({ alert: null, success: null })
    const result = await api.restore(current.boardId, item.id)
    restoring.current = false
    update({ restoringId: null })
    if (viewRef.current.board !== current) {
      return   // 요청 사이 게시판을 바꿨다 — 서버에는 반영됐을 수 있지만 지금 화면의 상태를 건드리지 않는다
    }
    if (!isOk(result)) {
      setTrash({ alert: failureMessage(result, '게시글 복원에 실패했습니다.'), success: null })
      if (result.kind === 'http' && result.status === 404) {
        reloadTrash()   // 이미 복원됐거나 사라진 글 — 최신 목록으로 맞춘다
      }
      return
    }
    setTrash({ alert: null, success: '「' + item.title + '」 글을 비노출 상태로 복원했습니다. 공개하려면 목록에서 노출을 켜세요.' })
    reloadTrash()
    reloadPosts()
  }

  // ===================== 수명 =====================

  // 편집기는 한 번만 만든다. 게시판을 고르거나 모달을 열 때 setUploadUrl로 업로드 대상(출처)만 바꾼다.
  useEffect(() => {
    const host = editorHost.current
    if (!host || editor.current) return
    const container = document.createElement('div')
    container.id = 'formContentEditor'
    host.appendChild(container)
    editor.current = window.NoticeEditor.create(container, {
      onChange: () => {
        const imageIds = editor.current?.getImageIds() ?? []
        update({ imageIds, form: reconcileCover(viewRef.current.form, imageIds) })
      },
      pickLibraryImage: canPickLibrary
        ? (insert) => window.MediaPicker.open({ title: '본문에 넣을 이미지 선택', onPick: insert })
        : null,
      onBusyChange: (busy) => update({ editorBusy: busy }),
      onError: (message) => update({ alert: message }),
    })
  }, [])

  useEffect(() => {
    const el = modalEl.current
    if (!el) return
    const target = $(el)
    target.on('hidden.bs.modal.postReact', () => {
      editor.current?.invalidate()
      update({ session: null, mode: null, saved: null, alert: null, success: null })
    })
    return () => target.off('.postReact')
  }, [])

  const initialized = useRef(false)
  useEffect(() => {
    if (initialized.current) return
    initialized.current = true
    void (async () => {
      let boards: MyBoard[]
      try {
        boards = await client.fetchQuery({
          queryKey: ['boards'],
          queryFn: async ({ signal }) => bodyOrThrow<MyBoard[]>(await api.myBoards(signal), '게시판 목록 조회에 실패했습니다.'),
        })
      } catch (error) {
        update({ boardsError: error instanceof Error ? error.message : '게시판 목록 조회 중 오류가 발생했습니다.' })
        return
      }
      update({ boards })
      // 상단바 검색 결과 링크(?boardId=&id=)로 들어오면 그 게시판을 고르고 게시글 상세를 바로 연다.
      const query = new URLSearchParams(location.search)
      const wantedBoard = query.get('boardId')
      const wantedPost = query.get('id')
      const initial = boards.find((b) => String(b.boardId) === wantedBoard) ?? boards[0]
      if (!initial) return
      selectBoard(initial)
      if (wantedPost !== null && /^[1-9]\d{0,15}$/.test(wantedPost) && String(initial.boardId) === wantedBoard) {
        query.delete('id')
        query.delete('boardId')
        const rest = query.toString()
        history.replaceState(null, '', location.pathname + (rest ? '?' + rest : ''))   // 새로고침·뒤로 가기에서 다시 열리지 않게
        openDetail(Number(wantedPost))
      }
    })()
  }, [])

  // ===================== 렌더링 =====================

  const onSearch = (event: FormEvent) => {
    event.preventDefault()
    loadPosts(0)
  }

  const boards = view.boards ?? []
  const modalBoard = session?.board ?? null
  const editing = view.mode === 'edit' || view.mode === 'create'
  const savingHere = session !== null && view.saving === session
  const detailFailed = session !== null && detail === null && detailQuery.isError && !detailQuery.isFetching
  const modalAlert = view.alert ?? (detailFailed && detailQuery.error ? detailQuery.error.message : null)

  return (
    <>
      <div className="d-sm-flex align-items-center justify-content-between mb-4">
        <h1 className="h3 mb-0 text-gray-800">게시글 관리</h1>
        <div className="page-actions">
          <button type="button" className={'btn btn-primary btn-sm shadow-sm' + (actionAllowed(board, 'CREATE') ? '' : ' d-none')} id="btnNewPost" onClick={openCreate}>
            <i className="fas fa-plus fa-sm mr-1"></i>새 게시글
          </button>
        </div>
      </div>

      <div className="admin-manage-shell">
        <div id="boardWriteStoppedNote" className={'alert alert-warning' + (board?.writableYn === false ? '' : ' d-none')} role="status">
          쓰기가 중지된 게시판입니다. 조회·첨부 삭제·게시글 삭제만 할 수 있습니다.
        </div>
        <div id="pageAlert" className={'alert alert-danger' + (pageAlert === null ? ' d-none' : '')} role="alert">{pageAlert ?? ''}</div>

        <div className="card shadow mb-0 admin-panel">
          <div className="card-header">
            <div>
              <h6>게시판 선택</h6>
              <p>조회 권한이 있는 게시판의 게시글만 관리할 수 있습니다.</p>
            </div>
          </div>
          <div className="card-body">
            <div className="filter-grid">
              <div className="form-group mb-0">
                <label className="form-label" htmlFor="boardSelect">게시판</label>
                <select className="form-control" id="boardSelect" disabled={boards.length === 0} value={board ? String(board.boardId) : ''}
                  onChange={(e) => {
                    const next = boards.find((b) => String(b.boardId) === e.target.value)
                    if (next) selectBoard(next)
                  }}>
                  {boards.map((b) => <option key={b.boardId} value={String(b.boardId)}>{'#' + b.boardId + ' ' + b.name + (b.publicYn ? '' : ' (비공개)')}</option>)}
                </select>
              </div>
            </div>
            <div id="noBoardState" className={'empty-state mt-3' + (view.boards !== null && boards.length === 0 ? '' : ' d-none')}>
              조회할 수 있는 게시판이 없습니다. 게시판이 없거나 권한이 부여되지 않았습니다.
            </div>
          </div>
        </div>

        <div id="postArea" className={board ? '' : 'd-none'}>
          <div className="card shadow mb-4 admin-panel">
            <div className="card-header">
              <div>
                <h6>검색 조건</h6>
                <p>제목, 노출 여부로 게시글을 조회합니다.</p>
              </div>
            </div>
            <div className="card-body">
              <form id="searchForm" onSubmit={onSearch}>
                <div className="filter-grid">
                  <div className="form-group mb-0">
                    <label className="form-label" htmlFor="searchKeyword">제목</label>
                    <input type="text" className="form-control" id="searchKeyword" name="keyword" maxLength={200} value={view.search.keyword}
                      onChange={(e) => update({ search: { ...viewRef.current.search, keyword: e.target.value } })} />
                  </div>
                  <div className="form-group mb-0">
                    <label className="form-label" htmlFor="searchUseYn">노출 여부</label>
                    <select className="form-control" id="searchUseYn" name="useYn" value={view.search.useYn}
                      onChange={(e) => update({ search: { ...viewRef.current.search, useYn: e.target.value } })}>
                      <option value="">전체</option>
                      <option value="true">노출</option>
                      <option value="false">비노출</option>
                    </select>
                  </div>
                </div>
                <div className="filter-actions mt-4">
                  <button type="submit" className="btn btn-primary">
                    <i className="fas fa-search fa-sm mr-1"></i>조회
                  </button>{' '}
                  <button type="button" className="btn btn-light border" id="btnReset" onClick={() => {
                    update({ search: EMPTY_SEARCH })
                    loadPosts(0)
                  }}>초기화</button>
                </div>
              </form>
            </div>
          </div>

          <PostList status={listStatus} data={!listOutOfRange && listData ? listData : lastList} onOpen={openDetail} onPage={loadPosts} />

          {board && (
            <TrashPanel api={api} board={board} trash={view.trash} visible={actionAllowed(board, 'DELETE')} restoringId={view.restoringId}
              onToggle={onToggleTrash} onPage={(page) => setTrash({ page })} onRestore={(item) => void onRestore(item)} />
          )}
        </div>
      </div>

      {createPortal(
        <PostModal modalRef={modalEl} board={modalBoard} mode={view.mode} modeLabel={view.modeLabel}
          loading={session !== null && view.mode === 'view' && detail === null && detailQuery.isFetching}
          hasDetail={detail !== null} alert={modalAlert} success={view.success}
          saveDisabled={view.editorBusy || savingHere} cancelDisabled={savingHere}
          onDelete={() => void onDelete()} onEdit={onEdit} onSave={() => void onSave()} onCancel={onCancel}>
          <PostView detail={detail} visible={view.mode === 'view' && detail !== null} />
          <PostForm visible={editing} board={modalBoard} detail={detail} form={view.form} imageIds={view.imageIds} editorBusy={view.editorBusy}
            editorHostRef={editorHost} onForm={(patch) => update({ form: { ...viewRef.current.form, ...patch } })} />
          {/* 첨부는 게시글이 생성된 뒤에만 다룬다 — 작성 중에는 업로드할 게시글 번호가 없다 */}
          <div id="attachmentSection" className={'attachment-section' + (detail ? '' : ' d-none')}>
            {session && detail && <Attachments key={session.id} api={api} board={session.board} postId={detail.id} sessionId={session.id} />}
          </div>
        </PostModal>,
        modalContainer,
      )}
    </>
  )
}
