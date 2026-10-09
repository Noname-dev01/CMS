package com.cms.admin.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 카탈로그 불변식(PLAN-menu-permission-management.md §2) — 기능 정의가 어긋나면 CI에서 바로 실패한다. */
class AdminFeatureTest {

    @Test
    @DisplayName("위임 가능 기능은 모두 READ 동작을 지원한다")
    void delegableFeaturesSupportRead() {
        for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.DELEGABLE)) {
            assertThat(feature.supports(PermissionAction.READ)).as(feature.name()).isTrue();
        }
    }

    @Test
    @DisplayName("권한관리 기능은 영구 ADMIN 전용이다(자기 승격 차단) — DELEGABLE로 바꿀 수 없다")
    void permissionFeatureIsAdminOnly() {
        assertThat(AdminFeature.PERMISSION.getKind()).isEqualTo(FeatureKind.ADMIN_ONLY);
    }

    @Test
    @DisplayName("위임 불가 기능(ADMIN_ONLY)은 지원 동작이 없고 게이트 패턴도 없다(캐치올이 ADMIN만 허용)")
    void adminOnlyFeaturesHaveNoActionsOrGates() {
        for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.ADMIN_ONLY)) {
            assertThat(feature.getActions()).as(feature.name()).isEmpty();
            assertThat(feature.getGatePatterns()).as(feature.name()).isEmpty();
        }
    }

    @Test
    @DisplayName("서로 다른 기능의 게이트 패턴은 겹치지 않는다")
    void gatePatternsDoNotOverlap() {
        Set<String> seen = new HashSet<>();
        for (AdminFeature feature : AdminFeature.values()) {
            for (String pattern : feature.getGatePatterns()) {
                assertThat(seen.add(pattern)).as("중복 게이트 패턴: " + pattern).isTrue();
            }
        }
    }

    @Test
    @DisplayName("사이드바 매칭 URL(menuUrls)에 중복이 없다")
    void menuUrlsAreUnique() {
        List<String> all = new ArrayList<>();
        for (AdminFeature feature : AdminFeature.values()) {
            all.addAll(feature.getMenuUrls());
        }
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("모든 게이트 패턴·메뉴 URL은 /admin 영역 안이다(공개 경로를 위임 대상으로 만들 수 없다)")
    void patternsAreInsideAdminArea() {
        for (AdminFeature feature : AdminFeature.values()) {
            for (String path : concat(feature.getGatePatterns(), feature.getMenuUrls())) {
                assertThat(path.equals("/admin") || path.startsWith("/admin/"))
                        .as(feature.name() + " " + path).isTrue();
            }
        }
    }

    @Test
    @DisplayName("내 정보 기능의 쪽지함 페이지 게이트는 정확 경로 1개다 — 와일드카드 하위 경로를 열지 않고 사이드바 메뉴 URL은 늘리지 않는다(2026-10-05 승인)")
    void myInfoFeatureGatesMessagesPageAsExactPathOnly() {
        assertThat(AdminFeature.MY_INFO.getKind()).isEqualTo(FeatureKind.ALWAYS);
        assertThat(AdminFeature.MY_INFO.getGatePatterns()).contains("/admin/member/messages");
        assertThat(AdminFeature.MY_INFO.getGatePatterns())
                .as("쪽지함 하위 경로를 여는 패턴이 있으면 안 된다")
                .noneMatch(pattern -> pattern.startsWith("/admin/member/messages") && !pattern.equals("/admin/member/messages"));
        assertThat(AdminFeature.MY_INFO.getGatePatterns()).noneMatch(pattern -> pattern.equals("/admin/member/**") || pattern.equals("/admin/member/*"));
        assertThat(AdminFeature.MY_INFO.getMenuUrls()).containsExactly("/admin/member/info");
    }

    @Test
    @DisplayName("통합 검색은 상시 허용 기능이고 게이트는 검색 API 경로 하나뿐이며 사이드바 메뉴가 아니다")
    void searchFeatureIsAlwaysWithSingleGate() {
        assertThat(AdminFeature.SEARCH.getKind()).isEqualTo(FeatureKind.ALWAYS);
        assertThat(AdminFeature.SEARCH.getGatePatterns()).containsExactly("/admin/api/search-results");
        assertThat(AdminFeature.SEARCH.getMenuUrls()).isEmpty();
    }

    @Test
    @DisplayName("게시판(게시글)은 게시판 단위 위임 기능이고 게이트는 게시글·본문 이미지 경로뿐 — 게시판 정의 경로는 게이트 밖(ADMIN 캐치올)이다")
    void boardFeatureGatesOnlyPostPaths() {
        assertThat(AdminFeature.BOARD.getKind()).isEqualTo(FeatureKind.BOARD_SCOPED);
        assertThat(AdminFeature.BOARD.supports(PermissionAction.READ)).isTrue();
        assertThat(AdminFeature.BOARD.getMenuUrls()).containsExactly("/admin/board/posts");
        assertThat(AdminFeature.BOARD.getGatePatterns()).containsExactly("/admin/board/posts", "/admin/notice/manage",
                "/admin/api/boards/*/posts", "/admin/api/boards/*/posts/**", "/admin/api/boards/*/content-images");
        AntPathMatcher matcher = new AntPathMatcher();
        for (String definitionPath : List.of("/admin/board/manage", "/admin/api/boards", "/admin/api/boards/3", "/admin/notice/manage/extra", "/admin/api/notices")) {
            assertThat(AdminFeature.BOARD.getGatePatterns()).as(definitionPath)
                    .noneMatch(pattern -> matcher.match(pattern, definitionPath));
        }
        assertThat(AdminFeature.BOARD_ADMIN.getKind()).isEqualTo(FeatureKind.ADMIN_ONLY);
        assertThat(AdminFeature.BOARD_ADMIN.getMenuUrls()).containsExactly("/admin/board/manage");
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
