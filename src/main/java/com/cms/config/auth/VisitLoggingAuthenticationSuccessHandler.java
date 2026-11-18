package com.cms.config.auth;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.notification.service.NotificationRecorder;
import com.cms.admin.visit.domain.VisitLog;
import com.cms.admin.visit.repository.VisitLogRepository;
import com.cms.common.web.ClientIpResolver;
import com.cms.config.auth.LoginFailureService.MemberSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * 로그인 성공 시 ROLE_ADMIN·ROLE_MANAGER 계정의 방문을 VisitLog에 기록한다.
 * 기존 defaultSuccessUrl("/admin", true) 동작을 보존하면서 방문 기록을 추가한다.
 *
 * <p>첫 작업으로 계정 상태를 재확인한다(fail-closed) — 인증(비밀번호 검증) 이후·성공 처리 이전에
 * 자동 잠금/상태 변경/역할 변경/비밀번호 변경이 커밋된 경합을 여기서 잡는다.
 * 세션 등록(SessionAuthenticationStrategy)은 이 핸들러보다 먼저 완료되므로, 여기서 못 잡은
 * 인터리빙(재확인 직후 커밋)은 AFTER_COMMIT 세션 폐기 리스너가 등록된 세션을 만료한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitLoggingAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    /** 2단계 인증 코드 화면 */
    public static final String TOTP_PAGE = "/admin/login/totp";

    /** 2단계 인증 강제 등록 화면(필수 역할의 미등록 회원) */
    public static final String TOTP_ENROLLMENT_PAGE = "/admin/login/totp-enrollment";

    private final VisitLogRepository visitLogRepository;
    private final LoginFailureService loginFailureService;
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    @PostConstruct
    public void init() {
        // 기존 SecurityConfig의 defaultSuccessUrl("/admin", true) 동작 보존
        setDefaultTargetUrl("/admin");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        boolean pending = TotpAuthorities.isPending(authentication);
        boolean enrollmentRequired = TotpAuthorities.isEnrollmentRequired(authentication);
        // 첫 작업: 상태·역할·해시·2단계 사용 여부 재확인 — 거부될 인증이 성공 방문으로 기록되면 안 되므로 방문 로그보다 먼저.
        if (!verifyFreshMemberState(authentication, pending, enrollmentRequired)) {
            rejectAuthentication(request, response);
            return;
        }

        if (enrollmentRequired) {
            // 2단계가 필수인 역할이 아직 등록하지 않았다 — 강제 등록 화면으로. 방문 기록은 등록 뒤 코드로 다시 로그인할 때(PLAN-totp-required-admin.md 결정 4)
            getRedirectStrategy().sendRedirect(request, response, TOTP_ENROLLMENT_PAGE);
            return;
        }

        if (pending) {
            // 비밀번호만 통과했다 — 2단계 화면으로. 방문 기록·알림 정리는 2단계 통과 때 한다(PLAN-totp-two-factor.md 결정 1).
            CustomUserDetails details = (CustomUserDetails) authentication.getPrincipal();
            request.getSession().setAttribute(TotpPendingState.SESSION_ATTRIBUTE,
                    new TotpPendingState(details.getId(), LocalDateTime.now(clock)));
            getRedirectStrategy().sendRedirect(request, response, TOTP_PAGE);
            return;
        }

        recordCompletedLogin(request, authentication);
        super.onAuthenticationSuccess(request, response, authentication);
    }

    /**
     * 로그인 완료 기록(방문·읽은 알림 정리). 2단계 통과로 완전 권한이 된 뒤 {@code TotpLoginController}도 부른다.
     * ROLE_ADMIN·ROLE_MANAGER 로그인만 방문으로 기록한다.
     */
    public void recordCompletedLogin(HttpServletRequest request, Authentication authentication) {
        if (hasAdminOrManagerRole(authentication.getAuthorities())) {
            tryLogVisit(request, authentication);
            tryPurgeOldReadNotifications(authentication);
        }
    }

    /**
     * 인증 완료 직전의 fresh 상태·역할·비밀번호 해시를 재확인한다.
     * 이 판정은 방문 로그 같은 부가 기능이 아니라 인증 결정이므로 예외도 거부(fail-closed)로
     * 처리한다 — fail-open이면 DB 일시 장애 인터리빙에서 잠긴 계정 세션이 살아남는다.
     * (실패 카운트 리셋도 같은 트랜잭션에서 함께 수행된다 — 리셋 0행은 허용, 예외는 거부.)
     */
    private boolean verifyFreshMemberState(Authentication authentication, boolean pending, boolean enrollmentRequired) {
        boolean intermediate = pending || enrollmentRequired;
        try {
            // 중간 상태는 실패 횟수를 리셋하지 않는다 — 리셋하면 2단계 오답 뒤 비밀번호 재로그인으로 대입 제한이 무력화된다.
            // 강제 등록 상태도 같다(리셋은 등록 확정이 한다).
            Optional<MemberSnapshot> found = intermediate
                    ? loginFailureService.checkActive(authentication.getName())
                    : loginFailureService.resetFailuresAndCheckActive(authentication.getName());
            if (found.isEmpty()) {
                return false;
            }
            MemberSnapshot snapshot = found.get();

            if (snapshot.status() != MemberStatus.ACTIVE) {
                return false;
            }

            // 로그인 진행 중 2단계 등록·해제·역할 변경이 커밋된 경합 — 토큰 종류와 지금 기대되는 종류가 다르면 거부한다.
            // (등록 전 스냅샷으로 만든 완전 권한 세션, 해제 뒤에도 남는 중간 상태, 필수 역할의 2단계 없는 완전 권한을 만들지 않는다)
            boolean expectPending = snapshot.totpEnabled();
            boolean expectEnrollment = !snapshot.totpEnabled() && TotpAuthorities.isRequired(snapshot.role());
            if (expectPending != pending || expectEnrollment != enrollmentRequired) {
                return false;
            }

            // 인증 중 역할 변경 경합 — 낡은 권한(특히 ADMIN)의 세션 생존 차단. 재로그인하면 새 권한으로 정상 로그인.
            // 중간 상태 토큰에는 역할 권한이 없으므로 principal의 원래 역할과 비교한다.
            Collection<? extends GrantedAuthority> granted = intermediate && authentication.getPrincipal() instanceof CustomUserDetails principal
                    ? principal.getAuthorities()
                    : authentication.getAuthorities();
            boolean roleMatches = granted.stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(authority -> authority.equals(snapshot.role().name()));
            if (!roleMatches) {
                return false;
            }

            // 인증 중 비밀번호 변경 경합 — 변경 전 비밀번호로 만들어진 세션 차단.
            // CustomUserDetails는 CredentialsContainer가 아니라 인증 당시 해시가 소거되지 않고 보존된다.
            if (!(authentication.getPrincipal() instanceof CustomUserDetails details)) {
                return false; // 예상 밖 principal 타입 — fail-closed
            }
            String authenticatedHash = details.getPassword();
            return authenticatedHash != null && authenticatedHash.equals(snapshot.passwordHash());
        } catch (Exception e) {
            log.error("로그인 성공 재확인 실패 — 로그인을 거부합니다 (user={})", authentication.getName(), e);
            return false;
        }
    }

    /**
     * 인증 완료 직전 재확인에서 거부된 로그인을 정리한다 —
     * 이미 등록된 세션 무효화 + SecurityContext 클리어 + 로그인 에러 페이지 리다이렉트.
     */
    private void rejectAuthentication(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        getRedirectStrategy().sendRedirect(request, response, "/admin/login-error");
    }

    private boolean hasAdminOrManagerRole(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_MANAGER"));
    }

    /**
     * 읽은 지 90일 지난 본인 알림 정리(D7·D8). 재확인을 통과한 뒤에만 호출되며, 실패해도 로그인을 막지 않는다.
     * 로그인 경로는 {@code REQUIRES_NEW}로 요청당 커넥션 2개를 잡지 않는다(풀 고갈 규칙) — 호출 시점에 열린 트랜잭션이 없으므로 REQUIRED가 새 트랜잭션을 연다.
     */
    private void tryPurgeOldReadNotifications(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof CustomUserDetails details) || details.getId() == null) {
            return;
        }
        try {
            notificationRecorder.purgeOldReadNotifications(details.getId());
        } catch (Exception e) {
            log.error("읽은 알림 정리 실패 (user={})", authentication.getName(), e);
        }
    }

    /**
     * 방문 로그 저장을 시도한다.
     * 저장 실패가 로그인 자체를 막지 않도록 예외를 격리하되, 에러 로그로 누락을 추적한다.
     */
    private void tryLogVisit(HttpServletRequest request, Authentication authentication) {
        try {
            // 앱 KST Clock으로 기록 — 대시보드 집계(오늘 카운트·일별 추이)와 같은 시간원.
            // JVM 기본 시간대 직접 호출은 UTC 환경에서 자정 전후 방문이 다른 날짜로 집계된다.
            VisitLog visitLog = VisitLog.builder()
                    .visitorUserId(authentication.getName())
                    .requestIp(ClientIpResolver.resolve(request))
                    .visitAt(LocalDateTime.now(clock))
                    .build();
            visitLogRepository.save(visitLog);
        } catch (Exception e) {
            log.error("방문 로그 저장 실패 (user={})", authentication.getName(), e);
        }
    }
}
