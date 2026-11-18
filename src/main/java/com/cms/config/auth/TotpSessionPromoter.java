package com.cms.config.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * 2단계 통과(커밋 완료) 뒤 중간 상태 세션을 원래 역할 권한으로 승격한다(PLAN-totp-two-factor.md 결정 2-4).
 *
 * <ul>
 *   <li>세션 ID는 바꾸지 않는다 — 바꾸면 레지스트리의 만료 표시가 옮겨지지 않아 만료된 세션이 되살아날 수 있다
 *       (docs/troubleshooting.md "세션 ID를 바꾸면 이미 만료 처리한 세션이 되살아날 수 있다").</li>
 *   <li>세션 생성을 금지한 저장소로 저장한다 — 기본 저장소는 판정 뒤 로그아웃이 끼면 새 세션을 만들 수 있고, 수동 승격은 레지스트리
 *       등록을 다시 하지 않아 레지스트리 밖 인증 세션이 생긴다.</li>
 *   <li>저장 전후로 원래 세션이 같은 ID로 살아 있고 레지스트리 항목이 있으며 만료되지 않았는지 확인한다. 아니면 무효화하고 false.</li>
 * </ul>
 */
@Component
public class TotpSessionPromoter {

    private final SessionRegistry sessionRegistry;
    private final HttpSessionSecurityContextRepository repository;

    @Autowired
    public TotpSessionPromoter(SessionRegistry sessionRegistry) {
        this(sessionRegistry, noSessionCreationRepository());
    }

    TotpSessionPromoter(SessionRegistry sessionRegistry, HttpSessionSecurityContextRepository repository) {
        this.sessionRegistry = sessionRegistry;
        this.repository = repository;
    }

    private static HttpSessionSecurityContextRepository noSessionCreationRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setAllowSessionCreation(false);
        return repository;
    }

    /** 세션 레지스트리 항목이 있고 만료되지 않았는가. */
    public boolean isLive(String sessionId) {
        SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
        return info != null && !info.isExpired();
    }

    /**
     * 레지스트리 항목이 살아 있고 그 회원이 {@code memberId}인가 — 같은 세션에서 다른 계정으로 다시 로그인한 뒤 낡은 요청이 진행되지 않게
     * (PLAN-totp-required-admin.md 결정 2).
     */
    public boolean isLiveFor(String sessionId, Long memberId) {
        SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
        return info != null && !info.isExpired()
                && info.getPrincipal() instanceof CustomUserDetails registered
                && memberId != null && memberId.equals(registered.getId());
    }

    public boolean promote(HttpServletRequest request, HttpServletResponse response, CustomUserDetails principal, String expectedSessionId) {
        if (!sameLiveSession(request, expectedSessionId)) {
            reject(request);
            return false;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        try {
            repository.saveContext(context, request, response);
        } catch (IllegalStateException e) {
            // 저장 도중 다른 요청이 세션을 무효화했다(로그아웃)
            reject(request);
            return false;
        }
        if (!sameLiveSession(request, expectedSessionId)) {
            reject(request);
            return false;
        }
        SecurityContextHolder.setContext(context);
        return true;
    }

    private boolean sameLiveSession(HttpServletRequest request, String expectedSessionId) {
        HttpSession session = request.getSession(false);
        if (session == null || !session.getId().equals(expectedSessionId)) {
            return false;
        }
        try {
            session.getAttribute(TotpPendingState.SESSION_ATTRIBUTE); // 무효화된 세션이면 IllegalStateException
        } catch (IllegalStateException e) {
            return false;
        }
        return isLive(expectedSessionId);
    }

    private static void reject(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 이미 무효
            }
        }
    }
}
