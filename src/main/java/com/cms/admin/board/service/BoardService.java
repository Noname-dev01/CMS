package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.dto.request.BoardCreateRequest;
import com.cms.admin.board.dto.request.BoardUpdateRequest;
import com.cms.admin.board.dto.response.BoardResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 게시판 정의 관리(ADMIN 전용, PLAN-board.md 쟁점 6). 삭제는 게시글 존재 검사와 함께 PR B에서 추가한다 — 검사 없는 삭제가
 * 먼저 배포되면 PR B → PR A 롤백 중에 게시글이 있는 게시판을 지울 수 있기 때문이다(리뷰 R1-5).
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    private static final String NOT_FOUND_MESSAGE = "게시판을 찾을 수 없습니다.";

    private final BoardRepository boardRepository;
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

    private static String requireNonBlank(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidRequestException("게시판 이름은 공백일 수 없습니다.");
        }
        return trimmed;
    }
}
