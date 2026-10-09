package com.cms.publicweb.notice.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.publicweb.board.dto.PublicBoardListResult;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.publicweb.board.dto.PublicPostAttachmentRef;
import com.cms.publicweb.board.dto.PublicPostDetail;
import com.cms.publicweb.board.service.PublicBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 공개(비로그인) 공지 조회. 공지는 "공지 게시판"({@code board_key='NOTICE'})의 게시글이므로(PLAN-notice-to-board.md 쟁점 8) 공개
 * 불변식(게시판 {@code publicYn ∧ ¬deleted} ∧ 게시글 {@code useYn ∧ ¬deleted} ∧ 소속)은 {@link PublicBoardService} 한 곳에만 있고, 이
 * 클래스는 공지 게시판 ID를 찾아 위임하는 얇은 어댑터다. 공개 URL({@code /notices/{id}} 등)과 404 계약을 보존하려고 유지한다.
 *
 * <p>공지 게시판이 없거나(키 없음) 삭제됐거나 비공개면 모든 메서드가 empty이고 컨트롤러가 같은 404로 흡수한다(fail-closed).
 * ID 조회는 요청마다 인덱스(UNIQUE {@code board_key}) 한 번이다.
 */
@Service
@RequiredArgsConstructor
public class PublicNoticeService {

    private final BoardRepository boardRepository;
    private final PublicBoardService publicBoardService;

    /** 공개 목록. 공지 게시판이 공개 상태가 아니면 empty — 컨트롤러가 404로 응답한다(빈 목록 200이나 예외 500으로 번역하지 않는다). */
    @Transactional(readOnly = true)
    public Optional<PublicBoardListResult> getPublishedNotices(int page, String rawKeyword) {
        return noticeBoardId().flatMap(boardId -> publicBoardService.getPublishedPosts(boardId, page, rawKeyword));
    }

    @Transactional(readOnly = true)
    public Optional<PublicPostDetail> findPublishedNotice(Long id) {
        return noticeBoardId().flatMap(boardId -> publicBoardService.findPublishedPost(boardId, id));
    }

    /** 다운로드 시점 재검증(트랜잭션) — 보장 수준과 약한 TOCTOU 계약은 {@link PublicBoardService#findPublishedAttachment}와 같다. */
    @Transactional(readOnly = true)
    public Optional<PublicPostAttachmentRef> findPublishedAttachment(Long noticeId, Long attachmentId) {
        return noticeBoardId().flatMap(boardId -> publicBoardService.findPublishedAttachment(boardId, noticeId, attachmentId));
    }

    /**
     * 파일을 스트림으로 연다. <b>트랜잭션 없음</b>(의도) — 열린 스트림이 트랜잭션 프록시를 통과하지 않게 {@link #findPublishedAttachment}와
     * 분리돼 있다. 반환된 값은 받은 쪽이 반드시 닫는다.
     */
    public Optional<PublicPostAttachmentDownload> openAttachment(PublicPostAttachmentRef ref) {
        return publicBoardService.openAttachment(ref);
    }

    private Optional<Long> noticeBoardId() {
        return boardRepository.findIdByBoardKey(Board.NOTICE_KEY);
    }
}
