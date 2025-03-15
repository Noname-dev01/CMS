package com.cms.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AdminMainController {


    @GetMapping("/admin/main")
    public String main(Model model) {
        return "/admin/index";
    }

}
