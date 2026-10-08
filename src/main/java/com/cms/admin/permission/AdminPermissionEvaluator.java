package com.cms.admin.permission;

import com.cms.config.auth.CustomUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 유일한 권한 판정 함수(PLAN-menu-permission-management.md §1, PLAN-member-permission.md §5-B). URL 게이트(SecurityConfig)와 메서드 어노테이션
 * ({@link RequirePermission})이 모두 이 클래스로 판정하므로 두 계층이 어긋나지 않는다. 빈 이름 {@code adminPermission}은 SpEL이 쓴다.
 *
 * <p>규칙: 인증 없음·익명 → false / {@code ROLE_ADMIN} → 항상 true(DB 미조회) / 상시 허용 기능 → ADMIN·MANAGER true /
 * 위임 불가 기능 → false / 위임 가능 기능 → {@code ROLE_MANAGER}이고 <b>그 회원 본인의</b> (기능, 동작) 허용 행이 있으며 쓰기 동작이면 READ도 있을 때만 true.
 * 역할은 세션 {@link Authentication}의 권한에서, 회원 ID는 principal({@link CustomUserDetails})에서 읽는다. principal이 {@code CustomUserDetails}가
 * 아니면(회원을 식별할 수 없으면) 위임 기능은 거부한다(fail-closed).
 */
@Slf4j
@Component("adminPermission")
public class AdminPermissionEvaluator {

    static final String ROLE_ADMIN = "ROLE_ADMIN";
    static final String ROLE_MANAGER = "ROLE_MANAGER";

    private final PermissionCache cache;

    public AdminPermissionEvaluator(PermissionCache cache) {
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

    /**
     * 현재 사용자가 가진 위임 가능 기능의 동작 키({@code "NOTICE:CREATE"}) — 화면 버튼 표시용이며 서버 판정을 대신하지 않는다.
     * {@link #decide}를 그대로 쓰므로 ADMIN이면 스냅샷 공급자를 호출하지 않고(DB 비의존), 익명·ROLE_USER는 빈 집합이다.
     */
    public Set<String> grantedActionKeys(Supplier<PermissionSnapshot> snapshot, Authentication authentication) {
        Set<String> keys = new LinkedHashSet<>();
        for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.DELEGABLE)) {
            for (PermissionAction action : feature.getActions()) {
                if (decide(snapshot, authentication, feature, action)) {
                    keys.add(feature.name() + ":" + action.name());
                }
            }
        }
        return keys;
    }

    private boolean decide(Supplier<PermissionSnapshot> snapshot, Authentication authentication,
                           AdminFeature feature, PermissionAction action) {
        if (!isAuthenticated(authentication)) {
            return false;
        }
        if (hasAuthority(authentication, ROLE_ADMIN)) {
            return true;
        }
        return hasAuthority(authentication, ROLE_MANAGER)
                && managerAllows(snapshot, memberIdOf(authentication), feature, action);
    }

    /** MANAGER 관점 판정. 스냅샷은 위임 가능 기능이고 회원을 식별할 수 있을 때만 지연 조회한다(상시 허용·위임 불가는 DB를 보지 않는다). */
    private boolean managerAllows(Supplier<PermissionSnapshot> snapshot, Long memberId, AdminFeature feature, PermissionAction action) {
        return switch (feature.getKind()) {
            case ALWAYS -> feature.supports(action);
            case DELEGABLE -> memberId != null && grantedBy(snapshot.get(), memberId, feature, action);
            // 기능 단위("어느 게시판이든") — URL 게이트·사이드바용. 게시판별 판정은 allowsBoard가 한다(PLAN-board.md 쟁점 2).
            case BOARD_SCOPED -> memberId != null && feature.supports(action)
                    && !snapshot.get().boardIds(memberId, action).isEmpty();
            case ADMIN_ONLY -> false;
        };
    }

    /**
     * 게시판 단위 판정(PLAN-board.md 쟁점 2·3) — 게시글·첨부·본문 이미지 핸들러가 {@link RequireBoardPermission}으로 부른다.
     * ADMIN은 항상 true(DB 미조회), MANAGER는 그 회원의 (게시판, 동작) 행이 있고 쓰기 동작이면 같은 게시판의 READ도 있어야 true.
     * 게시판 존재·삭제 여부는 보지 않는다 — 대상 확인(404)은 서비스 몫이다(권한과 대상의 분리, 공지와 같음).
     */
    public boolean allowsBoard(Authentication authentication, Long boardId, PermissionAction action) {
        return decideBoard(cache::snapshot, authentication, boardId, action);
    }

    /** 이미 받아 둔 스냅샷으로 게시판 단위 판정을 한다(한 요청에서 여러 게시판을 판정할 때). */
    public boolean allowsBoard(PermissionSnapshot snapshot, Authentication authentication, Long boardId, PermissionAction action) {
        return decideBoard(() -> snapshot, authentication, boardId, action);
    }

    private boolean decideBoard(Supplier<PermissionSnapshot> snapshot, Authentication authentication,
                                Long boardId, PermissionAction action) {
        if (!isAuthenticated(authentication) || boardId == null || action == null
                || !AdminFeature.BOARD.supports(action)) {
            return false;
        }
        if (hasAuthority(authentication, ROLE_ADMIN)) {
            return true;
        }
        Long memberId = memberIdOf(authentication);
        if (!hasAuthority(authentication, ROLE_MANAGER) || memberId == null) {
            return false;
        }
        PermissionSnapshot once = snapshot.get();
        return once.hasBoard(memberId, boardId, action)
                && (action == PermissionAction.READ || once.hasBoard(memberId, boardId, PermissionAction.READ));
    }

    /**
     * {@link RequireBoardPermission}의 메타 {@code @PreAuthorize}가 부르는 SpEL 진입점. 게시판 ID가 null(경로 변수 이름 불일치로
     * 파라미터를 못 읽은 경우 포함)이거나 동작 이름을 파싱하지 못하면 false(fail-closed).
     */
    public boolean checkBoard(Long boardId, String action) {
        PermissionAction parsedAction;
        try {
            parsedAction = PermissionAction.valueOf(action);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("알 수 없는 게시판 권한 동작 — 거부한다: boardId={}, action={}", boardId, action);
            return false;
        }
        return allowsBoard(SecurityContextHolder.getContext().getAuthentication(), boardId, parsedAction);
    }

    /**
     * 현재 사용자가 게시판에서 가진 유효 동작(화면 버튼·게시판 선택용 — 서버 판정을 대신하지 않는다). ADMIN은 게시판 기능의 전 동작,
     * MANAGER는 의존 규칙을 적용한 허용 동작, 그 밖은 빈 집합. 스냅샷은 MANAGER일 때만 조회한다.
     */
    public Set<PermissionAction> boardActions(Supplier<PermissionSnapshot> snapshot, Authentication authentication, Long boardId) {
        Set<PermissionAction> actions = new LinkedHashSet<>();
        for (PermissionAction action : PermissionAction.values()) {
            if (AdminFeature.BOARD.supports(action) && decideBoard(snapshot, authentication, boardId, action)) {
                actions.add(action);
            }
        }
        return actions;
    }

    /**
     * 사이드바가 메뉴 URL의 노출 여부를 정하는 판정(PLAN §7). ADMIN은 URL이 null이든 미분류든 항상 true,
     * MANAGER는 URL이 어떤 기능의 {@code menuUrls}와 완전 일치할 때만 그 기능의 READ 허용 여부를 <b>그 회원 본인의 허용 행으로</b> 따르고
     * 그 밖(미분류·null)은 false다. 그 외 역할·익명은 모두 false. 스냅샷은 회원을 식별할 수 있는 MANAGER일 때만 조회하므로 ADMIN 화면은
     * 권한 캐시 장애·지연과 무관하고, 한 요청의 판정은 같은 권한 버전을 본다.
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
        Long memberId = memberIdOf(authentication);
        PermissionSnapshot once = memberId == null ? PermissionSnapshot.EMPTY : snapshot.get(); // MANAGER 경로에서만, 요청당 한 번 조회한다
        return url -> AdminFeature.forMenuUrl(url)
                .map(feature -> managerAllows(() -> once, memberId, feature, PermissionAction.READ))
                .orElse(false);
    }

    /**
     * 메뉴 관리 화면의 노출 안내용 판정 — "권한을 받으면 MANAGER가 볼 수 있는 메뉴인가"(카탈로그 분류만 본다, 스냅샷 불필요).
     * 사용자별 권한 체계에는 "MANAGER 한 명의 시점"이 없으므로 위임 불가 기능과 카탈로그 밖 URL만 false다.
     */
    public Predicate<String> anyManagerMenuUrlVisibility() {
        return url -> AdminFeature.forMenuUrl(url)
                .map(feature -> feature.getKind() != FeatureKind.ADMIN_ONLY)
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

    private static boolean grantedBy(PermissionSnapshot snapshot, Long memberId, AdminFeature feature, PermissionAction action) {
        if (!feature.supports(action) || !snapshot.has(memberId, feature, action)) {
            return false;
        }
        // 의존 규칙: 쓰기 동작(생성·수정·삭제)은 조회 권한이 함께 있어야 한다.
        return action == PermissionAction.READ || snapshot.has(memberId, feature, PermissionAction.READ);
    }

    /** 세션 principal에서 회원 ID를 읽는다. {@link CustomUserDetails}가 아니면 null(식별 불가 — 위임 기능은 거부한다). */
    private static Long memberIdOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof CustomUserDetails details ? details.getId() : null;
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
