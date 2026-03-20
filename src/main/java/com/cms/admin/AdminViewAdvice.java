package com.cms.admin;

import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.cms.admin")
@RequiredArgsConstructor
public class AdminViewAdvice {

    private final AdminSecurityService adminSecurityService;

    @ModelAttribute("currentAdminName")
    public String currentAdminName() {
        String name = adminSecurityService.getCurrentAdminName();
        return (name == null || name.isBlank()) ? "관리자" : name;
    }

    @ModelAttribute("currentAdminProfileImageUrl")
    public String currentAdminProfileImageUrl() {
        return adminSecurityService.getCurrentAdminProfileImageUrl();
    }
}
