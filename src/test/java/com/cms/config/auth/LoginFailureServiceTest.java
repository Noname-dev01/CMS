package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.config.QuerydslConfig;
import com.cms.config.auth.LoginFailureService.MemberSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginFailureService의 벌크 UPDATE 계약을 실제 MariaDB로 검증한다 —
 * 원자적 증가·조건부 잠금·조건부 리셋·만료 해제는 mock으로 의미 있는 검증이 안 된다.
 *
 * <p>@DataJpaTest는 @Component·일반 @Configuration(AppConfig)을 등록하지 않으므로
 * 서비스와 고정 Clock을 명시적으로 Import한다. 테스트 트랜잭션은 기본 롤백된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LoginFailureService.class, QuerydslConfig.class, LoginFailureServiceTest.FixedClockConfig.class})
@RecordApplicationEvents
@ActiveProfiles("dev")
class LoginFailureServiceTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 14, 12, 0, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        public Clock clock() {
            return Clock.fixed(FIXED_NOW.atZone(ZONE).toInstant(), ZONE);
        }
    }

    @Autowired
    LoginFailureService loginFailureService;

    @Autowired
    MemberRepository memberRepository;

    @PersistenceContext
    EntityManager entityManager;

    private Member createMember(Role role, MemberStatus status) {
        String unique = "lockout-" + System.nanoTime();
        return memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd("encoded")
                .userName("잠금테스트")
                .email(unique + "@lockout.test")
                .userType(role)
                .status(status)
                .createDate(FIXED_NOW)
                .updateDate(FIXED_NOW)
                .passwordChangedAt(FIXED_NOW)
                .build());
    }

    private Member reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return memberRepository.findById(id).orElseThrow();
    }

    // ==================== recordFailure ====================

    @Test
    @DisplayName("4회 실패까지는 ACTIVE 유지, 카운트만 4로 증가한다")
    void recordFailure_fourTimes_staysActive(ApplicationEvents events) {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        for (int i = 0; i < 4; i++) {
            loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
        }

        Member found = reload(member.getId());
        assertThat(found.getFailedLoginCount()).isEqualTo(4);
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(found.getLockedAt()).isNull();
        assertThat(events.stream(AdminAccountAutoLockEvent.class)).isEmpty();
        assertThat(events.stream(AdminSessionRevokeEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("5회째 실패에 LOCKED 전이 + 잠금 시각(앱 Clock) 기록 + 세션 폐기·감사 이벤트가 각 1회 발행된다")
    void recordFailure_fifthTime_locksAndPublishesEvents(ApplicationEvents events) {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        for (int i = 0; i < 5; i++) {
            loginFailureService.recordFailure(member.getUserId(), "1.2.3.4", "/admin/login");
        }

        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(found.getFailedLoginCount()).isEqualTo(5);
        assertThat(found.getLockedAt()).isEqualTo(FIXED_NOW);

        assertThat(events.stream(AdminSessionRevokeEvent.class))
                .containsExactly(new AdminSessionRevokeEvent(member.getId()));
        assertThat(events.stream(AdminAccountAutoLockEvent.class))
                .containsExactly(new AdminAccountAutoLockEvent(
                        member.getId(), member.getUserId(), "1.2.3.4", "/admin/login"));
    }

    @Test
    @DisplayName("잠금 이후의 실패는 카운트를 증가시키지 않는다 (ACTIVE 조건 — 카운트는 5에서 멈춤)")
    void recordFailure_afterLock_noFurtherIncrement() {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        for (int i = 0; i < 8; i++) {
            loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
        }

        Member found = reload(member.getId());
        assertThat(found.getFailedLoginCount()).isEqualTo(5);
        assertThat(found.getStatus()).isEqualTo(MemberStatus.LOCKED);
    }

    @Test
    @DisplayName("존재하지 않는 userId는 무동작·이벤트 없음")
    void recordFailure_unknownUser_noop(ApplicationEvents events) {
        loginFailureService.recordFailure("no-such-user-" + System.nanoTime(), "127.0.0.1", "/admin/login");

        assertThat(events.stream(AdminAccountAutoLockEvent.class)).isEmpty();
        assertThat(events.stream(AdminSessionRevokeEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("비ACTIVE(DISABLED) 계정은 카운트가 증가하지 않는다 (숨은 누적 차단)")
    void recordFailure_disabledMember_notCounted() {
        Member member = createMember(Role.ROLE_MANAGER, MemberStatus.DISABLED);

        loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");

        assertThat(reload(member.getId()).getFailedLoginCount()).isZero();
    }

    @Test
    @DisplayName("ROLE_USER 계정은 5회 실패해도 상태·카운트가 변하지 않고 이벤트도 없다 (역할 allowlist)")
    void recordFailure_roleUser_neverLocked(ApplicationEvents events) {
        Member member = createMember(Role.ROLE_USER, MemberStatus.ACTIVE);

        for (int i = 0; i < 5; i++) {
            loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
        }

        Member found = reload(member.getId());
        assertThat(found.getFailedLoginCount()).isZero();
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(events.stream(AdminAccountAutoLockEvent.class)).isEmpty();
        assertThat(events.stream(AdminSessionRevokeEvent.class)).isEmpty();
    }

    // ==================== resetFailuresAndCheckActive ====================

    @Test
    @DisplayName("ACTIVE 계정 리셋 — 카운트 0·lockedAt null, fresh 스냅샷(상태·역할·해시) 반환")
    void resetFailures_activeMember_resetsAndReturnsSnapshot() {
        Member member = createMember(Role.ROLE_MANAGER, MemberStatus.ACTIVE);
        for (int i = 0; i < 3; i++) {
            loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
        }

        Optional<MemberSnapshot> snapshot = loginFailureService.resetFailuresAndCheckActive(member.getUserId());

        assertThat(snapshot).hasValue(new MemberSnapshot(MemberStatus.ACTIVE, Role.ROLE_MANAGER, "encoded"));
        assertThat(reload(member.getId()).getFailedLoginCount()).isZero();
    }

    @Test
    @DisplayName("LOCKED 계정 리셋은 0행 no-op — 상태·카운트·lockedAt 보존 (더티체킹이었다면 훼손됐을 케이스)")
    void resetFailures_lockedMember_noop() {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        for (int i = 0; i < 5; i++) {
            loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
        }

        Optional<MemberSnapshot> snapshot = loginFailureService.resetFailuresAndCheckActive(member.getUserId());

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().status()).isEqualTo(MemberStatus.LOCKED);
        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(found.getFailedLoginCount()).isEqualTo(5);
        assertThat(found.getLockedAt()).isEqualTo(FIXED_NOW);
    }

    // ==================== unlockIfLockExpired ====================

    private Member lockedMemberWithLockedAt(LocalDateTime lockedAt) {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        entityManager.createNativeQuery(
                        "UPDATE member SET status = 'LOCKED', failed_login_count = 5, locked_at = :lockedAt WHERE id = :id")
                .setParameter("lockedAt", lockedAt)
                .setParameter("id", member.getId())
                .executeUpdate();
        entityManager.clear();
        return member;
    }

    @Test
    @DisplayName("잠금 후 정확히 30분(cutoff와 동일)이면 해제된다 — <= 경계 계약")
    void unlock_exactlyAtCutoff_released() {
        Member member = lockedMemberWithLockedAt(FIXED_NOW.minusMinutes(30));

        boolean released = loginFailureService.unlockIfLockExpired(member.getUserId());

        assertThat(released).isTrue();
        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(found.getFailedLoginCount()).isZero();
        assertThat(found.getLockedAt()).isNull();
        assertThat(found.getUpdateDate()).isEqualTo(FIXED_NOW); // updateDate도 앱 Clock으로 갱신
    }

    @Test
    @DisplayName("잠금 후 30분 직전(cutoff 이후)이면 해제되지 않는다")
    void unlock_justBeforeExpiry_notReleased() {
        Member member = lockedMemberWithLockedAt(FIXED_NOW.minusMinutes(30).plusSeconds(1));

        assertThat(loginFailureService.unlockIfLockExpired(member.getUserId())).isFalse();
        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.LOCKED);
    }

    @Test
    @DisplayName("잠금 후 30분 초과(cutoff 직전)면 해제된다")
    void unlock_afterExpiry_released() {
        Member member = lockedMemberWithLockedAt(FIXED_NOW.minusMinutes(31));

        assertThat(loginFailureService.unlockIfLockExpired(member.getUserId())).isTrue();
        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("수동 잠금(locked_at null)은 해제되지 않는다")
    void unlock_manualLock_notReleased() {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        entityManager.createNativeQuery("UPDATE member SET status = 'LOCKED', locked_at = NULL WHERE id = :id")
                .setParameter("id", member.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(loginFailureService.unlockIfLockExpired(member.getUserId())).isFalse();
        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.LOCKED);
    }

    // ==================== @DynamicUpdate 더티체킹 잠금 보존 ====================

    @Test
    @DisplayName("행 잠금 없는 더티체킹 쓰기(updateInfo)가 동시 자동 잠금을 되쓰지 않는다 (@DynamicUpdate)")
    void dirtyChecking_doesNotOverwriteConcurrentLock() {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        // 엔티티를 영속 상태로 로드 (경합 시나리오의 "먼저 읽은" 쓰기 경로)
        Member loaded = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(MemberStatus.ACTIVE);

        // 같은 물리 트랜잭션의 네이티브 UPDATE로 영속성 컨텍스트만 우회해 잠금 커밋을 흉내낸다
        // (독립 트랜잭션은 REPEATABLE READ 스냅샷 때문에 거짓 실패 — 계획 v11)
        entityManager.createNativeQuery(
                        "UPDATE member SET status = 'LOCKED', failed_login_count = 5, locked_at = :lockedAt WHERE id = :id")
                .setParameter("lockedAt", FIXED_NOW)
                .setParameter("id", member.getId())
                .executeUpdate();

        // stale 엔티티(status=ACTIVE로 읽음)의 더티체킹 flush — @DynamicUpdate라 변경 컬럼만 UPDATE
        loaded.updateInfo("변경된이름", loaded.getEmail());
        entityManager.flush();
        entityManager.clear();

        Member found = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(found.getUserName()).isEqualTo("변경된이름");          // 더티체킹 UPDATE가 실제 실행됨
        assertThat(found.getStatus()).isEqualTo(MemberStatus.LOCKED);   // 잠금은 보존됨
        assertThat(found.getFailedLoginCount()).isEqualTo(5);
        assertThat(found.getLockedAt()).isEqualTo(FIXED_NOW);
    }
}
