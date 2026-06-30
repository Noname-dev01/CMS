package com.cms.admin.visit.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.visit.domain.VisitLog;
import com.cms.config.QuerydslConfig;
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
 * 실제 MariaDB(CI service container 또는 로컬 DB)를 사용하는 JPA 슬라이스 테스트.
 *
 * <p>@AutoConfigureTestDatabase(replace = NONE): 내장 DB 대신 dev 프로필의 MariaDB 설정을 사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class) // *RepositoryImpl이 의존하는 JPAQueryFactory 빈을 슬라이스 컨텍스트에 포함
@ActiveProfiles("dev")
class VisitLogRepositoryDataJpaTest {

    @Autowired
    VisitLogRepository visitLogRepository;

    @Autowired
    MemberRepository memberRepository;

    // ==================== visit_log DDL 자동 생성 검증 ====================

    @Test
    @DisplayName("visit_log 테이블이 ddl-auto:update로 자동 생성되어 저장이 성공한다")
    void visitLog_tableDdlAutoCreate_saveSucceeds() {
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
                .build();
    }
}
