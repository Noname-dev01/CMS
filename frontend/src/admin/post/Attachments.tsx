/**
 * 첨부 영역 — 게시글이 실제 생성된 뒤에만 다룬다. 모달 세션마다 새로 마운트되므로(key) 늦게 끝난 업로드·삭제는 언마운트된 쪽에서 버려지고
 * 새로 연 글의 파일 선택·목록·버튼을 건드리지 않는다. 쓰기 요청 자체는 중단하지 않는다.
 */
import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { attachmentUrl, type MyBoard, type PostApi, type PostAttachment } from '../../api/post'
import { actionAllowed } from './board'
import { fileSizeText } from './format'
import { bodyOrThrow, failureMessage, isOk, refresh } from './queries'

interface Props {
  api: PostApi
  board: MyBoard
  postId: number
  sessionId: number
}

export function Attachments({ api, board, postId, sessionId }: Props) {
  const client = useQueryClient()
  const queryKey = ['attachments', sessionId, board.boardId, postId]
  const query = useQuery({
    queryKey,
    queryFn: async ({ signal }) => bodyOrThrow<PostAttachment[]>(await api.attachments(board.boardId, postId, signal), '첨부파일 목록 조회에 실패했습니다.'),
  })
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const canUpdate = actionAllowed(board, 'UPDATE')
  const uploadAllowed = canUpdate && board.attachmentYn
  const extensions = Array.isArray(board.attachExtensions) ? board.attachExtensions : []
  const failed = query.isError && !query.isFetching
  const items = failed ? [] : query.data
  const alert = error ?? (failed ? query.error.message : null)

  const onUpload = async () => {
    const file = fileInput.current?.files?.[0]
    if (!file) {
      setError('업로드할 파일을 선택해주세요.')
      return
    }
    if (uploading) return
    setError(null)
    setUploading(true)
    const result = await api.uploadAttachment(board.boardId, postId, file)
    if (!alive.current) return
    if (isOk(result)) {
      if (fileInput.current) {
        fileInput.current.value = ''
      }
      await refresh(client, queryKey)
    } else {
      setError(failureMessage(result, '첨부파일 업로드에 실패했습니다.'))
    }
    setUploading(false)
  }

  const onRemove = async (attachmentId: number) => {
    if (!window.confirm('선택한 첨부파일을 삭제하시겠습니까?')) return
    setError(null)
    const result = await api.removeAttachment(board.boardId, postId, attachmentId)
    if (!alive.current) return
    if (isOk(result)) {
      await refresh(client, queryKey)
    } else {
      setError(failureMessage(result, '첨부파일 삭제에 실패했습니다.'))
    }
  }

  return (
    <>
      <h6 className="attachment-section-title">첨부파일</h6>
      <ul id="attachmentList" className="attachment-list">
        {items?.length === 0 && <li className="attachment-empty text-muted">첨부된 파일이 없습니다.</li>}
        {items?.map((item) => (
          <li key={item.id} className="attachment-item" data-id={item.id}>
            <a href={attachmentUrl(board.boardId, postId, item.id)} className="attachment-name">{item.originalFilename}</a>
            <span className="attachment-size">{'(' + fileSizeText(item.fileSize) + ' · 다운로드 ' + (item.downloadCount ?? 0) + '회)'}</span>
            {/* 쓰기 중지 게시판에서도 첨부 삭제는 남는다 — 첨부가 남은 글은 삭제할 수 없어서다 */}
            {board.actions.includes('UPDATE') && (
              <button type="button" className="btn btn-link text-danger btn-sm ml-auto js-delete-attachment" data-id={item.id} onClick={() => void onRemove(item.id)}>삭제</button>
            )}
          </li>
        ))}
      </ul>
      <small id="attachmentPolicyNote" className={'form-text text-muted' + (uploadAllowed ? '' : ' d-none')}>
        {'최대 ' + board.attachMaxCount + '개 · 파일당 ' + board.attachMaxSizeMb + 'MB · 허용 형식: ' + extensions.join(', ')}
      </small>
      <div className={'attachment-upload' + (uploadAllowed ? '' : ' d-none')} id="attachmentUpload">
        <input type="file" className="form-control-file" id="attachmentFileInput" ref={fileInput} accept={extensions.map((e) => '.' + e).join(',')} />
        <button type="button" className="btn btn-outline-primary btn-sm" id="btnUploadAttachment" disabled={uploading} onClick={() => void onUpload()}>
          <i className="fas fa-upload fa-sm mr-1"></i>업로드
        </button>
      </div>
      <small id="attachmentDisabledNote" className={'form-text text-muted' + (board.attachmentYn || !canUpdate ? ' d-none' : '')}>
        이 게시판은 새 첨부 업로드를 허용하지 않습니다. 기존 첨부는 조회·삭제할 수 있습니다.
      </small>
      <div id="attachmentAlert" className={'alert alert-danger mt-2' + (alert === null ? ' d-none' : '')} role="alert">{alert ?? ''}</div>
    </>
  )
}
