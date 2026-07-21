package com.cms.admin.notice.controller;

import com.cms.admin.AdminPage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@AdminPage
@RequestMapping("/admin/notice")
public class NoticePageController {

    @GetMapping("/manage")
    public String noticeManagePage() {
        return "admin/notice/manage";
    }
}
