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
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 게시판 정의 관리(ADMIN 전용, PLAN-board.md 쟁점 6). 삭제는 살아 있는 게시글이 없을 때만 가능하다 — 삭제 API는 이 검사와 함께 PR B에서 처음
 * 생겼다(검사 없는 삭제가 먼저 배포되면 PR B → PR A 롤백 중에 게시글이 있는 게시판을 지울 수 있다, 리뷰 R1-5).
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    private static final String NOT_FOUND_MESSAGE = "게시판을 찾을 수 없습니다.";

    private final BoardRepository boardRepository;
    private final PostRepository postRepository;
    private final MemberBoardPermissionRepository memberBoardPermissionRepository;
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

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BOARD_CREATE, targetType = "BOARD", targetIdExpression = "id")
    public BoardResponse createBoard(BoardCreateRequest request) {
        String name = requireNonBlank(request.getName());
        LocalDateTime now = LocalDateTime.now(clock);
        Board saved = boardRepository.save(Board.builder()
                .name(name)
                .publicYn(request.getPublicYn())
                .attachmentYn(request.getAttachmentYn())
                .deleted(false)
                .createDate(now)
                .updateDate(now)
                .build());
        return BoardResponse.from(saved);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.BOARD_UPDATE, targetType = "BOARD", targetIdExpression = "id")
    public BoardResponse updateBoard(Long id, BoardUpdateRequest request) {
        if (request.getName() == null && request.getPublicYn() == null && request.getAttachmentYn() == null) {
            throw new InvalidRequestException("변경할 필드가 없습니다.");
        }
        Board board = boardRepository.findByIdAndDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        String name = request.getName() != null ? requireNonBlank(request.getName()) : null;
        board.update(name, request.getPublicYn(), request.getAttachmentYn(), LocalDateTime.now(clock));
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
