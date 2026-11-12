package com.cms.admin.popup.controller;

import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequirePermission;
import com.cms.admin.popup.dto.request.PopupCreateRequest;
import com.cms.admin.popup.dto.request.PopupUpdateRequest;
import com.cms.admin.popup.dto.response.PopupImageDownload;
import com.cms.admin.popup.dto.response.PopupResponse;
import com.cms.admin.popup.service.PopupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * 팝업 관리 API. 경로가 {@code POPUP} URL 게이트({@code /admin/api/popups/**}) 안이라 게이트는 READ까지만 보고, 동작별 판정은 핸들러의
 * {@link RequirePermission}이 한다(PLAN-popup.md 쟁점 3). 동작 분류: 목록·상세·이미지 보기 = READ, 등록 = CREATE, 수정 = UPDATE, 삭제 = DELETE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/popups")
@Tag(name = "Admin Popup", description = "공개 메인 팝업 관리 API (기능 단위 위임)")
public class PopupController {

    private final PopupService popupService;

    @Operation(summary = "팝업 목록 조회", description = "노출 여부·기간과 무관하게 전부, 최근 등록 순. 본문(contentHtml)은 싣지 않는다. status는 서버 시각 기준")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "팝업 조회 권한 없음")
    @GetMapping
    @RequirePermission(feature = AdminFeature.POPUP, action = PermissionAction.READ)
    public ResponseEntity<List<PopupResponse>> getPopups() {
        return ResponseEntity.ok(popupService.getPopups());
    }

    @Operation(summary = "팝업 단건 조회", description = "HTML형 본문(contentHtml) 포함")
    @ApiResponse(responseCode = "404", description = "팝업 없음")
    @GetMapping("/{id}")
    @RequirePermission(feature = AdminFeature.POPUP, action = PermissionAction.READ)
    public ResponseEntity<PopupResponse> getPopup(@PathVariable Long id) {
        return ResponseEntity.ok(popupService.getPopup(id));
    }

    @Operation(summary = "팝업 이미지 미리보기", description = "노출 여부·기간과 무관하게 저장된 이미지를 돌려준다(관리자 전용 — 공개 경로는 /popups/{id}/image)")
    @ApiResponse(responseCode = "404", description = "팝업 또는 이미지 파일 없음(HTML형 포함)")
    @GetMapping("/{id}/image")
    @RequirePermission(feature = AdminFeature.POPUP, action = PermissionAction.READ)
    public ResponseEntity<byte[]> getImage(@PathVariable Long id) {
        PopupImageDownload download = popupService.getImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(download.content());
    }

    @Operation(summary = "팝업 등록", description = "multipart/form-data. 이미지형은 이미지 2MB 이하, HTML형은 본문. 총 30개까지(소프트 상한). 링크는 /경로 또는 http(s):// 만 허용")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청값·이미지·본문 검증 실패(종류별 필수·금지 필드, 링크 형식, 기간, 배치 범위)")
    @ApiResponse(responseCode = "403", description = "팝업 등록 권한 없음")
    @ApiResponse(responseCode = "409", description = "팝업 개수 상한 도달")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(feature = AdminFeature.POPUP, action = PermissionAction.CREATE)
    public ResponseEntity<PopupResponse> createPopup(@Valid @ModelAttribute PopupCreateRequest request) {
        PopupResponse response = popupService.createPopup(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "팝업 수정", description = "전체 교체(PUT). 종류·이미지는 바꾸지 않는다. 링크는 null/빈 값이면 해제, 노출 시작·종료·높이는 null만 해제(빈 문자열 400)")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "팝업 없음")
    @PutMapping("/{id}")
    @RequirePermission(feature = AdminFeature.POPUP, action = PermissionAction.UPDATE)
    public ResponseEntity<PopupResponse> updatePopup(@PathVariable Long id, @Valid @RequestBody PopupUpdateRequest request) {
        return ResponseEntity.ok(popupService.updatePopup(id, request));
    }

    @Operation(summary = "팝업 삭제", description = "행을 지우고 커밋 후 이미지 파일도 지운다(복구 불가)")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "팝업 없음")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.POPUP, action = PermissionAction.DELETE)
    public ResponseEntity<Void> deletePopup(@PathVariable Long id) {
        popupService.deletePopup(id);
        return ResponseEntity.noContent().build();
    }
}
