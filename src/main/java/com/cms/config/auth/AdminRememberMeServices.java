package com.cms.config.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;

import java.time.Clock;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 관리자 "로그인 상태 유지"(이슈 #222, PLAN-remember-me-login.md). Spring 영속 토큰 구현에 다음을 더한다.
 *
 * <ul>
 *   <li><b>기기 = 시리즈</b>: 로그인이 쓰는 시리즈(DB 정식 값)를 principal과 세션 속성 {@link #SESSION_SERIES_ATTR}에 기록한다.
 *       세션 관리의 수동 만료·로그아웃이 이것으로 그 기기만 폐기한다(사용자 결정 U4).</li>
 *   <li><b>불변식 I1</b>: 폼 로그인은 이 브라우저의 기존 시리즈(쿠키·세션 속성)를 먼저 지운다 — 체크하지 않으면 쿠키도 취소한다.
 *       정리 중 예외가 나면 이미 저장된 인증을 지우고 실패한다(폼 로그인은 컨텍스트 저장이 이 메서드보다 앞이다).</li>
 *   <li><b>탈취</b>(교체된 옛 쿠키 재사용): 자동 로그인 처리를 한 번의 조회로 직접 하며, 불일치면 세션 만료 → 토큰 삭제 → 세션 만료 뒤
 *       익명으로 계속한다(U5). 라이브러리는 삭제를 먼저 해 그 실패가 세션 만료를 건너뛰었고, 예외를 다시 던져 500이 됐다.</li>
 *   <li><b>폼 로그인 쿠키는 지연 발급</b>: 새 시리즈는 여기서 만들지만 쿠키는 성공 핸들러가 모든 검증을 통과한 뒤 {@link #issuePendingCookie}로 싣는다.</li>
 *   <li><b>로그아웃</b>: 회원 전체가 아니라 이 기기만 폐기한다(같은 시리즈의 다른 세션 포함). 만료된 세션의 후속 요청
 *       ({@code ConcurrentSessionFilter})에서는 쿠키만 취소한다 — 폐기 범위는 만료를 일으킨 쪽이 이미 정했다.</li>
 * </ul>
 * 자동 로그인 성공의 최종 확인(시리즈가 아직 DB에 있는가)과 방문 기록은 세션 등록 뒤 {@link RememberMeLoginVerificationFilter}가 한다.
 */
@Slf4j
public class AdminRememberMeServices extends PersistentTokenBasedRememberMeServices {

    /** 유효 기간 7일(사용자 결정 U2) — 자동 로그인마다 교체되므로 "마지막 사용 후 7일". */
    public static final int VALIDITY_SECONDS = 7 * 24 * 60 * 60;

    /** 이 세션이 쓰는 remember-me 시리즈. 같은 세션의 재로그인으로 principal이 바뀌어도 남는다. */
    public static final String SESSION_SERIES_ATTR = "REMEMBER_ME_SERIES";

    /** 이 요청에서 쿠키 자동 로그인이 토큰 검증까지 마친 시리즈 — 사후 검증 필터의 대상 표시. */
    static final String AUTO_LOGIN_SERIES_ATTR = AdminRememberMeServices.class.getName() + ".AUTO_LOGIN_SERIES";

    /** 폼 로그인이 발급했지만 아직 응답에 싣지 않은 쿠키 값 — 성공 핸들러가 모든 검증을 통과한 뒤에만 싣는다. */
    private static final String PENDING_COOKIE_ATTR = AdminRememberMeServices.class.getName() + ".PENDING_COOKIE";

    /**
     * 탈취를 감지했지만 토큰 삭제에 실패한 회원(소문자 아이디). 삭제가 성공할 때까지 그 회원의 자동 로그인을 거부하고 매번 삭제를 재시도한다
     * (코드 리뷰 반영 — 행이 남으면 공격자의 최신 쿠키로 다시 들어올 수 있다). 단일 인스턴스 메모리라 재기동하면 사라진다
     * (세션 레지스트리·레이트리밋과 같은 전제).
     */
    private final Set<String> pendingTheftRevocations = ConcurrentHashMap.newKeySet();

    private final RememberMeTokenRepository tokenRepository;
    private final AdminSessionService sessions;
    private final RememberMeDeviceRevoker deviceRevoker;
    private final SessionRegistry sessionRegistry;
    private final Clock clock;

    public AdminRememberMeServices(String key, UserDetailsService userDetailsService, RememberMeTokenRepository tokenRepository,
                                   AdminSessionService sessions, RememberMeDeviceRevoker deviceRevoker,
                                   SessionRegistry sessionRegistry, Clock clock) {
        super(key, userDetailsService, tokenRepository);
        this.tokenRepository = tokenRepository;
        this.sessions = sessions;
        this.deviceRevoker = deviceRevoker;
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
        setTokenValiditySeconds(VALIDITY_SECONDS);
    }

    // ------------------------------------------------------------------ 폼 로그인

    @Override
    public void loginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication successfulAuthentication) {
        try {
            HttpSession session = request.getSession(false);
            Set<String> previous = new LinkedHashSet<>();
            addIfPresent(previous, cookieSeries(request));
            if (session != null) {
                addIfPresent(previous, session.getAttribute(SESSION_SERIES_ATTR));
                session.removeAttribute(SESSION_SERIES_ATTR);
            }
            for (String series : previous) {
                tokenRepository.removeSeries(series);
            }
            if (!rememberMeRequested(request, getParameter())) {
                if (extractRememberMeCookie(request) != null) {
                    cancelCookie(request, response);
                }
                return;
            }
            onLoginSuccess(request, response, successfulAuthentication);
        } catch (RuntimeException e) {
            // 컨텍스트는 이미 세션에 저장됐다 — 그대로 두면 성공 핸들러의 fresh 재확인을 건너뛴 인증이 남는다(PLAN v8).
            discardAuthentication(request, response);
            throw e;
        }
    }

    @Override
    protected void onLoginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication successfulAuthentication) {
        PersistentRememberMeToken token = new PersistentRememberMeToken(successfulAuthentication.getName(),
                generateSeriesData(), generateTokenData(), Date.from(clock.instant()));
        try {
            tokenRepository.createNewToken(token);
        } catch (Exception e) {
            // 라이브러리와 같이 로그인 자체는 계속한다 — 상태 유지 없이.
            log.error("remember-me 토큰 저장 실패 — 상태 유지 없이 로그인합니다 (user={})", successfulAuthentication.getName(), e);
            return;
        }
        // 쿠키는 여기서 싣지 않는다 — 성공 핸들러가 fresh 상태·기기 검증을 모두 통과한 뒤 issuePendingCookie로 싣는다.
        // 거부된 응답에 발급 쿠키가 실리면, 행 삭제가 실패했을 때 클라이언트가 뒤의 취소 쿠키를 무시하고 그 값으로 재인증할 수 있다.
        request.setAttribute(PENDING_COOKIE_ATTR, new String[]{token.getSeries(), token.getTokenValue()});
        if (successfulAuthentication.getPrincipal() instanceof CustomUserDetails details) {
            details.setRememberMeSeries(token.getSeries());
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_SERIES_ATTR, token.getSeries());
        }
    }

    /** 성공 핸들러가 검증을 모두 통과한 폼 로그인에만 부른다 — 이 요청이 발급한 쿠키를 응답에 싣는다. */
    public void issuePendingCookie(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(PENDING_COOKIE_ATTR) instanceof String[] tokens) {
            setCookie(tokens, getTokenValiditySeconds(), request, response);
            request.removeAttribute(PENDING_COOKIE_ATTR);
        }
    }

    // ------------------------------------------------------------------ 쿠키 자동 로그인

    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        try {
            return super.autoLogin(request, response);
        } catch (CookieTheftException e) {
            // 세션 만료·토큰 삭제는 processAutoLoginCookie가 이미 했다. 500 대신 익명으로 계속한다.
            cancelCookie(request, response);
            return null;
        }
    }

    /**
     * 라이브러리 구현과 같은 단계(조회 → 탈취 판정 → 만료 판정 → 토큰 교체·쿠키 → 사용자 로드)를 <b>한 번의 조회</b>로 직접 한다.
     * 라이브러리를 부르면 사전 조회와 내부 재조회가 어긋나, 그 사이 다른 요청이 토큰을 교체했을 때 라이브러리 쪽 탈취 경로(토큰만 삭제)를 타
     * 세션 만료를 건너뛰었다(코드 리뷰 반영). 시각은 주입된 Clock을 쓴다.
     */
    @Override
    protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request, HttpServletResponse response) {
        if (cookieTokens.length != 2) {
            throw new InvalidCookieException("remember-me 쿠키 형식이 올바르지 않습니다.");
        }
        String presentedSeries = cookieTokens[0];
        String presentedToken = cookieTokens[1];
        PersistentRememberMeToken stored = tokenRepository.getTokenForSeries(presentedSeries);
        if (stored == null) {
            throw new RememberMeAuthenticationException("remember-me 토큰이 없습니다.");
        }
        if (pendingTheftRevocations.contains(stored.getUsername().toLowerCase(Locale.ROOT))) {
            retryTheftRevocation(stored.getUsername());
            throw new RememberMeAuthenticationException("remember-me 토큰이 폐기 대기 중입니다.");
        }
        if (!presentedToken.equals(stored.getTokenValue())) {
            revokeOnTheft(stored.getUsername());
            throw new CookieTheftException("remember-me 토큰 불일치");
        }
        if (stored.getDate().toInstant().plusSeconds(getTokenValiditySeconds()).isBefore(clock.instant())) {
            throw new RememberMeAuthenticationException("remember-me 토큰이 만료됐습니다.");
        }
        // 쿠키 값이 아니라 DB의 정식 시리즈를 쓴다(칼럼은 ascii_bin이지만 두 겹 방어 — PLAN v6).
        String series = stored.getSeries();
        String newToken = generateTokenData();
        try {
            tokenRepository.updateToken(series, newToken, Date.from(clock.instant()));
            setCookie(new String[]{series, newToken}, getTokenValiditySeconds(), request, response);
        } catch (Exception e) {
            // 0행 갱신(이미 폐기됨) 포함 — 라이브러리와 같이 인증 실패로 끝낸다.
            throw new RememberMeAuthenticationException("remember-me 토큰을 갱신하지 못했습니다.");
        }
        UserDetails user = getUserDetailsService().loadUserByUsername(stored.getUsername());
        if (user instanceof CustomUserDetails details) {
            details.setRememberMeSeries(series);
        }
        request.setAttribute(AUTO_LOGIN_SERIES_ATTR, series);
        return user;
    }

    /**
     * 탈취 의심(교체된 옛 토큰 재사용, U5): 세션 만료 → 토큰 삭제 → 세션 만료. 앞의 만료는 DB 없이 레지스트리만 보므로 삭제가 실패해도 끝나고,
     * 뒤의 만료는 그 사이 현재 토큰으로 세션을 등록·검증까지 마친 요청을 잡는다(그 요청의 검증이 행을 봤다면 등록이 삭제보다 앞이다).
     * 삭제가 실패하면 그 회원을 차단 목록에 올려 삭제가 성공할 때까지 자동 로그인을 거부하고 재시도한다. 아이디·시리즈는 로그에 남기지 않는다.
     */
    private void revokeOnTheft(String userId) {
        sessions.expireSessionsForUserId(userId);
        retryTheftRevocation(userId);
        sessions.expireSessionsForUserId(userId);
        log.warn("remember-me 쿠키 탈취 의심 — 그 회원의 자동 로그인 세션과 토큰을 끊었습니다");
    }

    /**
     * 탈취 처리의 토큰 삭제가 아직 끝나지 않은 회원인가. 사후 검증이 세션 등록 뒤에 본다 — 차단 목록 검사를 지난 뒤 멈춘 자동 로그인이
     * 탈취 처리(차단 등록 → 두 번째 세션 만료)가 끝난 뒤 세션을 등록하는 경합을 닫는다(코드 리뷰 반영).
     */
    public boolean isTheftRevocationPending(String userId) {
        return userId != null && pendingTheftRevocations.contains(userId.toLowerCase(Locale.ROOT));
    }

    /** 탈취 회원의 토큰 삭제. 실패하면 차단 목록에 남겨 자동 로그인을 거부하고, 다음 시도에서 다시 지운다. */
    private void retryTheftRevocation(String userId) {
        String key = userId.toLowerCase(Locale.ROOT);
        pendingTheftRevocations.add(key);
        try {
            tokenRepository.removeUserTokens(userId);
            pendingTheftRevocations.remove(key);
        } catch (RuntimeException deleteFailure) {
            log.error("remember-me 탈취 의심 — 토큰 삭제에 실패해 그 회원의 자동 로그인을 차단하고 재시도합니다", deleteFailure);
        }
    }

    // ------------------------------------------------------------------ 로그아웃

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        cancelCookie(request, response);
        HttpSession session = request.getSession(false);
        SessionInformation info = session == null ? null : sessionRegistry.getSessionInformation(session.getId());
        if (info != null && info.isExpired()) {
            return; // 만료 세션의 후속 요청 — 쿠키만 취소한다(PLAN v6).
        }
        Set<String> series = new LinkedHashSet<>();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details) {
            addIfPresent(series, details.getRememberMeSeries());
        }
        if (info != null && info.getPrincipal() instanceof CustomUserDetails registered) {
            addIfPresent(series, registered.getRememberMeSeries());
        }
        addIfPresent(series, cookieSeries(request));
        if (session != null) {
            addIfPresent(series, session.getAttribute(SESSION_SERIES_ATTR));
        }
        for (String s : series) {
            deviceRevoker.revokeDevice(s);
        }
    }

    // ------------------------------------------------------------------ 공용

    /** 응답에 remember-me 쿠키 취소를 싣는다. */
    public void cancelRememberMeCookie(HttpServletRequest request, HttpServletResponse response) {
        cancelCookie(request, response);
    }

    /** 이 요청의 인증을 버린다 — 세션 무효화·컨텍스트 초기화·쿠키 취소. */
    void discardAuthentication(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 이미 무효화됨
            }
        }
        SecurityContextHolder.clearContext();
        cancelCookie(request, response);
    }

    private String cookieSeries(HttpServletRequest request) {
        String value = extractRememberMeCookie(request);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            String[] parts = decodeCookie(value);
            return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
        } catch (InvalidCookieException e) {
            return null;
        }
    }

    private static void addIfPresent(Set<String> target, Object series) {
        if (series instanceof String s && !s.isEmpty()) {
            target.add(s);
        }
    }
}
