package com.cms.admin.dashboard.dto.response;

import lombok.*;

/**
 * 대시보드 통계 응답 DTO.
 * null = 조회 불가(장애), 0L = 정상 0건으로 구분한다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DashboardStatsResponse {

    /** 이번 달 신규 가입 관리 계정 수(ROLE_ADMIN·ROLE_MANAGER, DELETED 제외). null = 조회 불가 */
    private Long newMembersThisMonth;

    /** 오늘 방문자 수(ROLE_ADMIN·ROLE_MANAGER 로그인 성공 기준). null = 조회 불가 */
    private Long todayVisitors;

    /** 이번 달 방문자 수. null = 조회 불가 */
    private Long monthVisitors;

    /** 총 방문자 수(전체 기간). null = 조회 불가 */
    private Long totalVisitors;
}
