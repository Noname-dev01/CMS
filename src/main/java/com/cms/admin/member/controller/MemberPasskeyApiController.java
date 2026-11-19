package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.PasskeyRegisterRequest;
import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.admin.member.service.MemberPasskeyService;
import com.cms.admin.member.service.MemberPasskeyService.PasskeyList;
import com.cms.admin.member.service.MemberPasskeyService.PasskeySummary;
import com.cms.admin.member.service.PasskeyRegistration;
import com.cms.common.web.ClientIpResolver;
import com.cms.config.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 패스키 등록·목록·삭제 API(PLAN-passkey-second-factor.md 결정 5). 모두 본인 경로라 {@code /admin/api/members/me/**} 상시 허용 게이트 안이다 —
 * URL 규칙 추가 없음. 등록 대기 발급·확정은 세션 뮤텍스 안에서 처리한다(같은 세션의 다른 탭과 직렬화, 순서는 세션 뮤텍스 → 회원 행).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/members/me")
@Tag(name = "Admin Member Passkey", description = "관리자 패스키(2단계 인증 대체 수단) API")
public class MemberPasskeyApiController {

    private final MemberPasskeyService passkeyService;
    private final PasskeyJson passkeyJson;

    public record PasskeyItemResponse(Long id, String label, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
        static PasskeyItemResponse of(PasskeySummary summary) {
            return new PasskeyItemResponse(summary.id(), summary.label(), summary.createdAt(), summary.lastUsedAt());
        }
    }

    /** @param enabled 서버 기능 켜짐 @param totpEnabled 2단계(인증 앱) 사용 중 — 아니면 등록할 수 없다 */
    public record PasskeyListResponse(boolean enabled, boolean totpEnabled, List<PasskeyItemResponse> items) {
        static PasskeyListResponse of(PasskeyList list) {
            return new PasskeyListResponse(list.enabled(), list.totpEnabled(), list.items().stream().map(PasskeyItemResponse::of).toList());
        }
    }

    /** @param options 브라우저 {@code navigator.credentials.create}에 넘길 공개키 자격 생성 옵션(WebAuthn JSON) */
    public record PasskeyRegistrationResponse(String registrationId, JsonNode options) {
    }

    @Operation(summary = "내 패스키 목록")
    @GetMapping("passkeys")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public PasskeyListResponse list(@AuthenticationPrincipal CustomUserDetails principal) {
        return PasskeyListResponse.of(passkeyService.list(principal.getId()));
    }

    @Operation(summary = "패스키 등록 대기 발급", description = "공개키 자격 생성 옵션을 발급한다(5분 유효). 다시 부르면 이전 대기를 대체한다.")
    @ApiResponse(responseCode = "201", description = "발급")
    @ApiResponse(responseCode = "404", description = "패스키 기능 꺼짐")
    @ApiResponse(responseCode = "409", description = "2단계 인증(인증 앱) 미사용·등록 상한(10개)·계정 상태 변경")
    @PostMapping("passkey-registrations")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PasskeyRegistrationResponse> startRegistration(@AuthenticationPrincipal CustomUserDetails principal,
                                                                         HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            PasskeyRegistration pending = passkeyService.startRegistration(principal);
            session.setAttribute(PasskeyRegistration.SESSION_ATTRIBUTE, pending);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new PasskeyRegistrationResponse(pending.registrationId(), passkeyJson.toNode(pending.options())));
        }
    }

    @Operation(summary = "패스키 등록 확정", description = "현재 비밀번호와 브라우저가 만든 공개키 자격을 확인해 저장한다. 등록 대기는 성공·실패와 무관하게 끝난다.")
    @ApiResponse(responseCode = "201", description = "등록")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치(2단계 실패 횟수에 반영)·자격 검증 실패(반영 안 함)·이름 형식")
    @ApiResponse(responseCode = "404", description = "패스키 기능 꺼짐")
    @ApiResponse(responseCode = "409", description = "대기 만료·다른 화면에서 새로 시작·2단계 인증 변경·상한·계정 상태 변경")
    @PostMapping("passkeys")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PasskeyItemResponse> register(@AuthenticationPrincipal CustomUserDetails principal,
                                                        @Valid @RequestBody PasskeyRegisterRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            PasskeyRegistration pending = session.getAttribute(PasskeyRegistration.SESSION_ATTRIBUTE) instanceof PasskeyRegistration p ? p : null;
            try {
                PasskeySummary saved = passkeyService.confirmRegistration(principal, pending, body.registrationId(),
                        body.credential().toString(), body.label(), body.currentPassword(),
                        ClientIpResolver.resolve(request), request.getRequestURI());
                return ResponseEntity.status(HttpStatus.CREATED).body(PasskeyItemResponse.of(saved));
            } finally {
                // 1회용 — 실패해도 같은 챌린지로 다시 시도하지 않는다
                if (pending != null && pending.registrationId().equals(body.registrationId())) {
                    session.removeAttribute(PasskeyRegistration.SESSION_ATTRIBUTE);
                }
            }
        }
    }

    @Operation(summary = "내 패스키 삭제", description = "재인증 없이 지운다(2단계 인증 앱은 그대로 남는다).")
    @ApiResponse(responseCode = "204", description = "삭제")
    @ApiResponse(responseCode = "404", description = "내 패스키가 아님")
    @ApiResponse(responseCode = "409", description = "계정 상태 변경")
    @DeleteMapping("passkeys/{passkeyId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable Long passkeyId,
                                       HttpServletRequest request) {
        passkeyService.delete(principal, passkeyId, ClientIpResolver.resolve(request), request.getRequestURI());
        return ResponseEntity.noContent().build();
    }
}
