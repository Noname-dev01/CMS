package com.cms.admin.dashboard.service;

import com.cms.admin.dashboard.dto.response.DailyVisitorCountResponse;
import com.cms.admin.dashboard.dto.response.DashboardStatsResponse;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.visit.repository.VisitLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private MemberRepository memberRepository;
    private VisitLogRepository visitLogRepository;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        visitLogRepository = mock(VisitLogRepository.class);
    }

    private DashboardService serviceWithClock(Clock clock) {
        return new DashboardService(memberRepository, visitLogRepository, clock);
    }

    /** 특정 날짜(KST 고정)를 반환하는 Clock을 생성한다. */
    private Clock fixedKst(LocalDate date) {
        return Clock.fixed(
                date.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")
        );
    }

    // ==================== 정상 집계 ====================

    @Test
    @DisplayName("정상 집계: 4개 카드 값이 올바르게 조립된다")
    void getDashboardStats_normalCase_returnsStats() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(3L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(5L, 20L);
        given(visitLogRepository.count()).willReturn(100L);

        DashboardStatsResponse stats = dashboardService.getDashboardStats();

        assertThat(stats.getNewMembersThisMonth()).isEqualTo(3L);
        assertThat(stats.getTodayVisitors()).isEqualTo(5L);
        assertThat(stats.getMonthVisitors()).isEqualTo(20L);
        assertThat(stats.getTotalVisitors()).isEqualTo(100L);
    }

    @Test
    @DisplayName("정상 0건: null이 아닌 0L을 반환한다(장애와 구분)")
    void getDashboardStats_zeroCount_returnsZeroNotNull() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(0L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(0L);
        given(visitLogRepository.count()).willReturn(0L);

        DashboardStatsResponse stats = dashboardService.getDashboardStats();

        assertThat(stats.getNewMembersThisMonth()).isEqualTo(0L);
        assertThat(stats.getTodayVisitors()).isEqualTo(0L);
        assertThat(stats.getMonthVisitors()).isEqualTo(0L);
        assertThat(stats.getTotalVisitors()).isEqualTo(0L);
    }

    // ==================== 날짜 경계 검증 ====================

    @Test
    @DisplayName("오늘 방문자 경계: 오늘 자정~내일 자정 [start, end) 반열린구간이 전달된다")
    @SuppressWarnings("unchecked")
    void getDashboardStats_todayBoundary() {
        LocalDate date = LocalDate.of(2024, 6, 15);
        Clock clock = fixedKst(date);
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(0L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(0L);
        given(visitLogRepository.count()).willReturn(0L);

        dashboardService.getDashboardStats();

        // 오늘 방문자: 첫 번째 호출
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(visitLogRepository, atLeastOnce())
                .countByVisitAtGreaterThanEqualAndVisitAtLessThan(startCaptor.capture(), endCaptor.capture());

        LocalDateTime todayStart = LocalDateTime.of(2024, 6, 15, 0, 0, 0);
        LocalDateTime tomorrowStart = LocalDateTime.of(2024, 6, 16, 0, 0, 0);
        assertThat(startCaptor.getAllValues()).contains(todayStart);
        assertThat(endCaptor.getAllValues()).contains(tomorrowStart);
    }

    @Test
    @DisplayName("이번달 경계: 월 첫날 자정~다음달 첫날 자정 [start, end) 반열린구간이 전달된다")
    @SuppressWarnings("unchecked")
    void getDashboardStats_monthBoundary() {
        LocalDate date = LocalDate.of(2024, 6, 15);
        Clock clock = fixedKst(date);
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(0L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(0L);
        given(visitLogRepository.count()).willReturn(0L);

        dashboardService.getDashboardStats();

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(visitLogRepository, atLeastOnce())
                .countByVisitAtGreaterThanEqualAndVisitAtLessThan(startCaptor.capture(), endCaptor.capture());

        LocalDateTime monthStart = LocalDateTime.of(2024, 6, 1, 0, 0, 0);
        LocalDateTime nextMonthStart = LocalDateTime.of(2024, 7, 1, 0, 0, 0);
        assertThat(startCaptor.getAllValues()).contains(monthStart);
        assertThat(endCaptor.getAllValues()).contains(nextMonthStart);
    }

    @Test
    @DisplayName("월말 경계: 12월 31일에 신규회원 경계가 다음 해 1월 1일로 계산된다")
    @SuppressWarnings("unchecked")
    void getDashboardStats_yearEndBoundary() {
        LocalDate date = LocalDate.of(2024, 12, 31);
        Clock clock = fixedKst(date);
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(0L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(0L);
        given(visitLogRepository.count()).willReturn(0L);

        dashboardService.getDashboardStats();

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(memberRepository).countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isEqualTo(LocalDateTime.of(2024, 12, 1, 0, 0, 0));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0, 0));
    }

    // ==================== 신규회원 쿼리 인자 검증 ====================

    @Test
    @DisplayName("신규회원 count 호출 시 ROLE_ADMIN·ROLE_MANAGER 목록과 DELETED 상태가 전달된다")
    @SuppressWarnings("unchecked")
    void getDashboardStats_newMembers_correctArguments() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(0L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(0L);
        given(visitLogRepository.count()).willReturn(0L);

        dashboardService.getDashboardStats();

        ArgumentCaptor<Collection<Role>> rolesCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<MemberStatus> statusCaptor = ArgumentCaptor.forClass(MemberStatus.class);
        verify(memberRepository).countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                rolesCaptor.capture(), statusCaptor.capture(), any(), any());

        assertThat(rolesCaptor.getValue()).containsExactlyInAnyOrder(Role.ROLE_ADMIN, Role.ROLE_MANAGER);
        assertThat(statusCaptor.getValue()).isEqualTo(MemberStatus.DELETED);
    }

    // ==================== 전체 단위 폴백 검증 ====================

    @Test
    @DisplayName("신규회원 count 실패 시 4개 필드 모두 null 반환(전체 단위 폴백)")
    void getDashboardStats_newMembersFails_returnsAllNull() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willThrow(new RuntimeException("DB 연결 오류"));

        DashboardStatsResponse stats = dashboardService.getDashboardStats();

        assertThat(stats.getNewMembersThisMonth()).isNull();
        assertThat(stats.getTodayVisitors()).isNull();
        assertThat(stats.getMonthVisitors()).isNull();
        assertThat(stats.getTotalVisitors()).isNull();
    }

    @Test
    @DisplayName("오늘 방문자 count 실패 시 4개 필드 모두 null 반환(전체 단위 폴백)")
    void getDashboardStats_todayVisitorsFails_returnsAllNull() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(1L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willThrow(new RuntimeException("DB 연결 오류"));

        DashboardStatsResponse stats = dashboardService.getDashboardStats();

        assertThat(stats.getNewMembersThisMonth()).isNull();
        assertThat(stats.getTodayVisitors()).isNull();
        assertThat(stats.getMonthVisitors()).isNull();
        assertThat(stats.getTotalVisitors()).isNull();
    }

    @Test
    @DisplayName("총 방문자 count 실패 시 4개 필드 모두 null 반환(전체 단위 폴백)")
    void getDashboardStats_totalVisitorsFails_returnsAllNull() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(memberRepository.countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
                any(), any(), any(), any())).willReturn(1L);
        given(visitLogRepository.countByVisitAtGreaterThanEqualAndVisitAtLessThan(
                any(), any())).willReturn(2L);
        given(visitLogRepository.count()).willThrow(new RuntimeException("DB 연결 오류"));

        DashboardStatsResponse stats = dashboardService.getDashboardStats();

        assertThat(stats.getNewMembersThisMonth()).isNull();
        assertThat(stats.getTodayVisitors()).isNull();
        assertThat(stats.getMonthVisitors()).isNull();
        assertThat(stats.getTotalVisitors()).isNull();
    }

    // ==================== getDailyVisitorCounts ====================

    @Test
    @DisplayName("최근 7일 방문자: 방문 없는 날은 0으로 채워 항상 7개 요소, [start, end) 구간으로 조회한다")
    void getDailyVisitorCounts_fillsGapsWithZero_andQueriesHalfOpenInterval() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        // 6/13, 6/15에만 방문 존재 — 나머지 5일은 0으로 채워져야 한다
        given(visitLogRepository.countDailyVisits(any(), any())).willReturn(List.<Object[]>of(
                new Object[]{LocalDate.of(2024, 6, 13), 2L},
                new Object[]{LocalDate.of(2024, 6, 15), 5L}
        ));

        List<DailyVisitorCountResponse> result = dashboardService.getDailyVisitorCounts();

        assertThat(result).hasSize(7);
        assertThat(result).extracting(DailyVisitorCountResponse::date).containsExactly(
                "2024-06-09", "2024-06-10", "2024-06-11", "2024-06-12", "2024-06-13", "2024-06-14", "2024-06-15");
        assertThat(result).extracting(DailyVisitorCountResponse::count)
                .containsExactly(0L, 0L, 0L, 0L, 2L, 0L, 5L);

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(visitLogRepository).countDailyVisits(startCaptor.capture(), endCaptor.capture());
        assertThat(startCaptor.getValue()).isEqualTo(LocalDate.of(2024, 6, 9).atStartOfDay());
        assertThat(endCaptor.getValue()).isEqualTo(LocalDate.of(2024, 6, 16).atStartOfDay()); // 오늘 다음날 0시 (미포함 상한)
    }

    @Test
    @DisplayName("날짜 타입 방어: java.sql.Date·String·Number count도 정상 변환된다 (드라이버/Hibernate 타입 해석 차이)")
    void getDailyVisitorCounts_convertsSqlDateStringAndNumber() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(visitLogRepository.countDailyVisits(any(), any())).willReturn(List.<Object[]>of(
                new Object[]{java.sql.Date.valueOf("2024-06-14"), java.math.BigInteger.valueOf(3)},
                new Object[]{"2024-06-15", 7}
        ));

        List<DailyVisitorCountResponse> result = dashboardService.getDailyVisitorCounts();

        assertThat(result).hasSize(7);
        assertThat(result.get(5)).isEqualTo(new DailyVisitorCountResponse("2024-06-14", 3L));
        assertThat(result.get(6)).isEqualTo(new DailyVisitorCountResponse("2024-06-15", 7L));
    }

    @Test
    @DisplayName("예상하지 못한 날짜 타입이 오면 빈 리스트로 폴백한다 (null 반환 금지)")
    void getDailyVisitorCounts_unexpectedDateType_fallsBackToEmptyList() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(visitLogRepository.countDailyVisits(any(), any())).willReturn(List.<Object[]>of(
                new Object[]{12345L, 3L} // 지원하지 않는 타입
        ));

        List<DailyVisitorCountResponse> result = dashboardService.getDailyVisitorCounts();

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("집계 쿼리 실패 시 빈 리스트로 폴백한다 — 대시보드 500 금지, null 반환 금지")
    void getDailyVisitorCounts_queryFails_fallsBackToEmptyList() {
        Clock clock = fixedKst(LocalDate.of(2024, 6, 15));
        dashboardService = serviceWithClock(clock);

        given(visitLogRepository.countDailyVisits(any(), any()))
                .willThrow(new RuntimeException("DB 연결 오류"));

        List<DailyVisitorCountResponse> result = dashboardService.getDailyVisitorCounts();

        assertThat(result).isNotNull().isEmpty();
    }
}
