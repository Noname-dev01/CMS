package com.cms.config.auth;

import com.cms.admin.member.domain.MemberStatus;
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
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    private final LoginFailureService loginFailureService;
    private final LoginVisitRecorder visitRecorder;
    private final RememberMeTokenRepository rememberMeTokens;
    private final AdminRememberMeServices rememberMeServices;
    private final SessionRegistry sessionRegistry;

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
        // 첫 작업: 상태·역할·해시 재확인 — 거부될 인증이 성공 방문으로 기록되면 안 되므로 방문 로그보다 먼저.
        // 이어서 이 로그인이 방금 발급한 remember-me 기기가 아직 살아 있는지 본다(사후 검증 I2, PLAN-remember-me-login.md 쟁점 2).
        if (!verifyFreshMemberState(authentication) || !verifyRememberMeDevice(request, authentication)) {
            rejectAuthentication(request, response, authentication);
            return;
        }

        // remember-me 쿠키는 모든 검증을 통과한 뒤에만 싣는다(거부 응답에 발급 쿠키가 실리지 않게 — 코드 리뷰 반영).
        rememberMeServices.issuePendingCookie(request, response);
        visitRecorder.record(request, authentication);

        super.onAuthenticationSuccess(request, response, authentication);
    }

    /**
     * 인증 완료 직전의 fresh 상태·역할·비밀번호 해시를 재확인한다.
     * 이 판정은 방문 로그 같은 부가 기능이 아니라 인증 결정이므로 예외도 거부(fail-closed)로
     * 처리한다 — fail-open이면 DB 일시 장애 인터리빙에서 잠긴 계정 세션이 살아남는다.
     * (실패 카운트 리셋도 같은 트랜잭션에서 함께 수행된다 — 리셋 0행은 허용, 예외는 거부.)
     */
    private boolean verifyFreshMemberState(Authentication authentication) {
        try {
            Optional<MemberSnapshot> found = loginFailureService.resetFailuresAndCheckActive(authentication.getName());
            if (found.isEmpty()) {
                return false;
            }
            MemberSnapshot snapshot = found.get();

            if (snapshot.status() != MemberStatus.ACTIVE) {
                return false;
            }

            // 인증 중 역할 변경 경합 — 낡은 권한(특히 ADMIN)의 세션 생존 차단. 재로그인하면 새 권한으로 정상 로그인.
            boolean roleMatches = authentication.getAuthorities().stream()
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
    private void rejectAuthentication(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) throws IOException {
        // 방금 발급한 remember-me 기기도 정리한다 — 세션만 무효화하면 새 쿠키로 다음 요청에서 다시 들어온다(리뷰 v2-2).
        String series = seriesOf(authentication);
        if (series != null) {
            try {
                rememberMeTokens.removeSeries(series);
            } catch (Exception e) {
                log.error("거부된 로그인의 remember-me 토큰 정리 실패 (user={})", authentication.getName(), e);
            }
        }
        rememberMeServices.cancelRememberMeCookie(request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        getRedirectStrategy().sendRedirect(request, response, "/admin/login-error");
    }

    /**
     * 이 로그인이 remember-me 기기를 발급했다면, 그 시리즈가 아직 DB에 있고 현재 세션이 그 사이 만료되지 않았는지 본다.
     * 세션 등록 ~ 시리즈 기록 사이에 회원 전체 폐기·수동 만료가 끼면 여기서 거부한다. 조회 실패도 거부(fail-closed).
     * 세션 만료 표시는 일반 필드라 다른 스레드의 쓰기 가시성이 보장되지 않는다 — best-effort(쟁점 2 "남는 창").
     */
    private boolean verifyRememberMeDevice(HttpServletRequest request, Authentication authentication) {
        String series = seriesOf(authentication);
        if (series == null) {
            return true;
        }
        try {
            HttpSession session = request.getSession(false);
            SessionInformation info = session == null ? null : sessionRegistry.getSessionInformation(session.getId());
            if (info != null && info.isExpired()) {
                return false;
            }
            return rememberMeTokens.existsSeries(series);
        } catch (Exception e) {
            log.error("remember-me 기기 확인 실패 — 로그인을 거부합니다 (user={})", authentication.getName(), e);
            return false;
        }
    }

    private static String seriesOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof CustomUserDetails details ? details.getRememberMeSeries() : null;
    }
}
