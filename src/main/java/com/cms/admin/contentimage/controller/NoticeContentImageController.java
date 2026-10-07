package com.cms.admin.contentimage.controller;

import com.cms.admin.contentimage.dto.ContentImageUploadResponse;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;

/**
 * 공지 편집기 본문 이미지 업로드. 경로가 기존 {@code NOTICE} URL 게이트({@code /admin/api/notices/**}) 안이라
 * {@code SecurityConfig} 변경이 없다. 선언은 READ이고 CREATE∨UPDATE 재판정은 서비스가 한다(PLAN-html-editor.md 쟁점 10).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/notices/content-images")
@Tag(name = "Admin Notice Content Image", description = "공지 본문 이미지 업로드 API")
public class NoticeContentImageController {

    private final ContentImageService contentImageService;

    @Operation(summary = "공지 본문 이미지 업로드",
            description = "png·jpeg·gif, 파일당 5MB, 한 변 4096px 이하, 애니메이션 불가. 응답의 url을 본문 img src로 쓴다. "
                    + "공지 작성(CREATE) 또는 수정(UPDATE) 권한이 필요하다.")
    @ApiResponse(responseCode = "201", description = "업로드 성공 — Location은 공개 이미지 경로")
    @ApiResponse(responseCode = "400", description = "이미지 형식·크기·애니메이션 검증 실패")
    @ApiResponse(responseCode = "403", description = "공지 작성·수정 권한 없음")
    @ApiResponse(responseCode = "409", description = "본문 이미지 전체 용량·개수 상한 도달")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(feature = AdminFeature.NOTICE, action = PermissionAction.READ) // CREATE∨UPDATE는 서비스에서 재판정
    public ResponseEntity<ContentImageUploadResponse> upload(@RequestPart("file") MultipartFile file) {
        ContentImageUploadResponse response = contentImageService.uploadForNotice(file);
        return ResponseEntity.created(URI.create(response.url())).body(response);
    }
}
