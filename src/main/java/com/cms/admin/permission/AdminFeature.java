package com.cms.admin.permission;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.cms.admin.permission.FeatureKind.ADMIN_ONLY;
import static com.cms.admin.permission.FeatureKind.ALWAYS;
import static com.cms.admin.permission.FeatureKind.BOARD_SCOPED;
import static com.cms.admin.permission.FeatureKind.DELEGABLE;
import static com.cms.admin.permission.PermissionAction.CREATE;
import static com.cms.admin.permission.PermissionAction.DELETE;
import static com.cms.admin.permission.PermissionAction.READ;
import static com.cms.admin.permission.PermissionAction.UPDATE;

/**
 * 관리자 기능 카탈로그(PLAN-menu-permission-management.md §2). 어떤 기능을 누구에게 열 수 있는지의 <b>최종 권위는 이 코드</b>다 —
 * DB 허용 행에 위임 불가 기능이 수동으로 들어가도 판정기는 무시한다.
 *
 * <p>{@code gatePatterns}는 기능 단위 READ 게이트를 거는 URL 패턴이다(ADMIN_ONLY는 비워 두어 {@code /admin/**} 캐치올이 ADMIN만 허용).
 * {@code menuUrls}는 사이드바가 이 기능의 READ 권한으로 노출을 판정하는 메뉴 URL(문자열 완전 일치)이다.
 */
public enum AdminFeature {

    DASHBOARD(ALWAYS, "대시보드", EnumSet.of(READ),
            List.of("/admin"), List.of("/admin")),

    /**
     * 본인 계정 관련 상시 허용 기능. {@code /admin/member/messages}(쪽지함 페이지, 2026-10-05 사용자 승인 — PLAN-admin-message.md D3)는
     * <b>정확 경로 1개</b>만 추가했다. ALWAYS 게이트는 HTTP 메서드를 구분하지 않으므로 이 경로에는 GET/HEAD 핸들러만 둘 수 있고
     * {@code MessagePageMethodConventionTest}가 이를 CI에서 잠근다. 쪽지 API는 기존 {@code /admin/api/members/me/**} 안이다.
     */
    MY_INFO(ALWAYS, "내 정보", EnumSet.of(READ, UPDATE),
            List.of("/admin/member/info"),
            List.of("/admin/member/info", "/admin/member/settings", "/admin/member/messages",
                    "/admin/api/members/me", "/admin/api/members/me/**")),

    /** 상단바 통합 검색 — 검색창 사용 자체는 상시 허용이고, 결과의 도메인별 노출은 서비스가 판정기로 필터한다. */
    SEARCH(ALWAYS, "통합 검색", EnumSet.of(READ),
            List.of(), List.of("/admin/api/search-results")),

    /**
     * 게시글 관리(게시판별 위임, PLAN-board.md 쟁점 2). 게이트는 기능 단위 READ("어느 게시판이든 조회 권한") — 게시판별 판정은
     * 핸들러의 {@link RequireBoardPermission}이 한다. 게시판 정의({@code /admin/board/manage}·{@code /admin/api/boards}·
     * {@code /admin/api/boards/{id}})는 이 게이트 밖이라 ADMIN 캐치올이 막는다.
     *
     * <p>{@code /admin/notice/manage}는 공지가 공지 게시판으로 흡수되며(PLAN-notice-to-board.md 쟁점 9, 2026-10-09 승인) 옛 북마크·검색 링크를
     * 게시글 관리로 보내는 리다이렉트 전용 정확 경로 1개다. 게이트는 기능 단위 READ라 어느 게시판에든 조회 권한이 있는 MANAGER만 통과하고,
     * 옛 {@code /admin/notice/**}·{@code /admin/api/notices/**}의 나머지는 ADMIN 캐치올로 떨어진다. 리다이렉트는 GET만 둔다.
     */
    BOARD(BOARD_SCOPED, "게시판", EnumSet.of(READ, CREATE, UPDATE, DELETE),
            List.of("/admin/board/posts"),
            List.of("/admin/board/posts", "/admin/notice/manage", "/admin/api/boards/*/posts", "/admin/api/boards/*/posts/**",
                    "/admin/api/boards/*/content-images")),

    MEMBER(ADMIN_ONLY, "회원 관리", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/member/manage", "/admin/member/new"), List.of()),

    MENU(ADMIN_ONLY, "메뉴 관리", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/menu/manage"), List.of()),

    ACTION_LOG(ADMIN_ONLY, "활동 로그", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/log/manage"), List.of()),

    /** 게시판 정의 관리 — 위임 불가. 권한관리 매트릭스에 "위임 불가"로 보이게 하려고 카탈로그에 둔다(판정은 캐치올과 같다). */
    BOARD_ADMIN(ADMIN_ONLY, "게시판 관리", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/board/manage"), List.of()),

    /** 권한관리 자체는 영구 ADMIN 전용 — 생성자 가드가 DELEGABLE 변경을 클래스 로딩 시점에 막는다(자기 승격 차단). */
    PERMISSION(ADMIN_ONLY, "권한관리", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/permission/manage"), List.of());

    private final FeatureKind kind;
    private final String label;
    private final Set<PermissionAction> actions;
    private final List<String> menuUrls;
    private final List<String> gatePatterns;

    AdminFeature(FeatureKind kind, String label, Set<PermissionAction> actions,
                 List<String> menuUrls, List<String> gatePatterns) {
        if (name().equals("PERMISSION") && kind != ADMIN_ONLY) {
            throw new IllegalStateException("권한관리는 위임 가능 기능으로 만들 수 없다(자기 승격 차단).");
        }
        if ((kind == DELEGABLE || kind == BOARD_SCOPED) && !actions.contains(READ)) {
            throw new IllegalStateException("위임 가능 기능은 READ 동작을 지원해야 한다: " + name());
        }
        this.kind = kind;
        this.label = label;
        this.actions = Set.copyOf(actions);
        this.menuUrls = List.copyOf(menuUrls);
        this.gatePatterns = List.copyOf(gatePatterns);
    }

    public FeatureKind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    /** 이 기능이 지원하는 동작(ADMIN_ONLY는 위임 대상이 아니라 비어 있다). */
    public Set<PermissionAction> getActions() {
        return actions;
    }

    public boolean supports(PermissionAction action) {
        return actions.contains(action);
    }

    public List<String> getMenuUrls() {
        return menuUrls;
    }

    public List<String> getGatePatterns() {
        return gatePatterns;
    }

    /** 메뉴 URL이 어떤 기능의 {@code menuUrls}와 <b>문자열 완전 일치</b>하면 그 기능을, 아니면(미분류·쿼리스트링·null) 빈 값을 돌려준다. */
    public static Optional<AdminFeature> forMenuUrl(String menuUrl) {
        if (menuUrl == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(f -> f.menuUrls.contains(menuUrl)).findFirst();
    }

    public static List<AdminFeature> ofKind(FeatureKind kind) {
        return Arrays.stream(values()).filter(f -> f.kind == kind).toList();
    }
}
