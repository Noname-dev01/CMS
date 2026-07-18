package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.config.QuerydslConfig;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.cms.config.auth.PasswordExpiryService.PASSWORD_EXPIRY_DAYS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PasswordExpiryService의 조건부 벌크 UPDATE 계약을 실제 MariaDB로 검증한다 —
 * 상태·역할·경계 조건이 얽힌 조건부 UPDATE는 mock으로 의미 있는 검증이 안 된다.
 *
 * <p>@DataJpaTest는 @Component·일반 @Configuration(AppConfig)을 등록하지 않으므로
 * 서비스와 고정 Clock을 명시적으로 Import한다. 테스트 트랜잭션은 기본 롤백된다.
 * 시간 비교는 전부 서비스와 같은 고정 Clock 기준이다 (LocalDateTime.now() 직접 호출 금지).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PasswordExpiryService.class, QuerydslConfig.class, PasswordExpiryServiceTest.FixedClockConfig.class})
@ActiveProfiles("dev")
class PasswordExpiryServiceTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 17, 12, 0, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        public Clock clock() {
            return Clock.fixed(FIXED_NOW.atZone(ZONE).toInstant(), ZONE);
        }
    }

    @Autowired
    PasswordExpiryService passwordExpiryService;

    @Autowired
    MemberRepository memberRepository;

    @PersistenceContext
    EntityManager entityManager;

    private Member createMember(Role role, MemberStatus status, LocalDateTime passwordChangedAt) {
        String unique = "pwexpiry-" + System.nanoTime();
        return memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd("encoded")
                .userName("만료테스트")
                .email(unique + "@pwexpiry.test")
                .userType(role)
                .status(status)
                .createDate(FIXED_NOW)
                .updateDate(FIXED_NOW)
                .passwordChangedAt(passwordChangedAt)
                .build());
    }

    private Member reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return memberRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("89일 경과 ACTIVE 계정은 전이되지 않는다 (90일 미달)")
    void expire_89days_staysActive() {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE,
                FIXED_NOW.minusDays(PASSWORD_EXPIRY_DAYS - 1));

        passwordExpiryService.expireIfPasswordOutdated(member.getUserId());

        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("정확히 90일 도달(== cutoff) ACTIVE 계정은 전이된다 — <= 경계 계약")
    void expire_exactly90days_transitioned() {
        Member member = createMember(Role.ROLE_MANAGER, MemberStatus.ACTIVE,
                FIXED_NOW.minusDays(PASSWORD_EXPIRY_DAYS));

        passwordExpiryService.expireIfPasswordOutdated(member.getUserId());

        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
    }

    @Test
    @DisplayName("91일 경과 ACTIVE 계정은 전이되고 updateDate가 앱 Clock now로 갱신된다")
    void expire_91days_transitionedWithUpdateDate() {
        Member member = createMember(Role.ROLE_ADMIN, MemberStatus.ACTIVE,
                FIXED_NOW.minusDays(PASSWORD_EXPIRY_DAYS + 1));

        passwordExpiryService.expireIfPasswordOutdated(member.getUserId());

        Member found = reload(member.getId());
        assertThat(found.getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
        assertThat(found.getUpdateDate()).isEqualTo(FIXED_NOW);
        // passwordChangedAt은 전이가 건드리지 않는다 — 재설정/변경만 갱신
        assertThat(found.getPasswordChangedAt()).isEqualTo(FIXED_NOW.minusDays(PASSWORD_EXPIRY_DAYS + 1));
    }

    @Test
    @DisplayName("비ACTIVE(LOCKED·PASSWORD_EXPIRED·DISABLED) 계정은 91일 경과여도 덮어쓰지 않는다 (0행)")
    void expire_nonActive_untouched() {
        LocalDateTime outdated = FIXED_NOW.minusDays(PASSWORD_EXPIRY_DAYS + 1);
        Member locked = createMember(Role.ROLE_ADMIN, MemberStatus.LOCKED, outdated);
        Member expired = createMember(Role.ROLE_ADMIN, MemberStatus.PASSWORD_EXPIRED, outdated);
        Member disabled = createMember(Role.ROLE_MANAGER, MemberStatus.DISABLED, outdated);

        passwordExpiryService.expireIfPasswordOutdated(locked.getUserId());
        passwordExpiryService.expireIfPasswordOutdated(expired.getUserId());
        passwordExpiryService.expireIfPasswordOutdated(disabled.getUserId());

        assertThat(reload(locked.getId()).getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(reload(expired.getId()).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
        assertThat(reload(disabled.getId()).getStatus()).isEqualTo(MemberStatus.DISABLED);
    }

    @Test
    @DisplayName("ACTIVE ROLE_USER는 91일 경과여도 전이되지 않는다 — 재설정 자격 없는 역할의 오만료 방지 (allowlist)")
    void expire_roleUser_neverTransitioned() {
        Member member = createMember(Role.ROLE_USER, MemberStatus.ACTIVE,
                FIXED_NOW.minusDays(PASSWORD_EXPIRY_DAYS + 1));

        passwordExpiryService.expireIfPasswordOutdated(member.getUserId());

        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("존재하지 않는 userId는 무동작이다")
    void expire_unknownUser_noop() {
        passwordExpiryService.expireIfPasswordOutdated("no-such-user-" + System.nanoTime());
        // 예외 없이 종료하면 성공 — 거부 판정은 호출자(상태 검증) 몫
    }
}
