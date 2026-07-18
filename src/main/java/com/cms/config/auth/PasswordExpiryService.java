package com.cms.config.auth;

import com.cms.admin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 비밀번호 90일 만료(PASSWORD_EXPIRED) 전이 서비스.
 *
 * <p>전이만 수행하고 예외는 던지지 않는다 — 로그인 거부는 fresh 조회 후
 * {@code CustomUserDetailsService.validateMemberStatus()}의 기존 PASSWORD_EXPIRED 분기가 담당한다.
 * 대상은 ROLE_ADMIN/ROLE_MANAGER allowlist로 한정한다(쿼리 조건 — ROLE_USER는 재설정 자격이
 * 없어 만료 전이 시 자가 복구가 불가능하므로 제외).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordExpiryService {

    /** 비밀번호 만료 기간(일) — password_changed_at이 이 일수에 도달하면 만료 */
    public static final int PASSWORD_EXPIRY_DAYS = 90;

    private final MemberRepository memberRepository;
    private final Clock clock;

    /**
     * 비밀번호가 90일에 도달한 ACTIVE 계정을 PASSWORD_EXPIRED로 전이한다 (조건부 벌크 UPDATE).
     * 호출자의 트랜잭션에 참여한다(REQUIRED) — REQUIRES_NEW는 요청당 커넥션 2개를 잡아
     * 병렬 로그인 폭주 시 풀 고갈 위험이 있어 금지(lazy unlock과 동일한 이유).
     */
    @Transactional
    public void expireIfPasswordOutdated(String userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = memberRepository.expirePasswordIfOutdated(
                userId, now.minusDays(PASSWORD_EXPIRY_DAYS), now);
        if (updated > 0) {
            log.info("비밀번호 만료 전이 (userId={})", userId);
        }
    }
}
