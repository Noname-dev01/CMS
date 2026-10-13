package com.cms.admin.site.controller;

import com.cms.admin.site.dto.request.SiteCreateRequest;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.dto.response.SiteResponse;
import com.cms.admin.site.service.SiteService;
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

/** 사이트 관리 API — 전부 ADMIN 전용(위임 불가, PLAN-site-menu.md). 사이트 관리 화면은 정적 페이지·메뉴 관리 화면의 "사이트" 탭이다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/sites")
@Tag(name = "Admin Site", description = "사이트 관리 API")
public class SiteController {

    private final SiteService siteService;

    @Operation(summary = "사이트 목록 조회", description = "비활성 포함 전체. 사이트별 메뉴 수와 시드 여부를 함께 돌려준다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SiteResponse>> getSites() {
        return ResponseEntity.ok(siteService.getSites());
    }

    @Operation(summary = "사이트 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "사이트 없음")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SiteResponse> getSite(@PathVariable Long id) {
        return ResponseEntity.ok(siteService.getSite(id));
    }

    @Operation(summary = "공개 사이트 생성", description = "종류는 항상 PUBLIC이다. 키는 형식·예약어를 검사하고 생성 후 바꿀 수 없다.")
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(형식·예약어)")
    @ApiResponse(responseCode = "409", description = "이미 사용 중인 사이트 키")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SiteResponse> createSite(@Valid @RequestBody SiteCreateRequest request) {
        SiteResponse response = siteService.createSite(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getId())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "사이트 수정", description = "이름·외부 링크 허용·사용 여부만 바꾼다(null은 기존값 유지). 키·종류는 바꿀 수 없다.")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "관리자 사이트에 외부 링크 허용 시도 등")
    @ApiResponse(responseCode = "404", description = "사이트 없음")
    @ApiResponse(responseCode = "409", description = "시드 사이트 비활성화 또는 외부 링크 메뉴가 있는 사이트의 외부 링크 허용 해제")
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SiteResponse> updateSite(@PathVariable Long id, @Valid @RequestBody SiteUpdateRequest request) {
        return ResponseEntity.ok(siteService.updateSite(id, request));
    }

    @Operation(summary = "사이트 삭제", description = "하드 삭제(복구 불가). 시드 사이트와 메뉴가 있는 사이트는 삭제할 수 없다 — 비활성화한다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "사이트 없음")
    @ApiResponse(responseCode = "409", description = "시드 사이트이거나 메뉴가 있어 삭제 거부")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSite(@PathVariable Long id) {
        siteService.deleteSite(id);
        return ResponseEntity.noContent().build();
    }
}
