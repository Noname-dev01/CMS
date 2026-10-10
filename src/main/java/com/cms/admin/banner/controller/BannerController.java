package com.cms.admin.banner.controller;

import com.cms.admin.banner.dto.request.BannerCreateRequest;
import com.cms.admin.banner.dto.request.BannerOrderRequest;
import com.cms.admin.banner.dto.request.BannerUpdateRequest;
import com.cms.admin.banner.dto.response.BannerImageDownload;
import com.cms.admin.banner.dto.response.BannerResponse;
import com.cms.admin.banner.service.BannerService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequirePermission;
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
 * 배너 관리 API. 경로가 {@code BANNER} URL 게이트({@code /admin/api/banners/**}) 안이라 게이트는 READ까지만 보고, 동작별 판정은
 * 핸들러의 {@link RequirePermission}이 한다(PLAN-public-home-banner.md 쟁점 9). 동작 분류: 목록·상세·이미지 보기 = READ, 등록 = CREATE,
 * 수정·순서 저장 = UPDATE, 삭제 = DELETE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/banners")
@Tag(name = "Admin Banner", description = "공개 메인 배너 관리 API (기능 단위 위임)")
public class BannerController {

    private final BannerService bannerService;

    @Operation(summary = "배너 목록 조회", description = "노출 여부·기간과 무관하게 전부, 표시 순서대로. status는 서버 시각 기준")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "배너 조회 권한 없음")
    @GetMapping
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.READ)
    public ResponseEntity<List<BannerResponse>> getBanners() {
        return ResponseEntity.ok(bannerService.getBanners());
    }

    @Operation(summary = "배너 단건 조회")
    @ApiResponse(responseCode = "404", description = "배너 없음")
    @GetMapping("/{id}")
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.READ)
    public ResponseEntity<BannerResponse> getBanner(@PathVariable Long id) {
        return ResponseEntity.ok(bannerService.getBanner(id));
    }

    @Operation(summary = "배너 이미지 미리보기", description = "노출 여부·기간과 무관하게 저장된 이미지를 돌려준다(관리자 전용 — 공개 경로는 /banners/{id}/image)")
    @ApiResponse(responseCode = "404", description = "배너 또는 이미지 파일 없음")
    @GetMapping("/{id}/image")
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.READ)
    public ResponseEntity<byte[]> getImage(@PathVariable Long id) {
        BannerImageDownload download = bannerService.getImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(download.content());
    }

    @Operation(summary = "배너 등록", description = "multipart/form-data. 이미지 2MB 이하, 최대 10개. 링크는 /경로 또는 http(s):// 만 허용")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청값·이미지 검증 실패(링크 형식, 기간 역전, 이미지 형식·크기)")
    @ApiResponse(responseCode = "403", description = "배너 등록 권한 없음")
    @ApiResponse(responseCode = "409", description = "배너 개수 상한 도달")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.CREATE)
    public ResponseEntity<BannerResponse> createBanner(@Valid @ModelAttribute BannerCreateRequest request) {
        BannerResponse response = bannerService.createBanner(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "배너 순서 저장", description = "전체 배너 ID를 표시 순서대로 보낸다. 집합이 현재와 다르면 409")
    @ApiResponse(responseCode = "400", description = "중복·null 항목")
    @ApiResponse(responseCode = "409", description = "배너 집합이 변경됨(낡은 화면)")
    @PutMapping("/order")
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.UPDATE)
    public ResponseEntity<List<BannerResponse>> saveOrder(@Valid @RequestBody BannerOrderRequest request) {
        return ResponseEntity.ok(bannerService.saveOrder(request));
    }

    @Operation(summary = "배너 수정", description = "메타데이터 전체 교체(PUT). 링크·시작·종료가 null이면 해제. 이미지는 바꾸지 않는다")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "배너 없음")
    @PutMapping("/{id}")
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.UPDATE)
    public ResponseEntity<BannerResponse> updateBanner(@PathVariable Long id, @Valid @RequestBody BannerUpdateRequest request) {
        return ResponseEntity.ok(bannerService.updateBanner(id, request));
    }

    @Operation(summary = "배너 삭제", description = "행을 지우고 커밋 후 이미지 파일도 지운다(복구 불가)")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "배너 없음")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.BANNER, action = PermissionAction.DELETE)
    public ResponseEntity<Void> deleteBanner(@PathVariable Long id) {
        bannerService.deleteBanner(id);
        return ResponseEntity.noContent().build();
    }
}
