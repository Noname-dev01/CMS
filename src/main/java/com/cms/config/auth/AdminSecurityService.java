package com.cms.config.auth;

import com.cms.admin.member.domain.Role;
import org.hibernate.usertype.UserType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AdminSecurityService {

    public boolean hasAdminAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return false;
        }

        return userDetails.getMember().getUserType() == Role.ROLE_ADMIN;
    }

    public Long getCurrentAdminId(){
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getId();
    }

    public String getCurrentAdminUserId(){
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getUserId();
    }

    public String getCurrentAdminName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getUserName();
    }

    public String getCurrentAdminProfileImageUrl() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getProfileImageUrl();
    }
}
