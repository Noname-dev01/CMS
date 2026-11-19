package com.cms.config.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 패스키 단독 로그인 처리 필터 {@code POST /admin/login/passkey}(PLAN-passkey-login.md 결정 1·3). <b>빈이 아니다</b> —
 * {@link PasskeyLoginConfigurer}가 폼 로그인과 같은 {@code SessionAuthenticationStrategy}(세션 ID 교체·레지스트리 등록)·컨텍스트 저장소·
 * 성공 처리기를 넣어 {@code UsernamePasswordAuthenticationFilter} 뒤(= {@link LoginSessionMutexFilter} 뒤, 세션 뮤텍스 안)에 둔다.
 * 인증 관리자는 빈이 아닌 통과용이고 {@code AuthenticationProvider} 빈을 만들지 않는다(빈이 둘이면 2단계가 사라진다).
 *
 * <p>트랜잭션이 없는 조정자다: 챌린지 꺼내기(1회용, 검증·DB 전) → {@link PasskeyLoginService#identify}(읽기) →
 * {@code loadUserByUsername}(만료 잠금 해제·상태 검사, 폼 로그인과 같은 판정) → {@link PasskeyLoginService#verify}(회원 행 잠금이 첫 작업).
 * 모든 실패는 {@link AuthenticationException}으로 바꿔 실패 처리기로 보낸다 — 예상 밖 예외도 500이 아니라 같은 안내다.
 * 실패는 세션을 무효화하지 않고 세션에 저장된 기존 인증을 건드리지 않는다(폼 로그인 실패와 같다).
 */
@Slf4j
public class PasskeyLoginFilter extends AbstractAuthenticationProcessingFilter {

    public static final String PATH = "/admin/login/passkey";

    private static final RequestMatcher POST_PATH = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, PATH);

    private final PasskeyLoginService passkeyLoginService;
    private final UserDetailsService userDetailsService;
    private final Clock clock;

    public PasskeyLoginFilter(PasskeyLoginService passkeyLoginService, UserDetailsService userDetailsService, Clock clock) {
        // 꺼짐이면 매칭하지 않는다 — 요청은 기존 인가 규칙(/admin/** 캐치올)으로 간다
        super(request -> passkeyLoginService.isEnabled() && POST_PATH.matches(request));
        this.passkeyLoginService = passkeyLoginService;
        this.userDetailsService = userDetailsService;
        this.clock = clock;
        // 실제 판정은 attemptAuthentication이 한다 — 이미 인증된 토큰만 받는 통과용(빈 아님)
        setAuthenticationManager(authentication -> authentication);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        PasskeyLoginState state = takeState(request);
        if (state == null || !state.matches(request.getParameter("loginId"), LocalDateTime.now(clock))) {
            throw new PasskeyLoginExpiredException();
        }
        try {
            Optional<PasskeyLoginService.Identification> identified = passkeyLoginService.identify(request.getParameter("passkeyCredential"));
            if (identified.isEmpty()) {
                throw new BadCredentialsException("패스키 자격을 찾을 수 없다");
            }
            UserDetails loaded = userDetailsService.loadUserByUsername(identified.get().userId());
            if (!(loaded instanceof CustomUserDetails principal)) {
                throw new AuthenticationServiceException("예상 밖 principal");
            }
            LocalDateTime totpEnabledAt = passkeyLoginService.verify(principal, state.options(), identified.get().credential())
                    .orElseThrow(() -> new BadCredentialsException("패스키 검증 실패"));
            PasskeyLoginAuthenticationToken token = new PasskeyLoginAuthenticationToken(principal, totpEnabledAt);
            token.setDetails(authenticationDetailsSource.buildDetails(request));
            return token;
        } catch (AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("패스키 로그인 처리 오류 — 거부합니다", e);
            throw new AuthenticationServiceException("패스키 로그인 처리 오류", e);
        }
    }

    /** 세션의 챌린지를 꺼내며 지운다(1회용). */
    private static PasskeyLoginState takeState(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object stored = session.getAttribute(PasskeyLoginState.SESSION_ATTRIBUTE);
        session.removeAttribute(PasskeyLoginState.SESSION_ATTRIBUTE);
        return stored instanceof PasskeyLoginState found ? found : null;
    }

    /** 챌린지 없음·다른 탭이 교체·5분 초과 — 검증 전에 끝낸다(안내만 다르다). */
    public static class PasskeyLoginExpiredException extends AuthenticationException {
        public PasskeyLoginExpiredException() {
            super("패스키 로그인 챌린지 만료");
        }
    }
}
