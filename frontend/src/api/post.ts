/**
 * 게시글 관리 API(`/admin/api/boards/{boardId}/posts`)와 내 게시판 목록의 타입·호출 함수. 화면 무관 모듈이다 — 서버 DTO(`MyBoardResponse`·
 * `PostResponse`·`PostSummaryResponse`·`PostAttachmentResponse`·`DeletedPostSummaryResponse`·`PostCreateRequest`·`PostUpdateRequest`)와 같은 모양을 유지한다.
 * 서버 JSON은 null 필드를 생략한다(non_null) — nullable 필드는 선택 속성이다.
 * 403은 화면이 고정 문구를 쓰므로 본문을 읽지 않고, 본문이 필요 없는 쓰기는 2xx 헤더로 성공을 판정한다(PLAN-react-admin-posts.md 결정 3).
 */
import type { HttpClient, HttpResult, RequestOptions } from './http'

export type PostAction = 'READ' | 'CREATE' | 'UPDATE' | 'DELETE'

export interface MyBoard {
  boardId: number
  name: string
  publicYn: boolean
  attachmentYn: boolean
  writableYn: boolean
  pinMaxCount: number
  attachMaxCount: number
  attachMaxSizeMb: number
  /** 실효 허용 확장자(항상 있음) */
  attachExtensions: string[]
  bodyImageYn: boolean
  publishPeriodYn: boolean
  /** LIST·GALLERY·WEBZINE(해석값) */
  boardType: string
  /** 버튼 표시용 — 서버 판정(403)이 최종이다 */
  actions: PostAction[]
}

export interface PostSummary {
  id: number
  boardId: number
  title: string
  useYn: boolean
  pinned: boolean
  authorId?: string
  createDate?: string
  updateDate?: string
  updaterId?: string
  publishStart?: string
  publishEnd?: string
  /** 서버 Clock 기준: HIDDEN·SCHEDULED·EXPIRED·ACTIVE */
  status: string
  viewCount: number
}

export interface PostDetail extends PostSummary {
  /** 서버가 출력 시 sanitizer를 거친 본문 HTML */
  content: string
  coverImageId?: number
}

export interface PostAttachment {
  id: number
  originalFilename: string
  fileSize?: number
  downloadCount?: number
}

export interface DeletedPost {
  id: number
  title: string
  authorId?: string
  createDate?: string
  deletedAt?: string
  deletedBy?: string
}

export interface PageOf<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** 각 값은 "yyyy-MM-ddTHH:mm" 또는 null(그쪽 열림). 서버가 통째로 교체한다. */
export interface PublishPeriod {
  start: string | null
  end: string | null
}

export interface PostCreateBody {
  title: string
  content: string
  /** 이 본문은 편집기 HTML이라는 표식 — 없으면 서버가 거부한다 */
  contentFormat: 'HTML'
  useYn: boolean
  pinned?: true
  coverImageId?: number
  publishPeriod?: PublishPeriod
}

/** 바뀐 필드만 담는다(키 없음 = 유지). coverImageId의 null은 해제다. */
export interface PostUpdateBody {
  title?: string
  content?: string
  contentFormat?: 'HTML'
  useYn?: boolean
  pinned?: boolean
  coverImageId?: number | null
  publishPeriod?: PublishPeriod
}

export interface PostListParams {
  keyword: string
  useYn: string
  page: number
}

const PAGE_SIZE = 20
const TRASH_PAGE_SIZE = 10

export const postsUrl = (boardId: number) => `/admin/api/boards/${encodeURIComponent(String(boardId))}/posts`
/** 편집기 본문 이미지의 업로드 주소(게시판 출처) */
export const contentImagesUrl = (boardId: number) => `/admin/api/boards/${encodeURIComponent(String(boardId))}/content-images`
export const attachmentUrl = (boardId: number, postId: number, attachmentId: number) =>
  `${postsUrl(boardId)}/${encodeURIComponent(String(postId))}/attachments/${encodeURIComponent(String(attachmentId))}/content`

export function createPostApi(http: HttpClient) {
  const request = (url: string, options: RequestOptions = {}): Promise<HttpResult> => http.request(url, { ...options, skipForbiddenBody: true })
  const one = (boardId: number, postId: number) => `${postsUrl(boardId)}/${encodeURIComponent(String(postId))}`
  const attachments = (boardId: number, postId: number) => `${one(boardId, postId)}/attachments`
  return {
    myBoards: (signal?: AbortSignal) => request('/admin/api/members/me/boards', { signal }),
    list: (boardId: number, params: PostListParams, signal?: AbortSignal) => {
      const query = new URLSearchParams()
      const keyword = params.keyword.trim()
      if (keyword) query.set('keyword', keyword)
      if (params.useYn) query.set('useYn', params.useYn)
      query.set('page', String(params.page))
      query.set('size', String(PAGE_SIZE))
      return request(`${postsUrl(boardId)}?${query.toString()}`, { signal })
    },
    get: (boardId: number, postId: number, signal?: AbortSignal) => request(one(boardId, postId), { signal }),
    create: (boardId: number, body: PostCreateBody) => request(postsUrl(boardId), { method: 'POST', json: body }),
    update: (boardId: number, postId: number, body: PostUpdateBody) => request(one(boardId, postId), { method: 'PATCH', json: body }),
    remove: (boardId: number, postId: number) => request(one(boardId, postId), { method: 'DELETE', skipSuccessBody: true }),
    attachments: (boardId: number, postId: number, signal?: AbortSignal) => request(attachments(boardId, postId), { signal }),
    uploadAttachment: (boardId: number, postId: number, file: File) => {
      const form = new FormData()
      form.append('file', file)
      return request(attachments(boardId, postId), { method: 'POST', form, skipSuccessBody: true })
    },
    removeAttachment: (boardId: number, postId: number, attachmentId: number) =>
      request(`${attachments(boardId, postId)}/${encodeURIComponent(String(attachmentId))}`, { method: 'DELETE', skipSuccessBody: true }),
    trash: (boardId: number, page: number, signal?: AbortSignal) =>
      request(`${postsUrl(boardId)}/trash?page=${page}&size=${TRASH_PAGE_SIZE}`, { signal }),
    restore: (boardId: number, postId: number) => request(`${one(boardId, postId)}/restoration`, { method: 'POST', skipSuccessBody: true }),
  }
}

export type PostApi = ReturnType<typeof createPostApi>
