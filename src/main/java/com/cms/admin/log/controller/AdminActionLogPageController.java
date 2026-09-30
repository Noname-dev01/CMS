package com.cms.admin.log.controller;

import com.cms.admin.AdminPage;
import com.cms.admin.log.constant.AdminActionTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Clock;
import java.time.LocalDate;

@Controller
@AdminPage
@RequiredArgsConstructor
@RequestMapping("/admin/log")
public class AdminActionLogPageController {

    private final Clock clock;

    @GetMapping("/manage")
    public String manage(Model model) {
        // 드롭다운 옵션 목록 (상수 단일 출처)
        model.addAttribute("actionTypes", AdminActionTypes.ALL);
        // 기간 기본값: 최근 30일 (from=오늘-29일, to=오늘) — 오늘을 1회만 산출해 자정 경계에서 31일이 되지 않게 한다
        LocalDate today = LocalDate.now(clock);
        model.addAttribute("defaultFrom", today.minusDays(29));
        model.addAttribute("defaultTo", today);
        return "admin/log/manage";
    }
}
