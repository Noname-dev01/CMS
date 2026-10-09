package com.cms.config;

import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.admin.permission.PermissionSnapshot;
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
 * <p>캐시는 DB 없이 <b>{@link #MANAGER_ID} 회원에게 {@link #SEED_BOARD_ID} 게시판의 조회·생성·수정·삭제를 허용한 고정 스냅샷</b>을 돌려준다. 권한 판정 키가 회원 ID라
 * 게시판 권한(공지 포함 — 공지는 공지 게시판의 게시글이다)을 다루는 MANAGER 시험은 {@link WithManager}(이 ID를 가진 주체)를 쓴다. 다른 권한 조합이 필요한 시험은 {@link #snapshotWith}로 직접 만든다.
 */
@TestConfiguration
public class PermissionTestConfig {

    /** {@link WithManager} 주체의 회원 ID이자 {@link #seedSnapshot()}이 권한을 준 회원 ID. */
    public static final long MANAGER_ID = 9_001L;

    /** 고정 스냅샷이 권한을 주는 게시판 ID(슬라이스 시험용 임의 값 — DB 행이 아니다). */
    public static final long SEED_BOARD_ID = 1L;

    /** {@link #MANAGER_ID} 회원에게 {@link #SEED_BOARD_ID} 게시판의 4동작을 모두 허용한 스냅샷. */
    public static PermissionSnapshot seedSnapshot() {
        return snapshotWith(PermissionAction.values());
    }

    /** {@link #MANAGER_ID} 회원에게 {@link #SEED_BOARD_ID} 게시판의 지정한 동작만 허용한 스냅샷. */
    public static PermissionSnapshot snapshotWith(PermissionAction... actions) {
        return snapshotFor(MANAGER_ID, actions);
    }

    /** 지정한 회원에게 {@link #SEED_BOARD_ID} 게시판의 지정한 동작만 허용한 스냅샷. */
    public static PermissionSnapshot snapshotFor(long memberId, PermissionAction... actions) {
        Set<PermissionSnapshot.BoardGrant> grants = Arrays.stream(actions)
                .map(action -> new PermissionSnapshot.BoardGrant(memberId, SEED_BOARD_ID, action))
                .collect(Collectors.toSet());
        return new PermissionSnapshot(Set.of(), grants);
    }

    @Bean
    public PermissionCache permissionCache() {
        PermissionCache cache = Mockito.mock(PermissionCache.class);
        Mockito.when(cache.snapshot()).thenReturn(seedSnapshot());
        return cache;
    }

    @Bean("adminPermission")
    public AdminPermissionEvaluator adminPermission(PermissionCache cache) {
        return new AdminPermissionEvaluator(cache);
    }
}
