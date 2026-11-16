package com.cms.admin.member.controller;

import com.cms.admin.member.dto.request.TotpConfirmRequest;
import com.cms.admin.member.dto.request.TotpDisableRequest;
import com.cms.admin.member.dto.request.TotpRecoveryCodesReissueRequest;
import com.cms.admin.member.service.MemberTotpService;
import com.cms.admin.member.service.MemberTotpService.TotpStatus;
import com.cms.admin.member.service.TotpEnrollment;
import com.cms.admin.member.totp.Base32;
import com.cms.admin.member.totp.OtpAuthUri;
import com.cms.admin.member.totp.TotpProperties;
import com.cms.admin.member.totp.TotpQrCodes;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 2단계 인증(TOTP) 등록·해제 API(PLAN-totp-two-factor.md 결정 4). 본인 경로는 {@code /admin/api/members/me/**} 상시 허용 게이트 안이고,
 * 다른 회원 대상은 {@code /admin/**} ADMIN 캐치올 + {@code hasRole('ADMIN')}이다 — URL 규칙 추가 없음.
 *
 * <p>등록 대기 발급과 확정은 세션 뮤텍스 안에서 <b>대기 상태 검사부터 커밋·정리까지</b> 처리한다 — 회원 행 잠금은 DB 쓰기만 직렬화해
 * 같은 세션의 다른 탭이 대기 비밀값을 바꾸는 것을 막지 못한다. 잠금 순서는 세션 뮤텍스 → 회원 행으로 로그인 경로와 같다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/members")
@Tag(name = "Admin Member TOTP", description = "관리자 2단계 인증 API")
public class MemberTotpApiController {

    private final MemberTotpService memberTotpService;
    private final TotpProperties totpProperties;

    public record TotpStatusResponse(boolean enabled, LocalDateTime enabledAt, long remainingRecoveryCodes) {
        static TotpStatusResponse of(TotpStatus status) {
            return new TotpStatusResponse(status.enabled(), status.enabledAt(), status.remainingRecoveryCodes());
        }
    }

    public record TotpEnrollmentResponse(String enrollmentId, String secret, String otpauthUri, String qrPngBase64) {
    }

    public record TotpRecoveryCodesResponse(List<String> recoveryCodes) {
    }

    @Operation(summary = "내 2단계 인증 상태")
    @GetMapping("me/totp")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TotpStatusResponse myStatus(@AuthenticationPrincipal CustomUserDetails principal) {
        return TotpStatusResponse.of(memberTotpService.status(principal.getId()));
    }

    @Operation(summary = "2단계 인증 등록 대기 발급", description = "새 비밀값·QR을 발급한다(DB 쓰기 없음, 10분 유효). 다시 부르면 이전 대기를 대체한다.")
    @ApiResponse(responseCode = "201", description = "발급")
    @ApiResponse(responseCode = "409", description = "이미 사용 중")
    @PostMapping("me/totp-enrollments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<TotpEnrollmentResponse> startEnrollment(@AuthenticationPrincipal CustomUserDetails principal,
                                                                  HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            TotpEnrollment pending = memberTotpService.startEnrollment(principal);
            session.setAttribute(TotpEnrollment.SESSION_ATTRIBUTE, pending);
            String secret = Base32.encode(pending.secret());
            String uri = OtpAuthUri.build(totpProperties.getIssuer(), principal.getUserId(), secret);
            return ResponseEntity.status(HttpStatus.CREATED).body(new TotpEnrollmentResponse(pending.enrollmentId(), secret, uri,
                    Base64.getEncoder().encodeToString(TotpQrCodes.png(uri))));
        }
    }

    @Operation(summary = "2단계 인증 등록 확정", description = "성공하면 복구 코드 10개를 이 응답에서만 돌려주고, 본인의 모든 세션을 만료한다(다시 로그인).")
    @ApiResponse(responseCode = "200", description = "등록 완료")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 또는 코드 불일치(로그인 실패 횟수에 반영)")
    @ApiResponse(responseCode = "409", description = "대기 만료·다른 화면에서 새로 시작·이미 사용 중·계정 상태 변경")
    @PutMapping("me/totp")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TotpRecoveryCodesResponse confirmEnrollment(@AuthenticationPrincipal CustomUserDetails principal,
                                                       @Valid @RequestBody TotpConfirmRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            TotpEnrollment pending = session.getAttribute(TotpEnrollment.SESSION_ATTRIBUTE) instanceof TotpEnrollment e ? e : null;
            List<String> codes = memberTotpService.confirmEnrollment(principal, pending, body.enrollmentId(), body.code(),
                    body.currentPassword(), ClientIpResolver.resolve(request), request.getRequestURI());
            removeQuietly(session);
            return new TotpRecoveryCodesResponse(codes);
        }
    }

    @Operation(summary = "내 2단계 인증 해제", description = "현재 비밀번호와 인증 코드(또는 복구 코드)가 필요하다.")
    @ApiResponse(responseCode = "204", description = "해제 완료")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 또는 코드 불일치(2단계 실패 횟수에 반영)")
    @ApiResponse(responseCode = "409", description = "사용 중이 아님·계정 상태 변경")
    @DeleteMapping("me/totp")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal CustomUserDetails principal,
                                        @Valid @RequestBody TotpDisableRequest body, HttpServletRequest request) {
        memberTotpService.disable(principal, body.code(), body.currentPassword(), ClientIpResolver.resolve(request), request.getRequestURI());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 2단계 인증 복구 코드 재발급",
            description = "현재 비밀번호와 인증 앱의 6자리 코드(복구 코드 불가)를 확인한 뒤 새 복구 코드 10개를 이 응답에서만 돌려준다. "
                    + "옛 코드는 모두 무효가 되고 세션은 유지된다.")
    @ApiResponse(responseCode = "200", description = "재발급")
    @ApiResponse(responseCode = "400", description = "현재 비밀번호 또는 코드 불일치(2단계 실패 횟수에 반영)·이미 사용한 코드(반영 안 함)")
    @ApiResponse(responseCode = "409", description = "사용 중이 아님·계정 상태 변경")
    @PostMapping("me/totp/recovery-codes")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TotpRecoveryCodesResponse reissueRecoveryCodes(@AuthenticationPrincipal CustomUserDetails principal,
                                                          @Valid @RequestBody TotpRecoveryCodesReissueRequest body,
                                                          HttpServletRequest request) {
        return new TotpRecoveryCodesResponse(memberTotpService.reissueRecoveryCodes(principal, body.code(), body.currentPassword(),
                ClientIpResolver.resolve(request), request.getRequestURI()));
    }

    @Operation(summary = "관리자의 2단계 인증 상태 조회(ADMIN)")
    @GetMapping("{id}/totp")
    @PreAuthorize("hasRole('ADMIN')")
    public TotpStatusResponse memberStatus(@PathVariable Long id) {
        return TotpStatusResponse.of(memberTotpService.status(id));
    }

    @Operation(summary = "관리자의 2단계 인증 해제(ADMIN)", description = "인증기·복구 코드를 모두 잃은 회원의 복구용. 사용 중이 아니면 아무것도 하지 않는다.")
    @ApiResponse(responseCode = "204", description = "해제(또는 이미 미사용)")
    @ApiResponse(responseCode = "400", description = "본인 대상")
    @ApiResponse(responseCode = "404", description = "회원 없음")
    @DeleteMapping("{id}/totp")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetByAdmin(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable Long id,
                                             HttpServletRequest request) {
        memberTotpService.resetByAdmin(principal, id, ClientIpResolver.resolve(request), request.getRequestURI());
        return ResponseEntity.noContent().build();
    }

    private static void removeQuietly(HttpSession session) {
        try {
            session.removeAttribute(TotpEnrollment.SESSION_ATTRIBUTE);
        } catch (IllegalStateException ignored) {
            // 세션이 이미 무효(등록 직후 본인 세션 만료 처리 등)
        }
    }
}
