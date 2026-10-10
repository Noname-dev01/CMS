package com.cms.admin.session.controller;

import com.cms.admin.session.dto.response.SessionExpireResponse;
import com.cms.admin.session.dto.response.SessionExpireResult;
import com.cms.admin.session.dto.response.SessionListResponse;
import com.cms.admin.session.service.AdminSessionManageService;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * 세션 관리 API(영구 ADMIN 전용, PLAN-session-management.md). 경로는 어떤 카탈로그 게이트에도 걸리지 않아 {@code /admin/**} ADMIN 캐치올이
 * URL을 막고, 핸들러마다 {@code hasRole('ADMIN')}을 선언한다(인가 선언 컨벤션).
 *
 * <p>입력 검증은 이 컨트롤러가 {@link InvalidRequestException}(400, 고정 문구)으로 직접 한다 — 전역 핸들러에는 필수 {@code @RequestParam}
 * 누락({@code MissingServletRequestParameterException}) 처리가 없어 {@code required=true}로 두면 500이 되기 때문이다. 오류 문구에는 입력값을 싣지 않는다.
 * 서비스 진입 전에 거르는 400은 {@code @AdminActionLogged}가 닿지 못해 감사되지 않는다(다른 API와 같은 정책).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/sessions")
@Tag(name = "Admin Session", description = "접속 중 관리자 세션 조회·강제 만료 API (ADMIN 전용)")
public class AdminSessionController {

    private static final Pattern HANDLE_PATTERN = Pattern.compile("^[0-9a-f]{" + AdminSessionService.HANDLE_LENGTH + "}$");

    private final AdminSessionManageService adminSessionManageService;

    @Operation(summary = "활성 세션 목록", description = "만료되지 않은 세션을 회원별로 묶어 최근 요청 순으로 반환한다. 세션은 원문 ID가 아니라 해시 핸들로 식별한다")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SessionListResponse> getSessions(HttpServletRequest request) {
        return ResponseEntity.ok(adminSessionManageService.getSessions(currentSessionId(request)));
    }

    @Operation(summary = "세션 하나 강제 만료", description = "다음 요청부터 로그인이 필요해진다(best-effort). 현재 사용 중인 세션은 만료할 수 없다")
    @ApiResponse(responseCode = "204", description = "만료 처리됨")
    @ApiResponse(responseCode = "400", description = "핸들 형식 오류")
    @ApiResponse(responseCode = "404", description = "활성 세션 없음(이미 만료·소멸)")
    @ApiResponse(responseCode = "409", description = "현재 사용 중인 세션")
    @DeleteMapping("/{handle}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> expireSession(@PathVariable String handle, HttpServletRequest request) {
        if (!HANDLE_PATTERN.matcher(handle).matches()) {
            throw new InvalidRequestException("세션 식별자 형식이 올바르지 않습니다.");
        }
        adminSessionManageService.expireSession(handle, currentSessionId(request));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "회원의 세션 전부 강제 만료", description = "현재 사용 중인 세션은 호출자와 무관하게 항상 제외한다. 대상 세션이 없어도 200(expiredCount 0)")
    @ApiResponse(responseCode = "200", description = "처리 완료")
    @ApiResponse(responseCode = "400", description = "memberId 누락·0 이하")
    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SessionExpireResponse> expireMemberSessions(
            @RequestParam(required = false) Long memberId, HttpServletRequest request) {
        if (memberId == null || memberId <= 0) {
            throw new InvalidRequestException("memberId는 1 이상의 숫자여야 합니다.");
        }
        SessionExpireResult result = adminSessionManageService.expireMemberSessions(memberId, currentSessionId(request));
        return ResponseEntity.ok(new SessionExpireResponse(result.getExpiredCount()));
    }

    /** 요청 진입 시점의 세션 ID. 세션이 없으면(이론상 불가 — 인증된 요청) null. 서비스 안에서만 해시로 비교하고 밖으로 내보내지 않는다. */
    private String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getId();
    }
}
