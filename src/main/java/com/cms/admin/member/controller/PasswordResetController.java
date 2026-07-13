package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.PasswordResetConfirmRequest;
import com.cms.admin.member.dto.request.PasswordResetRequestRequest;
import com.cms.admin.member.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 재설정 공개 API — 비로그인 접근 허용 (SecurityConfig permitAll).
 * CSRF는 이 경로에도 활성이므로 페이지 JS가 X-CSRF-TOKEN 헤더를 보내야 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api")
@Tag(name = "Password Reset", description = "비밀번호 재설정 API (공개)")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Operation(summary = "비밀번호 재설정 메일 발송 요청",
            description = "가입 이메일로 재설정 링크를 발송한다. "
                    + "이메일 존재 여부·계정 상태와 무관하게 항상 200을 반환한다(계정 열거 방지). "
                    + "발급 후 60초 이내 재요청은 조용히 무시된다.")
    @ApiResponse(responseCode = "200", description = "요청 접수 (발송 여부는 노출하지 않음)")
    @ApiResponse(responseCode = "400", description = "이메일 형식 검증 실패")
    @PostMapping("password-reset-requests")
    public ResponseEntity<Void> requestReset(@Valid @RequestBody PasswordResetRequestRequest request,
                                             HttpServletRequest servletRequest) {
        passwordResetService.requestReset(request.getEmail(), extractClientIp(servletRequest));
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "토큰으로 비밀번호 재설정",
            description = "메일 링크의 토큰으로 새 비밀번호를 설정한다. 토큰은 일회용이며 30분 후 만료된다. "
                    + "무효/만료/사용됨은 구분 없이 동일한 400으로 응답한다.")
    @ApiResponse(responseCode = "204", description = "재설정 성공 (기존 세션은 만료 처리)")
    @ApiResponse(responseCode = "400", description = "토큰 무효/만료 또는 비밀번호 검증 실패")
    @PostMapping("password-resets")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.resetPassword(
                request.getToken(), request.getNewPassword(), request.getConfirmPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * IP를 추출한다. X-FORWARDED-FOR(마지막 홉) → X-Real-IP → RemoteAddr 순으로 시도한다.
     * AdminActionLogAspect.getClientIp()와 동일 로직(기존 두 곳 모두 private이라 직접 재사용 불가).
     * 공개 엔드포인트에서 이 헤더들은 위조 가능하므로 참고 로그 전용이다.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-FORWARDED-FOR");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] ips = xForwardedFor.split(",");
            return ips[ips.length - 1].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }
}
