package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.ReauthenticationRequest;
import com.cms.config.auth.ReauthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 민감 작업 재인증 API(PLAN-sensitive-reauthentication.md 쟁점 6). 경로는 상시 허용 게이트 {@code /admin/api/members/me/**} 안이라
 * {@code SecurityConfig} 변경이 없다. 재인증 자체이므로 {@code @RequireFullAuthentication}을 붙이지 않는다({@code FullAuthenticationConventionTest} 면제).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/members/me")
@Tag(name = "Admin Member", description = "관리자 계정 관리 API")
public class AdminReauthenticationController {

    private final ReauthenticationService reauthenticationService;

    @Operation(summary = "재인증(현재 비밀번호 확인)",
            description = "로그인 상태 유지(쿠키 자동 로그인)로 접속한 세션을 비밀번호 확인으로 완전 인증 세션으로 바꾼다(세션 ID가 바뀐다). "
                    + "이미 비밀번호로 로그인한 세션은 확인만 한다. 오답은 로그인 실패와 같은 횟수에 들어간다.")
    @ApiResponse(responseCode = "204", description = "재인증 성공")
    @ApiResponse(responseCode = "400", description = "검증 실패 또는 비밀번호 불일치(REAUTHENTICATION_FAILED — 다시 시도 가능)")
    @ApiResponse(responseCode = "401", description = "세션 종료(잠금·비활성·세션 폐기 경합) — 다시 로그인 필요")
    @PostMapping("reauthentication")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> reauthenticate(@Valid @RequestBody ReauthenticationRequest request, HttpServletRequest servletRequest) {
        reauthenticationService.reauthenticate(request.getPassword(), servletRequest);
        return ResponseEntity.noContent().build();
    }
}
