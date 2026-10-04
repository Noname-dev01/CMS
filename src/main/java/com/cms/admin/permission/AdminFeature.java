package com.cms.admin.permission;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.cms.admin.permission.FeatureKind.ADMIN_ONLY;
import static com.cms.admin.permission.FeatureKind.ALWAYS;
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

    MY_INFO(ALWAYS, "내 정보", EnumSet.of(READ, UPDATE),
            List.of("/admin/member/info"),
            List.of("/admin/member/info", "/admin/member/settings", "/admin/api/members/me", "/admin/api/members/me/**")),

    /** 상단바 통합 검색 — 검색창 사용 자체는 상시 허용이고, 결과의 도메인별 노출은 서비스가 판정기로 필터한다. */
    SEARCH(ALWAYS, "통합 검색", EnumSet.of(READ),
            List.of(), List.of("/admin/api/search-results")),

    NOTICE(DELEGABLE, "공지사항", EnumSet.of(READ, CREATE, UPDATE, DELETE),
            List.of("/admin/notice/manage"),
            List.of("/admin/notice/**", "/admin/api/notices", "/admin/api/notices/**")),

    MEMBER(ADMIN_ONLY, "회원 관리", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/member/manage", "/admin/member/new"), List.of()),

    MENU(ADMIN_ONLY, "메뉴 관리", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/menu/manage"), List.of()),

    ACTION_LOG(ADMIN_ONLY, "활동 로그", EnumSet.noneOf(PermissionAction.class),
            List.of("/admin/log/manage"), List.of()),

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
        if (kind == DELEGABLE && !actions.contains(READ)) {
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
