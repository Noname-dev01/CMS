import { describe, expect, it } from 'vitest'
import type { MyBoard } from '../../api/post'
import { actionAllowed, coverTypeNote, deleteErrorMessage, pinPolicy } from './board'

const board = (patch: Partial<MyBoard> = {}): MyBoard => ({
  boardId: 1, name: 'b', publicYn: true, attachmentYn: true, writableYn: true, pinMaxCount: 0, attachMaxCount: 5, attachMaxSizeMb: 10,
  attachExtensions: ['txt'], bodyImageYn: true, publishPeriodYn: true, boardType: 'LIST', actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'], ...patch,
})

describe('허용 동작', () => {
  it('쓰기 중지면 생성·수정만 닫는다', () => {
    const stopped = board({ writableYn: false })
    expect(actionAllowed(stopped, 'CREATE')).toBe(false)
    expect(actionAllowed(stopped, 'UPDATE')).toBe(false)
    expect(actionAllowed(stopped, 'DELETE')).toBe(true)
  })

  it('권한이 없거나 게시판이 없으면 닫는다', () => {
    expect(actionAllowed(board({ actions: ['READ'] }), 'DELETE')).toBe(false)
    expect(actionAllowed(null, 'READ')).toBe(false)
  })
})

describe('안내', () => {
  it('고정은 상한이 있거나 이미 고정된 글일 때만 보인다', () => {
    expect(pinPolicy(board({ pinMaxCount: 2 }), false)).toMatchObject({ visible: true })
    expect(pinPolicy(board(), false).visible).toBe(false)
    expect(pinPolicy(board(), true)).toEqual({ visible: true, note: '고정이 꺼진 게시판입니다. 해제만 할 수 있습니다.' })
  })

  it('갤러리·웹진만 유형 안내가 있고, 이미지를 끈 게시판은 고르라는 꼬리를 뺀다', () => {
    expect(coverTypeNote(board({ boardType: 'WEBZINE' }))).toContain('웹진형입니다.')
    expect(coverTypeNote(board({ boardType: 'GALLERY' }))).toContain("'대표 이미지'에서 고르세요.")
    expect(coverTypeNote(board({ boardType: 'GALLERY', bodyImageYn: false }))).not.toContain('고르세요')
    expect(coverTypeNote(board())).toBe('')
    expect(coverTypeNote(board({ boardType: 'toString' }))).toBe('')
  })

  it('첨부가 남은 삭제(409)는 수정 권한이 없을 때만 안내를 덧붙인다', () => {
    expect(deleteErrorMessage('남음', 409, board({ actions: ['READ', 'DELETE'] }))).toContain('수정 권한이 필요합니다.')
    expect(deleteErrorMessage('남음', 409, board())).toBe('남음')
    expect(deleteErrorMessage('오류', 500, board({ actions: ['DELETE'] }))).toBe('오류')
  })
})
