package com.cms.admin.search.controller;

import com.cms.admin.search.dto.AdminSearchRequest;
import com.cms.admin.search.dto.AdminSearchResponse;
import com.cms.admin.search.service.AdminSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/search-results")
@Tag(name = "Admin Search", description = "상단바 통합 검색 API")
public class AdminSearchController {

    private final AdminSearchService adminSearchService;

    @Operation(summary = "통합 검색",
            description = "메뉴·공지사항·관리자 계정을 한 번에 검색한다. 현재 사용자 권한으로 볼 수 있는 섹션만 응답에 포함하며(권한이 없는 섹션은 키 생략), "
                    + "섹션당 최대 5건과 전체 건수를 돌려준다. 검색어는 trim 후 2자 미만이면 빈 결과다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "검색어가 100자를 초과")
    @ApiResponse(responseCode = "403", description = "권한 없음")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<AdminSearchResponse> search(@Valid @ModelAttribute AdminSearchRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(adminSearchService.search(request.getKeyword(), authentication));
    }
}
