package com.cms.admin.log.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 활동 로그 화면의 기간 기본값이 주입된 KST Clock의 "오늘"을 1회 산출해 쓰는지 확인한다.
 * 슬라이스 없이 컨트롤러를 직접 생성한다(Model 값만 검증하므로 컨텍스트 불필요).
 */
class AdminActionLogPageControllerTest {

    @Test
    @DisplayName("기본 기간은 KST 오늘 기준 최근 30일 — UTC 09-29 15:00(=KST 09-30)에서도 KST 날짜를 쓴다")
    void manage_defaultRange_usesKstToday() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        AdminActionLogPageController controller = new AdminActionLogPageController(clock);
        Model model = new ExtendedModelMap();

        String view = controller.manage(model);

        assertEquals("admin/log/manage", view);
        assertEquals(LocalDate.of(2026, 9, 30), model.getAttribute("defaultTo"));
        assertEquals(LocalDate.of(2026, 9, 1), model.getAttribute("defaultFrom")); // 포함 30일
    }
}
