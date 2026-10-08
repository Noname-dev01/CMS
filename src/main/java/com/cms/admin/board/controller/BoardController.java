package com.cms.admin.board.controller;

import com.cms.admin.board.dto.request.BoardCreateRequest;
import com.cms.admin.board.dto.request.BoardUpdateRequest;
import com.cms.admin.board.dto.response.BoardResponse;
import com.cms.admin.board.service.BoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * 게시판 정의 API(ADMIN 전용). {@code /admin/api/boards}·{@code /admin/api/boards/{id}}는 어떤 카탈로그 게이트에도 걸리지 않아
 * {@code /admin/**} ADMIN 캐치올이 막고, 핸들러도 {@code hasRole('ADMIN')}을 선언한다(PLAN-board.md 쟁점 2·8).
 * 게시글 경로({@code /admin/api/boards/{boardId}/posts/**}, PR B)는 게시판 게이트로 MANAGER에게 열린다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/boards")
@Tag(name = "Admin Board", description = "게시판 정의 API (ADMIN 전용)")
public class BoardController {

    private final BoardService boardService;

    @Operation(summary = "게시판 목록 조회", description = "삭제되지 않은 게시판 전체(id 순)")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BoardResponse>> getBoards() {
        return ResponseEntity.ok(boardService.getBoards());
    }

    @Operation(summary = "게시판 상세 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "게시판 없음(삭제 포함)")
    @GetMapping("/{boardId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BoardResponse> getBoard(@PathVariable Long boardId) {
        return ResponseEntity.ok(boardService.getBoard(boardId));
    }

    @Operation(summary = "게시판 생성")
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BoardResponse> createBoard(@Valid @RequestBody BoardCreateRequest request) {
        BoardResponse response = boardService.createBoard(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.getId()).toUri()).body(response);
    }

    @Operation(summary = "게시판 부분 수정", description = "null 필드는 기존값 유지. 이름 공백·전체 필드 누락은 400")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "게시판 없음(삭제 포함)")
    @PatchMapping("/{boardId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BoardResponse> updateBoard(@PathVariable Long boardId,
                                                     @Valid @RequestBody BoardUpdateRequest request) {
        return ResponseEntity.ok(boardService.updateBoard(boardId, request));
    }

    @Operation(summary = "게시판 삭제(소프트)", description = "살아 있는 게시글이 없을 때만 삭제된다. 그 게시판의 MANAGER 권한 행도 함께 지운다")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "게시판 없음(삭제 포함)")
    @ApiResponse(responseCode = "409", description = "게시글이 남아 있음")
    @DeleteMapping("/{boardId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBoard(@PathVariable Long boardId) {
        boardService.deleteBoard(boardId);
        return ResponseEntity.noContent().build();
    }
}
