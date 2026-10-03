package com.cms.config;

import com.cms.admin.member.domain.Role;
import com.cms.support.TestMembers;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import com.cms.config.auth.CustomUserDetails;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 슬라이스 시험용 MANAGER 주체 — {@link PermissionTestConfig#MANAGER_ID}를 회원 ID로 가진 {@link CustomUserDetails}다.
 * 권한 판정 키가 회원 ID라 위임 기능(공지 등)을 다루는 MANAGER 시험은 {@code @WithMockUser(roles = "MANAGER")}가 아니라 이 어노테이션을 쓴다
 * ({@code @WithMockUser}는 회원 ID가 없어 위임 기능이 전부 거부된다). 상시 허용·ADMIN 전용 경로 시험은 {@code @WithMockUser}를 그대로 써도 된다.
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithManager.Factory.class)
public @interface WithManager {

    class Factory implements WithSecurityContextFactory<WithManager> {
        @Override
        public SecurityContext createSecurityContext(WithManager annotation) {
            CustomUserDetails details = TestMembers.detached(PermissionTestConfig.MANAGER_ID, Role.ROLE_MANAGER);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
            return context;
        }
    }
}
