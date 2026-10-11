package com.cms.admin.page.controller;

import com.cms.admin.page.dto.request.SitePageCreateRequest;
import com.cms.admin.page.dto.request.SitePageUpdateRequest;
import com.cms.admin.page.dto.response.SitePageDetailResponse;
import com.cms.admin.page.dto.response.SitePageSummaryResponse;
import com.cms.admin.page.service.SitePageService;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * 정적 페이지 관리 API. 경로가 {@code SITE_PAGE} URL 게이트({@code /admin/api/site-pages/**}) 안이라 게이트는 READ까지만 보고, 동작별 판정은
 * 핸들러의 {@link RequirePermission}이 한다(PLAN-static-page.md 쟁점 9). 동작 분류: 목록·상세 = READ, 등록 = CREATE, 수정 = UPDATE, 삭제 = DELETE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/site-pages")
@Tag(name = "Admin Site Page", description = "정적 페이지(약관·방침 등) 관리 API (기능 단위 위임)")
public class SitePageController {

    private final SitePageService sitePageService;

    @Operation(summary = "정적 페이지 목록 조회", description = "노출 여부와 무관하게 전부, 최근에 만든 순. 본문은 싣지 않는다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "정적 페이지 조회 권한 없음")
    @GetMapping
    @RequirePermission(feature = AdminFeature.SITE_PAGE, action = PermissionAction.READ)
    public ResponseEntity<List<SitePageSummaryResponse>> getPages() {
        return ResponseEntity.ok(sitePageService.getPages());
    }

    @Operation(summary = "정적 페이지 상세 조회", description = "현재 개정의 제목·본문과 최근 개정 요약(최대 20건)")
    @ApiResponse(responseCode = "404", description = "페이지 없음")
    @GetMapping("/{id}")
    @RequirePermission(feature = AdminFeature.SITE_PAGE, action = PermissionAction.READ)
    public ResponseEntity<SitePageDetailResponse> getPage(@PathVariable Long id) {
        return ResponseEntity.ok(sitePageService.getPage(id));
    }

    @Operation(summary = "정적 페이지 등록", description = "슬러그는 소문자·숫자·하이픈 2~50자, 생성 후 변경 불가. 본문 이미지는 제거된다")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(슬러그 형식·예약어, 본문 형식·길이)")
    @ApiResponse(responseCode = "403", description = "정적 페이지 등록 권한 없음")
    @ApiResponse(responseCode = "409", description = "이미 사용 중인 슬러그")
    @PostMapping
    @RequirePermission(feature = AdminFeature.SITE_PAGE, action = PermissionAction.CREATE)
    public ResponseEntity<SitePageDetailResponse> createPage(@Valid @RequestBody SitePageCreateRequest request) {
        SitePageDetailResponse response = sitePageService.createPage(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "정적 페이지 수정", description = "전체 교체(PUT). 제목·본문이 바뀌면 새 개정이 쌓이고, 노출·푸터만 바뀌면 개정 없이 버전만 오른다")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패")
    @ApiResponse(responseCode = "404", description = "페이지 없음")
    @ApiResponse(responseCode = "409", description = "version 불일치(그 사이 다른 관리자가 저장함)")
    @PutMapping("/{id}")
    @RequirePermission(feature = AdminFeature.SITE_PAGE, action = PermissionAction.UPDATE)
    public ResponseEntity<SitePageDetailResponse> updatePage(@PathVariable Long id, @Valid @RequestBody SitePageUpdateRequest request) {
        return ResponseEntity.ok(sitePageService.updatePage(id, request));
    }

    @Operation(summary = "정적 페이지 삭제", description = "개정 이력까지 모두 지운다(복구 불가). 화면이 가진 version이 필요하다")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "400", description = "version 누락")
    @ApiResponse(responseCode = "404", description = "페이지 없음")
    @ApiResponse(responseCode = "409", description = "version 불일치(그 사이 다른 관리자가 저장함)")
    @DeleteMapping("/{id}")
    @RequirePermission(feature = AdminFeature.SITE_PAGE, action = PermissionAction.DELETE)
    public ResponseEntity<Void> deletePage(@PathVariable Long id, @RequestParam(required = false) Integer version) {
        sitePageService.deletePage(id, version);
        return ResponseEntity.noContent().build();
    }
}
