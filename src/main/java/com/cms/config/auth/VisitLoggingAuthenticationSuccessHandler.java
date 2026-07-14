package com.cms.config.auth;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.visit.domain.VisitLog;
import com.cms.admin.visit.repository.VisitLogRepository;
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

    private static final int MAX_IP_LENGTH = 45;

    private final VisitLogRepository visitLogRepository;
    private final LoginFailureService loginFailureService;

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
        if (!verifyFreshMemberState(authentication)) {
            rejectAuthentication(request, response);
            return;
        }

        // ROLE_ADMIN·ROLE_MANAGER 로그인만 방문으로 기록한다.
        if (hasAdminOrManagerRole(authentication.getAuthorities())) {
            tryLogVisit(request, authentication);
        }

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
     * 방문 로그 저장을 시도한다.
     * 저장 실패가 로그인 자체를 막지 않도록 예외를 격리하되, 에러 로그로 누락을 추적한다.
     */
    private void tryLogVisit(HttpServletRequest request, Authentication authentication) {
        try {
            String ip = extractClientIp(request);
            VisitLog visitLog = VisitLog.builder()
                    .visitorUserId(authentication.getName())
                    .requestIp(truncateIp(ip))
                    .visitAt(LocalDateTime.now())
                    .build();
            visitLogRepository.save(visitLog);
        } catch (Exception e) {
            log.error("방문 로그 저장 실패 (user={})", authentication.getName(), e);
        }
    }

    /**
     * IP를 추출한다.
     * X-FORWARDED-FOR(마지막 홉) → X-Real-IP → RemoteAddr 순으로 시도한다.
     * AdminActionLogAspect.getClientIp()와 동일 로직(기존 Aspect는 private이라 직접 재사용 불가).
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

    /**
     * IP를 컬럼 최대 길이(45)로 절단한다.
     * 조작된 헤더나 과도하게 긴 값이 들어와도 DataException으로 인한 무음 누락을 방지한다.
     */
    private String truncateIp(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
    }
}
