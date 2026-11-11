package com.cms.config.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 세션 레지스트리 파사드. 자동 만료(역할·상태 변경)와 세션 관리 화면(조회·수동 만료)이 함께 쓴다.
 *
 * <p>원문 세션 ID는 {@code JSESSIONID} 쿠키 값이라 이 클래스 밖으로 내보내지 않는다 — 바깥에는 {@link #handleOf}가 만든
 * 해시 핸들만 나간다(PLAN-session-management.md 쟁점 1). 조회는 원자적 스냅샷이 아니고 만료는 best-effort다
 * ({@code SessionInformation.expired}·{@code lastRequest}는 일반 필드 — 쟁점 8).
 *
 * <p><b>만료와 세션 ID 교체는 한 모니터({@link #registryMutation})로 직렬화한다</b>(PLAN-sensitive-reauthentication.md 쟁점 6 v7).
 * Spring Security 7.0.7 {@code SessionRegistryImpl}은 ID 변경 이벤트에서 옛 항목을 지우고 새로 등록해 <b>만료 표시를 옮기지 않는다</b> —
 * 만료가 교체와 겹치면(옛 항목을 집어 든 뒤 교체) 만료가 사라져 끊은 세션이 되살아난다. 그래서 레지스트리를 조회해 {@code expireNow()}하는
 * 모든 메서드와 재인증의 {@link #changeSessionIdIfActive}가 같은 모니터 안에서 돈다: 만료가 교체보다 먼저면 교체가 만료를 보고 멈추고,
 * 나중이면 새 ID 항목을 찾는다. <b>모니터 안에서는 DB를 쓰지 않는다</b>(토큰 삭제 같은 DB 작업은 호출자가 모니터 밖·만료 앞에서 한다).
 * 상수 시간은 아니다(principal·세션 순회) — 관리자 세션 수 규모에서 수용한다. 잠금 순서는 항상 세션 뮤텍스 → 이 모니터.
 */
@Component
@RequiredArgsConstructor
public class AdminSessionService {

    /** 핸들 길이(hex 문자 수) — SHA-256의 앞 128비트. */
    public static final int HANDLE_LENGTH = 32;

    private final SessionRegistry sessionRegistry;

    /** 레지스트리 만료·세션 ID 교체 직렬화용 모니터(클래스 주석 참조). */
    private final Object registryMutation = new Object();

    /** 목록에 나가는 활성 세션 한 건. 원문 세션 ID는 없다. */
    public record ActiveSession(String handle, Long memberId, String userId, String userName, String role, Instant lastRequest) {
    }

    /** 회원 단위 만료 결과 — {@code currentExcluded}는 현재 세션을 대상에서 실제로 제외했는지. */
    public record MemberExpiration(int expiredCount, boolean currentExcluded) {
    }

    /**
     * 세션 ID에서 결정적으로 도출한 핸들({@code SHA-256} 앞 32 hex). 세션 ID는 128비트 이상 난수라 핸들에서 원문을 복원할 수 없고,
     * 순번과 달리 목록과 만료 사이에 순서가 바뀌어도 같은 세션을 가리킨다.
     */
    public static String handleOf(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HANDLE_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    /** 만료되지 않은 세션 전부(회원 ID가 같은 세션은 principal이 달라도 그대로 나열). 정렬은 호출자 몫이 아니라 여기서 고정한다. */
    public List<ActiveSession> listActiveSessions() {
        List<ActiveSession> result = new ArrayList<>();
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CustomUserDetails userDetails) {
                for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                    result.add(toActiveSession(userDetails, info));
                }
            }
        }
        result.sort(Comparator.comparing(ActiveSession::lastRequest).reversed()
                .thenComparing(ActiveSession::memberId)
                .thenComparing(ActiveSession::handle));
        return result;
    }

    /**
     * 핸들에 해당하는 <b>조회 시점에 활성인</b> 세션을 만료시키고 그 세션 정보를 돌려준다. 없으면(이미 만료·소멸) 빈 값.
     * 동시에 두 요청이 같은 세션을 찾으면 둘 다 성공할 수 있다({@code expireNow()} 멱등).
     */
    public Optional<ActiveSession> expireByHandle(String handle) {
        synchronized (registryMutation) {
            return findActiveByHandle(handle).map(found -> {
                found.info().expireNow();
                return toActiveSession(found.principal(), found.info());
            });
        }
    }

    /** 핸들로 찾은 활성 세션과 그 레지스트리 principal(remember-me 시리즈를 담고 있다). */
    public record FoundSession(SessionInformation info, CustomUserDetails principal) {
    }

    /**
     * 핸들에 해당하는 <b>조회 시점에 활성인</b> 세션을 찾기만 한다(만료하지 않는다). 수동 단일 만료가 remember-me 기기 토큰을 먼저 지우고
     * 성공한 뒤에 만료하도록(삭제 실패 시 같은 핸들로 재시도 가능 — PLAN-remember-me-login.md 쟁점 3) 찾기와 만료를 나눈다.
     */
    public Optional<FoundSession> findActiveByHandle(String handle) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CustomUserDetails userDetails) {
                for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                    if (handleOf(info.getSessionId()).equals(handle)) {
                        return Optional.of(new FoundSession(info, userDetails));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 세션 ID의 레지스트리 principal이 가진 remember-me 시리즈. 등록되지 않았거나 시리즈가 없으면 {@code null}. */
    public String rememberMeSeriesOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
        return info != null && info.getPrincipal() instanceof CustomUserDetails details ? details.getRememberMeSeries() : null;
    }

    /**
     * 같은 remember-me 기기(시리즈)를 가진 활성 세션을 모두 만료한다 — 서버에 남은 세션과 쿠키로 복원한 세션이 같은 시리즈를 가질 수 있다
     * (PLAN-remember-me-login.md v5). 시리즈는 무작위 값이라 회원을 따로 거르지 않는다. 반환값은 {@code expireNow()}를 호출한 수(참고용).
     */
    public int expireSessionsWithSeries(String series) {
        if (series == null) {
            return 0;
        }
        synchronized (registryMutation) {
            int expired = 0;
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                if (principal instanceof CustomUserDetails userDetails && series.equals(userDetails.getRememberMeSeries())) {
                    for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                        info.expireNow();
                        expired++;
                    }
                }
            }
            return expired;
        }
    }

    /**
     * 아이디로 회원의 활성 세션을 모두 만료한다 — <b>DB를 조회하지 않는다</b>. remember-me 탈취 처리가 토큰을 이미 지운 뒤에 쓰므로
     * 추가 조회가 실패해 공격자 세션이 남는 일이 없어야 한다(PLAN-remember-me-login.md v5). 비교는 대소문자 무시(칼럼 콜레이션과 같은 판정).
     */
    public int expireSessionsForUserId(String userId) {
        if (userId == null) {
            return 0;
        }
        synchronized (registryMutation) {
            int expired = 0;
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                if (principal instanceof CustomUserDetails userDetails && userId.equalsIgnoreCase(userDetails.getUserId())) {
                    for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                        info.expireNow();
                        expired++;
                    }
                }
            }
            return expired;
        }
    }

    /**
     * 지정 회원의 활성 세션을 만료 처리한다. {@code exceptSessionId}(현재 세션)는 있으면 건너뛴다.
     * principal의 equals/hashCode에 의존하지 않고(CustomUserDetails가 equals 미구현) member id를 직접 비교해 대상을 찾는다.
     * {@code expiredCount}는 "조회 시점에 활성이던 세션에 {@code expireNow()}를 호출한 수"다(엄밀한 신규 만료 수 아님).
     *
     * <p>만료된 세션의 다음 요청은 ConcurrentSessionFilter가 가로채 {@code AdminSessionExpiredStrategy}에 따라 거부된다.
     */
    public MemberExpiration expireSessionsFor(Long memberId, String exceptSessionId) {
        if (memberId == null) {
            return new MemberExpiration(0, false);
        }
        synchronized (registryMutation) {
            int expired = 0;
            boolean excluded = false;
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                if (principal instanceof CustomUserDetails userDetails && memberId.equals(userDetails.getId())) {
                    for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                        if (exceptSessionId != null && exceptSessionId.equals(info.getSessionId())) {
                            excluded = true;
                            continue;
                        }
                        info.expireNow();
                        expired++;
                    }
                }
            }
            return new MemberExpiration(expired, excluded);
        }
    }

    /**
     * 재인증 승격의 세션 ID 교체(세션 고정 방지). 만료 경로와 같은 모니터 안에서: 현재 세션의 레지스트리 항목이 <b>있고·만료되지 않았고·같은 회원</b>일
     * 때만 {@code request.changeSessionId()}를 하고 true. 아니면 교체하지 않고 false(호출자가 세션을 끝낸다).
     *
     * <p>컨테이너의 ID 변경 리스너({@code HttpSessionEventPublisher} → 레지스트리)는 이 호출 안에서 동기로 돈다. 다만 Tomcat은 리스너 예외를
     * 삼키므로 정상 반환이 이동을 보장하지 않는다 — 새 ID 항목이 없으면 여기서 직접 옮긴다(리스너가 했을 일과 같다: 옛 항목 제거 → 같은
     * principal로 새 ID 등록. 리스너가 없는 MockMvc도 같은 상태가 된다). 레지스트리가 옮긴 항목은 만료 표시를 잃지만, 이 모니터 안에서는
     * 만료가 끼어들 수 없으므로 교체 직전에 본 "만료 아님"이 그대로 유효하다.
     */
    public boolean changeSessionIdIfActive(HttpServletRequest request, Long memberId) {
        HttpSession session = request.getSession(false);
        if (session == null || memberId == null) {
            return false;
        }
        synchronized (registryMutation) {
            String oldId = session.getId();
            SessionInformation current = sessionRegistry.getSessionInformation(oldId);
            if (current == null || current.isExpired()
                    || !(current.getPrincipal() instanceof CustomUserDetails details) || !memberId.equals(details.getId())) {
                return false;
            }
            Object principal = current.getPrincipal();
            String newId = request.changeSessionId();
            if (sessionRegistry.getSessionInformation(newId) == null) {
                sessionRegistry.removeSessionInformation(oldId);
                sessionRegistry.registerNewSession(newId, principal);
            }
            SessionInformation moved = sessionRegistry.getSessionInformation(newId);
            return moved != null && !moved.isExpired()
                    && moved.getPrincipal() instanceof CustomUserDetails movedDetails && memberId.equals(movedDetails.getId());
        }
    }

    /** 역할·상태 변경에 따른 자동 만료 — 모든 활성 세션을 만료시킨다(제외 없음). */
    public void expireSessionsFor(Long memberId) {
        expireSessionsFor(memberId, null);
    }

    private static ActiveSession toActiveSession(CustomUserDetails userDetails, SessionInformation info) {
        return new ActiveSession(
                handleOf(info.getSessionId()),
                userDetails.getId(),
                userDetails.getUserId(),
                userDetails.getUserName(),
                userDetails.getMember().getUserType().name(),
                info.getLastRequest().toInstant());
    }
}
