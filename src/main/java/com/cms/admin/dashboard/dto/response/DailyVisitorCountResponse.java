package com.cms.admin.dashboard.dto.response;

/**
 * 대시보드 방문자 추이 차트의 일별 집계 항목.
 *
 * @param date  ISO yyyy-MM-dd 문자열 — Thymeleaf inline JSON에서 LocalDate가 객체로
 *              풀리며 차트 라벨이 깨지므로 String으로 고정한다
 * @param count 해당 일자의 방문 수 (관리자 로그인 성공 1회 = 방문 1건)
 */
public record DailyVisitorCountResponse(String date, long count) {
}
