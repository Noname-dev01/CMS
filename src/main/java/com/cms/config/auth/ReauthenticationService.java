package com.cms.config.auth;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.common.exception.ReauthenticationEndedException;
import com.cms.common.exception.ReauthenticationFailedException;
import com.cms.common.web.ClientIpResolver;
import com.cms.config.auth.LoginFailureService.MemberSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.WebUtils;

import java.util.Objects;
import java.util.Optional;

/**
 * 민감 작업 재인증 — 쿠키 자동 로그인(remember-me) 세션을 비밀번호 확인으로 <b>제자리에서</b> 완전 인증으로 바꾼다
 * (PLAN-sensitive-reauthentication.md 쟁점 6·7). 폼 로그인 밖의 새 인증 경로라 폼 로그인이 해 주던 보호를 스스로 갖춘다 —
 * 폼 로그인에 보호가 추가되면 여기에도 맞춘다.
 *
 * <ul>
 *   <li><b>검증</b>: 폼 로그인과 같은 {@link AuthenticationManager}(같은 {@code CustomUserDetailsService}·{@code BCryptPasswordEncoder})로
 *       현재 principal의 DB 정식 아이디를 인증한다. 아이디를 요청에서 받지 않는다.</li>
 *   <li><b>실패</b>: 오답은 폼 로그인과 같은 실패 카운터({@link LoginFailureService#recordFailure}) — 잠기지 않았으면 400, 이번 실패로 잠겼거나
 *       계정이 잠김·비활성·변경 강제면 세션을 끝내고 401.</li>
 *   <li><b>이미 완전 인증</b>: 비밀번호만 확인하고 아무것도 바꾸지 않는다(세션 ID 교체 없음).</li>
 *   <li><b>승격</b>(요청 시작 때의 세션 S에만, S의 뮤텍스 안 — 같은 뮤텍스를 {@link LoginSessionMutexFilter}가 폼 로그인에 쓴다):
 *       ⓪ S의 인증이 이 요청의 인증 그대로인지 → ① 레지스트리 모니터 안에서 "만료·불일치면 중단, 아니면 세션 ID 교체"
 *       ({@link AdminSessionService#changeSessionIdIfActive}) → ② 폼 로그인 성공 처리기와 같은 DB 재확인 + 시리즈 생존 + 탈취 폐기 대기 아님 →
 *       ③ 새 컨텍스트 객체 → ④ S에 직접 저장(무효화됐으면 새 세션을 만들지 않고 끝냄) → ⑤ 레지스트리·컨텍스트가 같은 회원인지 사후 확인.
 *       어느 단계든 어긋나면 S를 무효화하고 401. CSRF 토큰은 바꾸지 않는다(화면이 같은 헤더로 원 요청을 다시 보낸다).</li>
 * </ul>
 * 감사({@code admin_action_log})·방문 기록은 남기지 않는다 — 폼 로그인 성공도 감사가 아니고, 새 로그인이 아니다. 잠금 전이는 기존 감사가 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReauthenticationService {

    /** admin_action_log.request_uri 컬럼 길이 — 실패 집계 감사에 넘기는 URI 절단(폼 로그인 실패 처리기와 같다). */
    private static final int MAX_URI_LENGTH = 255;

    private final AuthenticationManager authenticationManager;
    private final LoginFailureService loginFailureService;
    private final AdminSessionService adminSessionService;
    private final SessionRegistry sessionRegistry;
    private final RememberMeTokenRepository rememberMeTokens;
    private final AdminRememberMeServices rememberMeServices;

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    public void reauthenticate(String password, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (session == null || current == null || !(current.getPrincipal() instanceof CustomUserDetails principal)) {
            throw end(session, "인증 정보 없음");
        }
        String userId = principal.getUserId();

        Authentication verified;
        try {
            verified = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(userId, password));
        } catch (BadCredentialsException e) {
            Boolean locked = recordFailure(userId, request);
            if (locked == null) {
                // 집계를 보장할 수 없으면 세션을 끝낸다 — 세션이 살아 있는 채로 집계 없이 계속 시도하는 경로를 남기지 않는다(코드 리뷰 4라운드).
                // 폼 로그인은 그 요청이 이미 실패로 끝나 같은 관용이 공백이 아니지만, 재인증은 세션을 유지한 채 반복할 수 있다.
                throw end(session, "실패 집계 불가");
            }
            if (locked) {
                throw end(session, "오답으로 계정 잠김");
            }
            log.info("재인증 실패(비밀번호 불일치): user={}", userId);
            throw new ReauthenticationFailedException();
        } catch (AuthenticationException e) {
            throw end(session, "계정 상태로 인증 거부(" + e.getClass().getSimpleName() + ")");
        }

        if (trustResolver.isFullyAuthenticated(current)) {
            log.info("재인증 확인(이미 완전 인증 — 변경 없음): user={}", userId);
            return;
        }

        synchronized (WebUtils.getSessionMutex(session)) {
            // ⓪ S의 지금 인증이 이 요청과 같은 주체인가 — 객체 동일성이 아니라 (회원, 기기 시리즈)로 본다. 같은 회원의 자기수정
            //    (refreshAuthentication)은 타입·시리즈를 보존하면서 인증 객체만 새로 만들기 때문이다(코드 리뷰 2라운드).
            //   - 같은 회원·같은 시리즈의 remember-me → 계속 승격한다.
            //   - 같은 회원의 완전 인증 → 같은 세션의 다른 탭이 먼저 승격했다(탭은 세션을 공유). 비밀번호는 이 요청도 위에서 확인했으므로
            //     성공으로 보고 아무것도 바꾸지 않는다(코드 리뷰 1라운드).
            //   - 그 밖(다른 회원의 폼 로그인·다른 기기 등) → 어느 인증이 맞는지 이 요청이 정할 수 없어 세션을 끝낸다.
            Authentication sessionAuth = storedAuthentication(session);
            if (!isRememberMeOf(sessionAuth, principal)) {
                if (isFullyAuthenticatedAs(sessionAuth, principal.getId())) {
                    log.info("재인증 확인(다른 요청이 먼저 승격 — 변경 없음): user={}", userId);
                    return;
                }
                throw end(session, "세션의 인증이 바뀜");
            }
            // ① 만료와 직렬화된 세션 ID 교체(세션 고정 방지)
            if (!adminSessionService.changeSessionIdIfActive(request, principal.getId())) {
                throw end(session, "세션이 만료됐거나 레지스트리와 다름");
            }
            // ② 폼 로그인 성공 처리기·자동 로그인 사후 검증과 같은 재확인
            CustomUserDetails upgraded = (CustomUserDetails) verified.getPrincipal();
            if (!stillValid(userId, upgraded, principal.getRememberMeSeries())) {
                throw end(session, "재확인 실패");
            }
            // ③ 새 컨텍스트 — 공유 중인 기존 컨텍스트 객체는 건드리지 않는다
            upgraded.setRememberMeSeries(principal.getRememberMeSeries());
            UsernamePasswordAuthenticationToken token =
                    UsernamePasswordAuthenticationToken.authenticated(upgraded, null, upgraded.getAuthorities());
            token.setDetails(current.getDetails());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(token);
            // ④ 잡아 둔 세션에 직접 저장 — 무효화됐으면 예외(저장소 기본값처럼 새 세션을 만들지 않는다)
            try {
                session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            } catch (IllegalStateException invalidated) {
                throw end(session, "세션이 무효화됨");
            }
            SecurityContextHolder.setContext(context);
            // ⑤ 레지스트리·컨텍스트가 같은 회원인지
            SessionInformation info;
            Object stored;
            try {
                info = sessionRegistry.getSessionInformation(session.getId());
                stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            } catch (IllegalStateException invalidated) {
                throw end(session, "세션이 무효화됨");
            }
            if (stored != context || info == null
                    || !(info.getPrincipal() instanceof CustomUserDetails registered) || !principal.getId().equals(registered.getId())) {
                throw end(session, "승격 뒤 레지스트리·컨텍스트 불일치");
            }
        }
        log.info("재인증 승격: user={}", userId);
    }

    /** remember-me 인증이고 회원·기기 시리즈가 {@code expected}와 같은가(인증 객체가 새로 만들어졌어도 같은 주체). */
    private boolean isRememberMeOf(Authentication authentication, CustomUserDetails expected) {
        return trustResolver.isRememberMe(authentication)
                && authentication.getPrincipal() instanceof CustomUserDetails details
                && expected.getId().equals(details.getId())
                && Objects.equals(expected.getRememberMeSeries(), details.getRememberMeSeries());
    }

    private boolean isFullyAuthenticatedAs(Authentication authentication, Long memberId) {
        return trustResolver.isFullyAuthenticated(authentication)
                && authentication.getPrincipal() instanceof CustomUserDetails details && memberId.equals(details.getId());
    }

    private static Authentication storedAuthentication(HttpSession session) {
        try {
            Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            return stored instanceof SecurityContext context ? context.getAuthentication() : null;
        } catch (IllegalStateException invalidated) {
            return null;
        }
    }

    /** 폼 로그인 성공 처리기와 같은 판정 + 시리즈 생존 + 탈취 폐기 대기 아님. 예외는 거부(fail-closed). */
    private boolean stillValid(String userId, CustomUserDetails verified, String series) {
        try {
            Optional<MemberSnapshot> found = loginFailureService.resetFailuresAndCheckActive(userId);
            if (found.isEmpty() || found.get().status() != MemberStatus.ACTIVE) {
                return false;
            }
            MemberSnapshot snapshot = found.get();
            boolean roleMatches = verified.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).anyMatch(a -> a.equals(snapshot.role().name()));
            if (!roleMatches || verified.getPassword() == null || !verified.getPassword().equals(snapshot.passwordHash())) {
                return false;
            }
            if (rememberMeServices.isTheftRevocationPending(userId)) {
                return false;
            }
            return series == null || rememberMeTokens.existsSeries(series);
        } catch (RuntimeException e) {
            log.error("재인증 재확인 실패 — 승격하지 않습니다 (user={})", userId, e);
            return false;
        }
    }

    /** 폼 로그인 실패 처리기와 같은 집계. 이번 실패로 잠겼으면 true, 아니면 false, 기록 자체가 실패하면 ERROR 로그 + {@code null}(호출자가 세션을 끝낸다). */
    private Boolean recordFailure(String userId, HttpServletRequest request) {
        try {
            String uri = request.getRequestURI();
            if (uri != null && uri.length() > MAX_URI_LENGTH) {
                uri = uri.substring(0, MAX_URI_LENGTH);
            }
            return loginFailureService.recordFailure(userId, ClientIpResolver.resolve(request), uri);
        } catch (RuntimeException e) {
            log.error("재인증 실패 카운트 기록 실패 — 세션을 끝냅니다 (user={})", userId, e);
            return null;
        }
    }

    /** 세션을 끝낸다 — 요청 시작 때의 세션 무효화 + 보안 컨텍스트 비움. 응답은 401(호출자가 던진다). */
    private ReauthenticationEndedException end(HttpSession session, String reason) {
        log.info("재인증 중 세션 종료: reason={}", reason);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // 이미 끝난 세션 — 할 일 없음
            }
        }
        SecurityContextHolder.clearContext();
        return new ReauthenticationEndedException();
    }
}
