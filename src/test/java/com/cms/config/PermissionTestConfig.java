package com.cms.config;

import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.admin.permission.RolePermissionCache;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 슬라이스 테스트용 권한 판정기 빈. {@code SecurityConfig}와 {@code @RequirePermission}이 {@code adminPermission}을 쓰는데
 * {@code @WebMvcTest}는 {@code @Component}를 스캔하지 않으므로 직접 등록한다.
 *
 * <p>캐시는 DB 없이 <b>V14 시드와 같은 고정 스냅샷</b>(MANAGER × 공지 × 조회·생성·수정·삭제)을 돌려준다 — 이 PR이 동작을 바꾸지 않는다는
 * 증거로 기존 보안 테스트의 기대값이 하나도 바뀌지 않아야 한다. 다른 권한 조합이 필요한 테스트는 {@link #snapshotWith}로 직접 만든다.
 */
@TestConfiguration
public class PermissionTestConfig {

    /** V14 시드와 같은 허용 행: ROLE_MANAGER × NOTICE × 4동작. */
    public static PermissionSnapshot seedSnapshot() {
        return snapshotWith(PermissionAction.values());
    }

    /** ROLE_MANAGER에게 NOTICE의 지정한 동작만 허용한 스냅샷. */
    public static PermissionSnapshot snapshotWith(PermissionAction... actions) {
        Set<PermissionSnapshot.Grant> grants = Arrays.stream(actions)
                .map(action -> new PermissionSnapshot.Grant("ROLE_MANAGER", AdminFeature.NOTICE, action))
                .collect(Collectors.toSet());
        return new PermissionSnapshot(grants);
    }

    @Bean
    public RolePermissionCache rolePermissionCache() {
        RolePermissionCache cache = Mockito.mock(RolePermissionCache.class);
        Mockito.when(cache.snapshot()).thenReturn(seedSnapshot());
        return cache;
    }

    @Bean("adminPermission")
    public AdminPermissionEvaluator adminPermission(RolePermissionCache cache) {
        return new AdminPermissionEvaluator(cache);
    }
}
