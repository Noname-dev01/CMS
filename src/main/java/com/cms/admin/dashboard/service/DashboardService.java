package com.cms.admin.dashboard.service;

import com.cms.admin.dashboard.dto.response.DailyVisitorCountResponse;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 방문자 추이 차트의 집계 구간 길이(일) — 오늘 포함 최근 7일 */
    private static final int VISITOR_TREND_DAYS = 7;

    /**
     * 최근 7일(오늘 포함) 일별 방문자 수. 방문 없는 날은 0으로 채워 항상 7개 요소를 보장한다.
     * 실패 시 빈 리스트를 반환한다(화면에서 오류 문구 처리 — 전체 페이지 500 금지).
     * null은 어떤 경로에서도 반환하지 않는다.
     */
    public List<DailyVisitorCountResponse> getDailyVisitorCounts() {
        try {
            LocalDate today = LocalDate.now(clock);
            LocalDate firstDay = today.minusDays(VISITOR_TREND_DAYS - 1);

            List<Object[]> rows = visitLogRepository.countDailyVisits(
                    firstDay.atStartOfDay(), today.plusDays(1).atStartOfDay());

            Map<LocalDate, Long> countsByDate = new HashMap<>();
            for (Object[] row : rows) {
                countsByDate.put(toLocalDate(row[0]), ((Number) row[1]).longValue());
            }

            List<DailyVisitorCountResponse> result = new ArrayList<>(VISITOR_TREND_DAYS);
            for (LocalDate date = firstDay; !date.isAfter(today); date = date.plusDays(1)) {
                result.add(new DailyVisitorCountResponse(
                        date.toString(), countsByDate.getOrDefault(date, 0L)));
            }
            return result;

        } catch (Exception e) {
            log.error("방문자 추이 집계 실패 — 빈 리스트 폴백", e);
            return List.of();
        }
    }

    /**
     * function('date', ...) 결과를 LocalDate로 변환한다.
     * 반환 타입은 JDBC 드라이버·Hibernate 함수 타입 해석에 좌우되므로 3분기로 방어하고,
     * 그 외 타입은 실제 클래스명을 남기며 실패시킨다(호출부 폴백).
     */
    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof String text) {
            return LocalDate.parse(text);
        }
        throw new IllegalStateException("지원하지 않는 일별 집계 날짜 타입: "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
