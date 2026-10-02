package com.cms.admin.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 유일한 권한 판정 함수(PLAN-menu-permission-management.md §1). URL 게이트(SecurityConfig)와 메서드 어노테이션
 * ({@link RequirePermission})이 모두 이 클래스로 판정하므로 두 계층이 어긋나지 않는다. 빈 이름 {@code adminPermission}은 SpEL이 쓴다.
 *
 * <p>규칙: 인증 없음·익명 → false / {@code ROLE_ADMIN} → 항상 true(DB 미조회) / 상시 허용 기능 → ADMIN·MANAGER true /
 * 위임 불가 기능 → false / 위임 가능 기능 → {@code ROLE_MANAGER}이고 (기능, 동작) 허용 행이 있으며 쓰기 동작이면 READ도 있을 때만 true.
 * 역할은 세션 {@link Authentication}의 권한에서 읽는다(기존 URL 규칙과 같은 출처).
 */
@Slf4j
@Component("adminPermission")
public class AdminPermissionEvaluator {

    static final String ROLE_ADMIN = "ROLE_ADMIN";
    static final String ROLE_MANAGER = "ROLE_MANAGER";

    private final RolePermissionCache cache;

    public AdminPermissionEvaluator(RolePermissionCache cache) {
        this.cache = cache;
    }

    /** 요청 안에서 여러 번 판정할 때 한 번 받아 {@link #allows(PermissionSnapshot, Authentication, AdminFeature, PermissionAction)}에 넘긴다. */
    public PermissionSnapshot snapshot() {
        return cache.snapshot();
    }

    public boolean allows(Authentication authentication, AdminFeature feature, PermissionAction action) {
        // 스냅샷은 MANAGER의 위임 기능일 때만 지연 조회한다 — ADMIN·상시 허용 기능은 캐시 장애와 무관하다.
        return decide(cache::snapshot, authentication, feature, action);
    }

    /** 이미 받아 둔 스냅샷으로 판정한다(사이드바처럼 한 요청에서 여러 번 판정할 때 같은 권한 버전을 쓰기 위해). */
    public boolean allows(PermissionSnapshot snapshot, Authentication authentication, AdminFeature feature, PermissionAction action) {
        return decide(() -> snapshot, authentication, feature, action);
    }

    private boolean decide(Supplier<PermissionSnapshot> snapshot, Authentication authentication,
                           AdminFeature feature, PermissionAction action) {
        if (!isAuthenticated(authentication)) {
            return false;
        }
        if (hasAuthority(authentication, ROLE_ADMIN)) {
            return true;
        }
        return hasAuthority(authentication, ROLE_MANAGER) && managerAllows(snapshot, feature, action);
    }

    /** MANAGER 관점 판정. 스냅샷은 위임 가능 기능일 때만 지연 조회한다(상시 허용·위임 불가는 DB를 보지 않는다). */
    private boolean managerAllows(Supplier<PermissionSnapshot> snapshot, AdminFeature feature, PermissionAction action) {
        return switch (feature.getKind()) {
            case ALWAYS -> feature.supports(action);
            case DELEGABLE -> grantedBy(snapshot.get(), feature, action);
            case ADMIN_ONLY -> false;
        };
    }

    /**
     * 사이드바가 메뉴 URL의 노출 여부를 정하는 판정(PLAN §7). ADMIN은 URL이 null이든 미분류든 항상 true,
     * MANAGER는 URL이 어떤 기능의 {@code menuUrls}와 완전 일치할 때만 그 기능의 READ 허용 여부를 따르고 그 밖(미분류·null)은 false다.
     * 그 외 역할·익명은 모두 false. 스냅샷은 MANAGER일 때만 지연 조회하므로 ADMIN 화면은 권한 캐시 장애·지연과 무관하고, 한 요청의 판정은 같은 권한 버전을 본다.
     */
    public Predicate<String> menuUrlVisibility(Supplier<PermissionSnapshot> snapshot, Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            return url -> false;
        }
        if (hasAuthority(authentication, ROLE_ADMIN)) {
            return url -> true;
        }
        if (!hasAuthority(authentication, ROLE_MANAGER)) {
            return url -> false;
        }
        return managerMenuUrlVisibility(snapshot.get()); // MANAGER 경로에서만, 요청당 한 번 조회한다
    }

    /** "MANAGER 관점" 메뉴 URL 가시성 — 메뉴 관리 화면의 노출 안내가 실제 MANAGER 사이드바와 같은 규칙을 쓰도록 공유한다. */
    public Predicate<String> managerMenuUrlVisibility(PermissionSnapshot snapshot) {
        return url -> AdminFeature.forMenuUrl(url)
                .map(feature -> managerAllows(() -> snapshot, feature, PermissionAction.READ))
                .orElse(false);
    }

    /**
     * {@link RequirePermission}의 메타 {@code @PreAuthorize}가 부르는 SpEL 진입점. 이름을 enum으로 파싱하지 못하면
     * false다(fail-closed — 오타가 권한을 열 수 없다).
     */
    public boolean check(String feature, String action) {
        AdminFeature parsedFeature;
        PermissionAction parsedAction;
        try {
            parsedFeature = AdminFeature.valueOf(feature);
            parsedAction = PermissionAction.valueOf(action);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("알 수 없는 권한 이름 — 거부한다: feature={}, action={}", feature, action);
            return false;
        }
        return allows(SecurityContextHolder.getContext().getAuthentication(), parsedFeature, parsedAction);
    }

    private static boolean grantedBy(PermissionSnapshot snapshot, AdminFeature feature, PermissionAction action) {
        if (!feature.supports(action) || !snapshot.has(ROLE_MANAGER, feature, action)) {
            return false;
        }
        // 의존 규칙: 쓰기 동작(생성·수정·삭제)은 조회 권한이 함께 있어야 한다.
        return action == PermissionAction.READ || snapshot.has(ROLE_MANAGER, feature, PermissionAction.READ);
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static boolean hasAuthority(Authentication authentication, String role) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (role.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
