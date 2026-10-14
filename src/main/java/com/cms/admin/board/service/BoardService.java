package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.dto.request.BoardCreateRequest;
import com.cms.admin.board.dto.request.BoardUpdateRequest;
import com.cms.admin.board.dto.response.BoardResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.permission.MemberBoardPermissionRepository;
import com.cms.admin.permission.PermissionChangedEvent;
import com.cms.admin.site.ContentSiteScope;
import com.cms.admin.site.SiteScope;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 게시판 정의 관리(ADMIN 전용, PLAN-board.md 쟁점 6). 삭제는 살아 있는 게시글이 없을 때만 가능하다 — 삭제 API는 이 검사와 함께 PR B에서 처음
 * 생겼다(검사 없는 삭제가 먼저 배포되면 PR B → PR A 롤백 중에 게시글이 있는 게시판을 지울 수 있다, 리뷰 R1-5).
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    private static final String NOT_FOUND_MESSAGE = "게시판을 찾을 수 없습니다.";
    private static final String NOTICE_BOARD_EXISTS_MESSAGE = "이 사이트의 공지 게시판이 이미 있습니다.";

    private final BoardRepository boardRepository;
    private final PostRepository postRepository;
    private final MemberBoardPermissionRepository memberBoardPermissionRepository;
    private final ContentSiteScope contentSiteScope;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<BoardResponse> getBoards() {
        return boardRepository.findByDeletedFalseOrderByIdAsc().stream().map(BoardResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoard(Long id) {
        return BoardResponse.from(boardRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE)));
    }

    /** 공지 게시판 ID(시스템 키 {@link Board#NOTICE_KEY}). 없으면 404 — 옛 공지 화면 주소 리다이렉트가 쓴다. */
    @Transactional(readOnly = true)
    public Long getNoticeBoardId() {
        return boardRepository.findIdByBoardKey(Board.NOTICE_KEY)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BOARD_CREATE, targetType = "BOARD", targetIdExpression = "id")
    public BoardResponse createBoard(BoardCreateRequest request) {
        String name = requireNonBlank(request.getName());
        Long siteId = SiteScope.parse(request.getSiteScope());   // 누락 = 공통(null)
        boolean noticeBoard = Boolean.TRUE.equals(request.getNoticeBoard());
        if (noticeBoard) {
            if (siteId == null) {
                throw new InvalidRequestException("사이트 공지 게시판은 표시 대상 사이트를 골라야 합니다.");
            }
            if (!Boolean.TRUE.equals(request.getPublicYn())) {
                throw new InvalidRequestException("공지 게시판은 공개 게시판이어야 합니다.");
            }
        }
        // 사이트 행 FOR SHARE — 사이트 삭제와 직렬화된다(지정이 먼저면 삭제 409, 삭제가 먼저면 지정 400)
        contentSiteScope.requireAssignable(siteId);
        String boardKey = noticeBoard ? Board.noticeKeyFor(siteId) : null;
        if (boardKey != null && boardRepository.existsByBoardKey(boardKey)) {
            throw new ConflictException(NOTICE_BOARD_EXISTS_MESSAGE);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Board board = Board.builder()
                .name(name)
                .publicYn(request.getPublicYn())
                .attachmentYn(request.getAttachmentYn())
                .deleted(false)
                .boardKey(boardKey)
                .siteId(siteId)
                .createDate(now)
                .updateDate(now)
                .build();
        try {
            // 사전 검사를 통과한 동시 생성은 UNIQUE(board_key)가 막는다 — flush에서 잡아 500이 아니라 같은 409로 번역한다
            return BoardResponse.from(boardRepository.saveAndFlush(board));
        } catch (DataIntegrityViolationException e) {
            if (boardKey != null) {
                throw new ConflictException(NOTICE_BOARD_EXISTS_MESSAGE);
            }
            throw e;
        }
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BOARD_UPDATE, targetType = "BOARD", targetIdExpression = "id")
    public BoardResponse updateBoard(Long id, BoardUpdateRequest request) {
        if (request.getName() == null && request.getPublicYn() == null && request.getAttachmentYn() == null
                && request.getSiteScope() == null) {
            throw new InvalidRequestException("변경할 필드가 없습니다.");
        }
        // 표시 대상은 요청이 siteScope를 명시한 경우에만 바꾼다(누락 = 유지) — 낡은 화면이 대상을 되돌리려면 선택을 바꿔 명시해야 한다
        boolean siteRequested = request.getSiteScope() != null;
        Long requestedSiteId = siteRequested ? SiteScope.parse(request.getSiteScope()) : null;
        Board board = boardRepository.findByIdAndDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        // 시스템 게시판(공지)은 공개 /notices가 의존하므로 비공개로 바꿀 수 없다(이름·첨부 허용은 변경 가능)
        if (board.isSystem() && Boolean.FALSE.equals(request.getPublicYn())) {
            throw new InvalidRequestException("시스템 게시판은 비공개로 바꿀 수 없습니다.");
        }
        boolean siteChanged = siteRequested && !Objects.equals(board.getSiteId(), requestedSiteId);
        if (siteChanged) {
            // 공지 게시판은 키가 사이트를 품고 있어 표시 대상을 바꿀 수 없다
            if (board.isSystem()) {
                throw new InvalidRequestException("공지 게시판의 표시 대상은 바꿀 수 없습니다.");
            }
            // 게시판 행 FOR UPDATE → 사이트 행 FOR SHARE 순서 (사이트 삭제는 게시판 행을 잠그지 않는다)
            contentSiteScope.requireAssignable(requestedSiteId);
        }
        String name = request.getName() != null ? requireNonBlank(request.getName()) : null;
        LocalDateTime now = LocalDateTime.now(clock);
        board.update(name, request.getPublicYn(), request.getAttachmentYn(), now);
        if (siteChanged) {
            board.changeSite(requestedSiteId, now);
        }
        return BoardResponse.from(board);
    }

    /**
     * 게시판 소프트 삭제. 게시판 행 `FOR UPDATE` → 살아 있는(미삭제) 게시글이 있으면 409 → 삭제 + 그 게시판의 권한 행 전부 삭제.
     * 잠금 순서는 게시판 → 권한 행이라, 권한 저장(회원 → 게시판 `FOR SHARE`)·게시글 생성(게시판 `FOR SHARE`)과 순환이 없고 직렬화된다.
     * 버전은 올리지 않는다 — 낡은 권한 화면의 PUT은 게시판 존재 확인에서 400이다. 지운 행이 있으면 캐시를 폐기한다
     * (`PermissionChangedEvent`의 회원 ID는 무효화에 쓰이지 않는다 — 여러 회원의 행이 한꺼번에 지워지므로 null).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BOARD_DELETE, targetType = "BOARD", targetIdExpression = "id")
    public BoardResponse deleteBoard(Long id) {
        Board board = boardRepository.findByIdAndDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        // 공통 공지 게시판만 삭제 불가 — 사이트 전용 공지 게시판은 글이 없으면 지울 수 있다(막으면 그 사이트를 지울 수 없다, PLAN-site-menu.md PR ④ T3)
        if (board.isCommonNotice()) {
            throw new ConflictException("시스템 게시판은 삭제할 수 없습니다.");
        }
        if (postRepository.existsByBoardIdAndDeletedFalse(id)) {
            throw new ConflictException("게시글이 남아있어 삭제할 수 없습니다. 게시글을 먼저 삭제해주세요.");
        }
        board.softDelete(LocalDateTime.now(clock));
        if (memberBoardPermissionRepository.deleteByBoardId(id) > 0) {
            eventPublisher.publishEvent(new PermissionChangedEvent(null));
        }
        return BoardResponse.from(board);
    }

    private static String requireNonBlank(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidRequestException("게시판 이름은 공백일 수 없습니다.");
        }
        return trimmed;
    }
}
