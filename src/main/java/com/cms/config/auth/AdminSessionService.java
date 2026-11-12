package com.cms.config.auth;

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
 */
@Component
@RequiredArgsConstructor
public class AdminSessionService {

    /** 핸들 길이(hex 문자 수) — SHA-256의 앞 128비트. */
    public static final int HANDLE_LENGTH = 32;

    private final SessionRegistry sessionRegistry;

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
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CustomUserDetails userDetails) {
                for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                    if (handleOf(info.getSessionId()).equals(handle)) {
                        info.expireNow();
                        return Optional.of(toActiveSession(userDetails, info));
                    }
                }
            }
        }
        return Optional.empty();
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

    /** 역할·상태 변경에 따른 자동 만료 — 모든 활성 세션을 만료시킨다(제외 없음). */
    public void expireSessionsFor(Long memberId) {
        expireSessionsFor(memberId, null);
    }

    private ActiveSession toActiveSession(CustomUserDetails userDetails, SessionInformation info) {
        return new ActiveSession(
                handleOf(info.getSessionId()),
                userDetails.getId(),
                userDetails.getUserId(),
                userDetails.getUserName(),
                userDetails.getMember().getUserType().name(),
                info.getLastRequest().toInstant());
    }
}
