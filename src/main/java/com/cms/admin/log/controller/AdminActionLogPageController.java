package com.cms.admin.log.controller;

import com.cms.admin.AdminPage;
import com.cms.admin.log.constant.AdminActionTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;

@Controller
@AdminPage
@RequiredArgsConstructor
@RequestMapping("/admin/log")
public class AdminActionLogPageController {

    @GetMapping("/manage")
    public String manage(Model model) {
        // 드롭다운 옵션 목록 (상수 단일 출처)
        model.addAttribute("actionTypes", AdminActionTypes.ALL);
        // 기간 기본값: 최근 30일 (from=오늘-29일, to=오늘)
        model.addAttribute("defaultFrom", LocalDate.now().minusDays(29));
        model.addAttribute("defaultTo", LocalDate.now());
        return "admin/log/manage";
    }
}
