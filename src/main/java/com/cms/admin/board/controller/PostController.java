package com.cms.admin.board.controller;

import com.cms.admin.board.dto.request.PostCreateRequest;
import com.cms.admin.board.dto.request.PostSearchRequest;
import com.cms.admin.board.dto.request.PostUpdateRequest;
import com.cms.admin.board.dto.response.PostPageResponse;
import com.cms.admin.board.dto.response.PostResponse;
import com.cms.admin.board.service.PostService;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequireBoardPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 게시글 관리 API. 경로가 {@code BOARD} URL 게이트({@code /admin/api/boards/&#42;/posts/**}) 안이라 게이트는 "어느 게시판이든 READ"까지만 보고,
 * 게시판별 판정은 핸들러의 {@link RequireBoardPermission}이 한다 — 판정에 쓰는 경로 변수 이름은 반드시 {@code boardId}여야 한다
 * (PLAN-board.md 쟁점 3, 컨벤션 테스트가 고정). 동작 분류는 공지 U4와 같다: 목록·상세 = READ, 생성 = CREATE, 수정 = UPDATE, 삭제 = DELETE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/boards/{boardId}/posts")
@Tag(name = "Admin Post", description = "게시글 관리 API (게시판별 권한)")
public class PostController {

    private final PostService postService;

    @Operation(summary = "게시글 목록 조회", description = "keyword(제목 부분 일치)·useYn 필터. 페이지 크기는 100으로 제한된다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "그 게시판의 조회 권한 없음")
    @ApiResponse(responseCode = "404", description = "게시판 없음(삭제 포함)")
    @GetMapping
    @RequireBoardPermission(action = PermissionAction.READ)
    public ResponseEntity<PostPageResponse> getPosts(
            @PathVariable Long boardId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @Valid @ModelAttribute PostSearchRequest request
    ) {
        return ResponseEntity.ok(postService.getPosts(boardId, request, pageable));
    }

    @Operation(summary = "게시글 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "그 게시판의 조회 권한 없음")
    @ApiResponse(responseCode = "404", description = "게시판·게시글 없음(삭제·다른 게시판 소속 포함)")
    @GetMapping("/{postId}")
    @RequireBoardPermission(action = PermissionAction.READ)
    public ResponseEntity<PostResponse> getPost(@PathVariable Long boardId, @PathVariable Long postId) {
        return ResponseEntity.ok(postService.getPost(boardId, postId));
    }

    @Operation(summary = "게시글 생성")
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(본문 형식 표식·길이·다른 출처 이미지 포함)")
    @ApiResponse(responseCode = "403", description = "그 게시판의 작성 권한 없음")
    @ApiResponse(responseCode = "404", description = "게시판 없음(삭제 포함)")
    @PostMapping
    @RequireBoardPermission(action = PermissionAction.CREATE)
    public ResponseEntity<PostResponse> createPost(@PathVariable Long boardId, @Valid @RequestBody PostCreateRequest request) {
        PostResponse response = postService.createPost(boardId, request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "게시글 부분 수정", description = "null 필드는 기존값 유지. 전체 필드 누락·공백은 400")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "403", description = "그 게시판의 수정 권한 없음")
    @ApiResponse(responseCode = "404", description = "게시판·게시글 없음")
    @PatchMapping("/{postId}")
    @RequireBoardPermission(action = PermissionAction.UPDATE)
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable Long boardId,
            @PathVariable Long postId,
            @Valid @RequestBody PostUpdateRequest request
    ) {
        return ResponseEntity.ok(postService.updatePost(boardId, postId, request));
    }

    @Operation(summary = "게시글 삭제(소프트)", description = "첨부파일이 남아 있으면 409 — 첨부를 먼저 삭제해야 한다")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "403", description = "그 게시판의 삭제 권한 없음")
    @ApiResponse(responseCode = "404", description = "게시판·게시글 없음")
    @ApiResponse(responseCode = "409", description = "첨부파일이 남아 있음")
    @DeleteMapping("/{postId}")
    @RequireBoardPermission(action = PermissionAction.DELETE)
    public ResponseEntity<Void> deletePost(@PathVariable Long boardId, @PathVariable Long postId) {
        postService.deletePost(boardId, postId);
        return ResponseEntity.noContent().build();
    }
}
