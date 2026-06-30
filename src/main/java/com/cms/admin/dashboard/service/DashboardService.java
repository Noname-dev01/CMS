package com.cms.admin.dashboard.service;

import com.cms.admin.dashboard.dto.response.DashboardStatsResponse;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.visit.repository.VisitLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 대시보드 통계를 집계하는 서비스.
 *
 * <p>서비스 레벨 @Transactional을 사용하지 않는다. 각 count 쿼리는 Spring Data 기본 동작(SimpleJpaRepository의
 * readOnly 트랜잭션)으로 개별 트랜잭션에서 실행된다. 비트랜잭션 메서드의 try-catch가 트랜잭션 경계 밖에 있어
 * 커밋/정리 시점 예외까지 완전히 통제한다(단일 @Transactional + 내부 try-catch는 커밋 시점 예외를 못 잡는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final MemberRepository memberRepository;
    private final VisitLogRepository visitLogRepository;
    private final Clock clock;

    /**
     * 대시보드 통계 4종을 집계해 반환한다.
     *
     * <p>어느 count라도 실패하면 4개 필드를 모두 null(조회 불가)로 반환한다(전체 단위 폴백).
     * 장애 시 정상 0건처럼 보이지 않도록 null과 0L을 구분한다.
     */
    public DashboardStatsResponse getDashboardStats() {
        try {
            LocalDate today = LocalDate.now(clock);

            LocalDateTime todayStart = today.atStartOfDay();
            LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
            LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
            LocalDateTime nextMonthStart = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

            long newMembers = memberRepository
                    .countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                            List.of(Role.ROLE_ADMIN, Role.ROLE_MANAGER),
                            MemberStatus.DELETED,
                            monthStart,
                            nextMonthStart
                    );

            long todayVisitors = visitLogRepository
                    .countByVisitAtGreaterThanEqualAndVisitAtLessThan(todayStart, tomorrowStart);

            long monthVisitors = visitLogRepository
                    .countByVisitAtGreaterThanEqualAndVisitAtLessThan(monthStart, nextMonthStart);

            long totalVisitors = visitLogRepository.count();

            return DashboardStatsResponse.builder()
                    .newMembersThisMonth(newMembers)
                    .todayVisitors(todayVisitors)
                    .monthVisitors(monthVisitors)
                    .totalVisitors(totalVisitors)
                    .build();

        } catch (Exception e) {
            log.error("대시보드 통계 조회 실패 — 전체 폴백(null) 반환", e);
            return DashboardStatsResponse.builder().build(); // 모든 필드 null
        }
    }
}
