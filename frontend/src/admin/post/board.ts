/** 게시판 정책에 따른 버튼·안내(순수). 표시용일 뿐 서버 판정이 최종이다. */
import type { MyBoard, PostAction } from '../../api/post'

/** 쓰기 중지(보관) 게시판은 생성·수정만 닫는다 — 보관 게시판의 글을 지울 수 있어야 하므로 삭제와 첨부 삭제는 그대로다. */
export function actionAllowed(board: MyBoard | null, action: PostAction): boolean {
  if (board === null || !board.actions.includes(action)) {
    return false
  }
  return !((action === 'CREATE' || action === 'UPDATE') && board.writableYn === false)
}

/** 고정을 끈 게시판에서도 이미 고정된 글은 해제할 수 있어야 한다. */
export function pinPolicy(board: MyBoard | null, alreadyPinned: boolean): { visible: boolean; note: string } {
  const max = board?.pinMaxCount ?? 0
  return {
    visible: max > 0 || alreadyPinned,
    note: max > 0
      ? '이 게시판은 최대 ' + max + '개까지 고정할 수 있습니다. 공개 목록 1페이지(검색하지 않을 때) 맨 위에 보입니다.'
      : '고정이 꺼진 게시판입니다. 해제만 할 수 있습니다.',
  }
}

const COVER_TYPE_NOTES = new Map([
  ['GALLERY', '이 게시판은 갤러리형입니다. 공개 일반 목록에 글마다 대표 이미지가 격자로 보이고, 대표를 고르지 않은 글은 빈 자리표시로 보입니다.'],
  ['WEBZINE', '이 게시판은 웹진형입니다. 공개 일반 목록에 대표 이미지가 보이고(본문에 글이 있으면 요약도 함께), 대표를 고르지 않은 글은 빈 자리표시로 보입니다.'],
])

export const imagesAllowed = (board: MyBoard | null) => board === null || board.bodyImageYn !== false
export const periodAllowed = (board: MyBoard | null) => board === null || board.publishPeriodYn !== false

/** 갤러리·웹진 안내. 이미지를 끈 게시판에는 "넣은 뒤 고르세요"를 뺀다. */
export function coverTypeNote(board: MyBoard | null): string {
  const note = board ? COVER_TYPE_NOTES.get(board.boardType) : undefined
  return note ? note + (imagesAllowed(board) ? " 본문에 이미지를 넣은 뒤 아래 '대표 이미지'에서 고르세요." : '') : ''
}

/** 첨부가 남은 글은 첨부부터 지워야 하는데(409), 첨부 삭제는 "수정" 권한이라 없으면 안내를 따를 수 없다. */
export function deleteErrorMessage(message: string, status: number | null, board: MyBoard): string {
  return status === 409 && !board.actions.includes('UPDATE')
    ? message + ' 첨부 삭제에는 이 게시판의 수정 권한이 필요합니다. 관리자에게 요청하세요.'
    : message
}
