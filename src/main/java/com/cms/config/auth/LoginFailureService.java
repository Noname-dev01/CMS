package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 로그인 연속 실패 카운트·자동 잠금(LOCKED) 전이 서비스.
 *
 * <p>카운트 증가·잠금·해제·리셋은 전부 조건부 벌크 UPDATE로 처리한다 — 엔티티 조회 후
 * 수정은 동시 요청에서 lost update가 나고, 전체/변경 컬럼 UPDATE가 경합 상대의 잠금을
 * 되살릴 수 있다. 대상은 ROLE_ADMIN/ROLE_MANAGER allowlist로 한정한다(쿼리 조건).
 *
 * <p>감사 기록은 {@link AdminAccountAutoLockEvent} 발행 → AFTER_COMMIT 리스너 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginFailureService {

    /** 연속 실패 임계값 — 이 횟수 도달 시 LOCKED 전이 */
    private static final int LOCK_THRESHOLD = 5;

    /** 자동 잠금 유지 기간 — 경과 시 lazy 해제. PasswordResetService의 재설정 경로 해제와 공유 */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 성공 핸들러의 재확인용 스냅샷 — 인증 완료 직전의 fresh 상태·역할·비밀번호 해시.
     * 해시를 담으므로 toString에서 가린다.
     */
    public record MemberSnapshot(MemberStatus status, Role role, String passwordHash) {
        @Override
        public String toString() {
            return "MemberSnapshot{status=" + status + ", role=" + role + "}";
        }
    }

    /**
     * 로그인 실패(BadCredentials)를 기록하고 임계값 도달 시 LOCKED로 전이한다.
     *
     * @param requestIp  감사용 요청 IP — 호출자(실패 핸들러)가 컬럼 길이로 절단해 전달
     * @param requestUri 감사용 요청 URI — 동일
     */
    @Transactional
    public void recordFailure(String userId, String requestIp, String requestUri) {
        int increased = memberRepository.increaseFailedLoginCount(userId);
        if (increased == 0) {
            // 미존재·비ACTIVE·비관리자 계정 — 아무것도 하지 않는다
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int locked = memberRepository.lockIfThresholdReached(userId, LOCK_THRESHOLD, now);
        if (locked == 0) {
            return;
        }

        // clearAutomatically로 1차 캐시가 비워져 fresh 조회 — 감사·세션 만료에 쓸 memberId 확보
        Member member = memberRepository.findByUserId(userId).orElse(null);
        if (member == null) {
            // 잠금 UPDATE가 1행이었으므로 이론상 도달 불가 — 감사·세션 만료만 생략
            log.error("잠금 전이 후 회원 재조회 실패 — 감사 기록·세션 만료 생략 (userId={})", userId);
            return;
        }

        // 발행 순서 = AFTER_COMMIT 재생 순서: 세션 만료(인메모리)가 감사 저장(DB)보다 먼저
        eventPublisher.publishEvent(new AdminSessionRevokeEvent(member.getId()));
        eventPublisher.publishEvent(new AdminAccountAutoLockEvent(member.getId(), userId, requestIp, requestUri));
    }

    /**
     * 성공 로그인 시 실패 카운트를 리셋하고, 인증 완료 직전의 fresh 상태·역할·해시 스냅샷을 반환한다.
     * 리셋은 best-effort(경합으로 이미 잠겼으면 0행 no-op — 잠금 보존)지만,
     * 이 메서드의 예외는 호출자(성공 핸들러)가 로그인 거부(fail-closed)로 처리해야 한다.
     */
    @Transactional
    public Optional<MemberSnapshot> resetFailuresAndCheckActive(String userId) {
        memberRepository.resetFailedLoginCountIfActive(userId);
        // clearAutomatically로 1차 캐시가 비워져 fresh 조회
        return memberRepository.findByUserId(userId)
                .map(m -> new MemberSnapshot(m.getStatus(), m.getUserType(), m.getPwd()));
    }

    /**
     * 만료된 자동 잠금을 해제한다 — 로그인 시도 진입점(CustomUserDetailsService)에서 호출.
     * 호출자의 트랜잭션에 참여한다(REQUIRED) — REQUIRES_NEW는 요청당 커넥션 2개를 잡아
     * 병렬 로그인 폭주 시 풀 고갈 위험이 있어 금지.
     */
    @Transactional
    public boolean unlockIfLockExpired(String userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = memberRepository.unlockIfLockExpired(userId, now.minus(LOCK_DURATION), now);
        if (updated > 0) {
            log.info("자동 잠금 만료 해제 (userId={})", userId);
        }
        return updated > 0;
    }
}
