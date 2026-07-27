package com.cms.admin.notice.controller;

import com.cms.admin.notice.dto.response.NoticeAttachmentDownload;
import com.cms.admin.notice.dto.response.NoticeAttachmentResponse;
import com.cms.admin.notice.service.NoticeAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/notices/{noticeId}/attachments")
@Tag(name = "Admin Notice Attachment", description = "공지사항 첨부파일 관리 API")
public class NoticeAttachmentController {

    private final NoticeAttachmentService noticeAttachmentService;

    @Operation(summary = "공지사항 첨부파일 업로드", description = "허용 확장자: pdf/doc(x)/xls(x)/ppt(x)/hwp/txt/csv/zip/png/jpg/jpeg/gif. 파일당 최대 10MB, 공지당 최대 5개.")
    @ApiResponse(responseCode = "201", description = "업로드 성공")
    @ApiResponse(responseCode = "400", description = "허용되지 않는 확장자·크기 초과 등 요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "공지사항 없음")
    @ApiResponse(responseCode = "409", description = "첨부파일 5개 초과")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NoticeAttachmentResponse> upload(
            @PathVariable Long noticeId,
            @RequestPart("file") MultipartFile file
    ) {
        NoticeAttachmentResponse response = noticeAttachmentService.upload(noticeId, file);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "공지사항 첨부파일 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "공지사항 없음")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<List<NoticeAttachmentResponse>> list(@PathVariable Long noticeId) {
        return ResponseEntity.ok(noticeAttachmentService.list(noticeId));
    }

    @Operation(summary = "공지사항 첨부파일 다운로드")
    @ApiResponse(responseCode = "200", description = "다운로드 성공")
    @ApiResponse(responseCode = "404", description = "공지사항 또는 첨부파일 없음")
    @GetMapping("/{attachmentId}/content")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<byte[]> content(@PathVariable Long noticeId, @PathVariable Long attachmentId) {
        NoticeAttachmentDownload download = noticeAttachmentService.download(noticeId, attachmentId);

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.content());
    }

    @Operation(summary = "공지사항 첨부파일 삭제")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "공지사항 또는 첨부파일 없음")
    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable Long noticeId, @PathVariable Long attachmentId) {
        noticeAttachmentService.delete(noticeId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
