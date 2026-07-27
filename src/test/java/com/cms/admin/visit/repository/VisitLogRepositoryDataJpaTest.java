package com.cms.admin.visit.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.visit.domain.VisitLog;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers가 띄우는 일회용 MariaDB를 사용하는 JPA 슬라이스 테스트({@link MariaDbContainerSupport}).
 *
 * <p>@AutoConfigureTestDatabase(replace = NONE): 내장 DB 대신 {@code @ServiceConnection}이
 * 연결한 MariaDB 컨테이너를 사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class) // *RepositoryImpl이 의존하는 JPAQueryFactory 빈을 슬라이스 컨텍스트에 포함
@ActiveProfiles("dev")
class VisitLogRepositoryDataJpaTest extends MariaDbContainerSupport {

    @Autowired
    VisitLogRepository visitLogRepository;

    @Autowired
    MemberRepository memberRepository;

    // ==================== visit_log 테이블 존재 검증 ====================

    @Test
    @DisplayName("visit_log 테이블이 Flyway 마이그레이션으로 생성되어 저장이 성공한다")
    void visitLog_tableCreatedByFlyway_saveSucceeds() {
        VisitLog log = VisitLog.builder()
                .visitorUserId("testAdmin")
                .requestIp("127.0.0.1")
                .visitAt(LocalDateTime.now())
                .build();

        VisitLog saved = visitLogRepository.save(log);

        assertThat(saved.getId()).isNotNull();
    }

    // ==================== 반열린구간 count 검증 ====================

    @Test
    @DisplayName("오늘 방문자: [todayStart, tomorrowStart) 내 데이터만 카운트된다")
    void countByVisitAt_halfOpenInterval_countCorrectly() {
        LocalDateTime today = LocalDateTime.of(2024, 6, 15, 0, 0, 0);
        LocalDateTime tomorrow = LocalDateTime.of(2024, 6, 16, 0, 0, 0);

        // 범위 안
        visitLogRepository.save(VisitLog.builder().visitorUserId("u1").requestIp("1.1.1.1").visitAt(today).build());
        visitLogRepository.save(VisitLog.builder().visitorUserId("u2").requestIp("1.1.1.2").visitAt(today.plusHours(12)).build());
        // 경계값 포함(start = 포함, end = 미포함)
        visitLogRepository.save(VisitLog.builder().visitorUserId("u3").requestIp("1.1.1.3").visitAt(today.minusSeconds(1)).build()); // 제외
        visitLogRepository.save(VisitLog.builder().visitorUserId("u4").requestIp("1.1.1.4").visitAt(tomorrow).build()); // 제외

        long count = visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(today, tomorrow);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("totalVisitors: count()는 저장된 모든 visit_log를 반환한다")
    void count_returnsAllVisitLogs() {
        visitLogRepository.deleteAll(); // 이전 테스트 데이터 정리

        visitLogRepository.save(VisitLog.builder().visitorUserId("u1").requestIp("1.1.1.1").visitAt(LocalDateTime.now()).build());
        visitLogRepository.save(VisitLog.builder().visitorUserId("u2").requestIp("1.1.1.2").visitAt(LocalDateTime.now()).build());
        visitLogRepository.save(VisitLog.builder().visitorUserId("u3").requestIp("1.1.1.3").visitAt(LocalDateTime.now()).build());

        assertThat(visitLogRepository.count()).isEqualTo(3);
    }

    // ==================== 일별 집계 쿼리 검증 ====================

    @Test
    @DisplayName("countDailyVisits: [start, end) 경계로 일별 그룹핑되고 날짜 오름차순이며, 반환 날짜 타입이 서비스 지원 3분기 안이다")
    void countDailyVisits_groupsByDay_halfOpenInterval_ordered() {
        // 공유 dev DB 오염 방지 — 실데이터와 충돌 가능성이 없는 먼 미래 고유 구간 사용 (계획 R2#2)
        LocalDateTime start = LocalDateTime.of(2031, 3, 10, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2031, 3, 13, 0, 0, 0);

        // 삽입 전 구간 부재 확인 — 기존 행이 있으면 여기서 명확히 실패시킨다 (묵시적 오답 방지)
        assertThat(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(start, end))
                .as("고유 테스트 구간에 기존 데이터가 존재 — 구간을 변경하라")
                .isZero();

        // 3/10 2건, 3/11 0건, 3/12 1건 + 경계 밖 2건(start 직전 포함/end 시각)
        visitLogRepository.save(VisitLog.builder().visitorUserId("d1").requestIp("1.1.1.1").visitAt(start).build()); // start 포함
        visitLogRepository.save(VisitLog.builder().visitorUserId("d2").requestIp("1.1.1.2").visitAt(start.plusHours(23)).build());
        visitLogRepository.save(VisitLog.builder().visitorUserId("d3").requestIp("1.1.1.3").visitAt(LocalDateTime.of(2031, 3, 12, 12, 0)).build());
        visitLogRepository.save(VisitLog.builder().visitorUserId("d4").requestIp("1.1.1.4").visitAt(start.minusSeconds(1)).build()); // 제외
        visitLogRepository.save(VisitLog.builder().visitorUserId("d5").requestIp("1.1.1.5").visitAt(end).build()); // 제외 (end 미포함)

        List<Object[]> rows = visitLogRepository.countDailyVisits(start, end);

        assertThat(rows).hasSize(2); // 방문 없는 3/11은 행이 없다 — 0 채움은 서비스 책임

        // 실 MariaDB 반환 타입이 서비스 변환 3분기(LocalDate/java.sql.Date/String) 안인지 단언 (계획 R2#1)
        for (Object[] row : rows) {
            assertThat(row[0])
                    .as("row[0] 실제 타입: %s", row[0] == null ? "null" : row[0].getClass().getName())
                    .matches(v -> v instanceof java.time.LocalDate || v instanceof java.sql.Date || v instanceof String);
            assertThat(row[1]).isInstanceOf(Number.class);
        }

        // 날짜 오름차순 + 그룹별 count (타입 무관 비교를 위해 문자열 정규화)
        assertThat(rows.get(0)[0].toString()).startsWith("2031-03-10");
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(2L);
        assertThat(rows.get(1)[0].toString()).startsWith("2031-03-12");
        assertThat(((Number) rows.get(1)[1]).longValue()).isEqualTo(1L);
    }

    // ==================== 신규회원 파생 쿼리 검증 ====================

    @Test
    @DisplayName("countByUserTypeIn...: ROLE_USER 계정은 집계에서 제외된다")
    void countNewMembers_excludesRoleUser() {
        LocalDateTime monthStart = LocalDateTime.of(2024, 6, 1, 0, 0, 0);
        LocalDateTime nextMonthStart = LocalDateTime.of(2024, 7, 1, 0, 0, 0);
        LocalDateTime inRange = LocalDateTime.of(2024, 6, 15, 10, 0, 0);

        // ROLE_ADMIN 1명, ROLE_MANAGER 1명, ROLE_USER 1명 저장
        memberRepository.save(member("adminUser", Role.ROLE_ADMIN, MemberStatus.ACTIVE, inRange));
        memberRepository.save(member("managerUser", Role.ROLE_MANAGER, MemberStatus.ACTIVE, inRange));
        memberRepository.save(member("normalUser", Role.ROLE_USER, MemberStatus.ACTIVE, inRange));

        long count = memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                List.of(Role.ROLE_ADMIN, Role.ROLE_MANAGER),
                MemberStatus.DELETED,
                monthStart,
                nextMonthStart
        );

        // ROLE_USER는 제외되므로 2명
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("countByUserTypeIn...: DELETED 상태 계정은 집계에서 제외된다")
    void countNewMembers_excludesDeletedStatus() {
        LocalDateTime monthStart = LocalDateTime.of(2024, 6, 1, 0, 0, 0);
        LocalDateTime nextMonthStart = LocalDateTime.of(2024, 7, 1, 0, 0, 0);
        LocalDateTime inRange = LocalDateTime.of(2024, 6, 15, 10, 0, 0);

        // ACTIVE 2명, DELETED 1명
        memberRepository.save(member("admin1", Role.ROLE_ADMIN, MemberStatus.ACTIVE, inRange));
        memberRepository.save(member("admin2", Role.ROLE_ADMIN, MemberStatus.ACTIVE, inRange));
        memberRepository.save(member("admin3", Role.ROLE_ADMIN, MemberStatus.DELETED, inRange));

        long count = memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                List.of(Role.ROLE_ADMIN, Role.ROLE_MANAGER),
                MemberStatus.DELETED,
                monthStart,
                nextMonthStart
        );

        // DELETED는 제외되므로 2명
        assertThat(count).isEqualTo(2);
    }

    private Member member(String userId, Role role, MemberStatus status, LocalDateTime createDate) {
        return Member.builder()
                .userId(userId)
                .pwd("$2a$10$dummyHashedPassword1234")
                .userName(userId)
                .email(userId + "@test.com")
                .userType(role)
                .status(status)
                .createDate(createDate)
                .updateDate(createDate)
                .passwordChangedAt(createDate)
                .build();
    }
}
