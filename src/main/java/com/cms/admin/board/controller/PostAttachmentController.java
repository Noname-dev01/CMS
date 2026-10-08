package com.cms.admin.board.controller;

import com.cms.admin.board.dto.response.PostAttachmentDownload;
import com.cms.admin.board.dto.response.PostAttachmentResponse;
import com.cms.admin.board.service.PostAttachmentService;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequireBoardPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 게시글 첨부 관리 API. 동작 분류는 공지 첨부(U4)와 같다 — 업로드·삭제는 게시글 내용 수정이라 UPDATE, 목록·다운로드는 READ.
 * 게시글 조회는 {@code (postId, boardId)}, 첨부 조회는 {@code (attachmentId, postId)} 조합이라 다른 게시판·게시글의 ID를 경로에 넣어도 같은 404다(IDOR).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/boards/{boardId}/posts/{postId}/attachments")
@Tag(name = "Admin Post Attachment", description = "게시글 첨부파일 관리 API (게시판별 권한)")
public class PostAttachmentController {

    private final PostAttachmentService postAttachmentService;

    @Operation(summary = "첨부파일 업로드", description = "게시글당 최대 5개, 파일당 10MB. 게시판이 첨부를 허용하지 않으면(attachmentYn=false) 400")
    @ApiResponse(responseCode = "201", description = "업로드 성공")
    @ApiResponse(responseCode = "400", description = "파일 검증 실패 또는 첨부 미허용 게시판")
    @ApiResponse(responseCode = "403", description = "그 게시판의 수정 권한 없음")
    @ApiResponse(responseCode = "409", description = "게시글당 첨부 개수 상한 도달")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireBoardPermission(action = PermissionAction.UPDATE)
    public ResponseEntity<PostAttachmentResponse> upload(
            @PathVariable Long boardId,
            @PathVariable Long postId,
            @RequestPart("file") MultipartFile file
    ) {
        PostAttachmentResponse response = postAttachmentService.upload(boardId, postId, file);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "첨부파일 목록 조회")
    @GetMapping
    @RequireBoardPermission(action = PermissionAction.READ)
    public ResponseEntity<List<PostAttachmentResponse>> list(@PathVariable Long boardId, @PathVariable Long postId) {
        return ResponseEntity.ok(postAttachmentService.list(boardId, postId));
    }

    @Operation(summary = "첨부파일 다운로드", description = "응답 Content-Type은 항상 application/octet-stream으로 강제한다")
    @GetMapping("/{attachmentId}/content")
    @RequireBoardPermission(action = PermissionAction.READ)
    public ResponseEntity<byte[]> content(@PathVariable Long boardId, @PathVariable Long postId, @PathVariable Long attachmentId) {
        PostAttachmentDownload download = postAttachmentService.download(boardId, postId, attachmentId);

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.content());
    }

    @Operation(summary = "첨부파일 삭제", description = "첨부 삭제도 게시글 수정(UPDATE)이다 — DELETE만 가진 MANAGER는 첨부 있는 게시글을 못 지운다")
    @DeleteMapping("/{attachmentId}")
    @RequireBoardPermission(action = PermissionAction.UPDATE)
    public ResponseEntity<Void> delete(@PathVariable Long boardId, @PathVariable Long postId, @PathVariable Long attachmentId) {
        postAttachmentService.delete(boardId, postId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
