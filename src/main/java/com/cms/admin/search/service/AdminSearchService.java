package com.cms.admin.search.service;

import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.board.repository.PostSearchRow;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.admin.search.dto.AdminSearchResponse;
import com.cms.admin.search.dto.AdminSearchResponse.MemberItem;
import com.cms.admin.search.dto.AdminSearchResponse.MenuItem;
import com.cms.admin.search.dto.AdminSearchResponse.PostItem;
import com.cms.admin.search.dto.AdminSearchResponse.Section;
import com.cms.common.web.SafeUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 상단바 통합 검색. 결과는 <b>현재 사용자가 원래 볼 수 있는 것만</b> 담는다 — 검색이 권한 우회 경로가 되지 않도록
 * 섹션 노출을 이 서비스에서 판정기({@link AdminPermissionEvaluator})로 결정한다(계획서 PLAN-admin-unified-search.md §5-C).
 *
 * <ul>
 *   <li>메뉴: 사이드바와 같은 {@code getSidebarMenus(menuUrlVisibility)} 결과를 평탄화해 이름으로 거른다.</li>
 *   <li>게시글: ADMIN은 전체, MANAGER는 READ가 유효한 게시판만(공지도 공지 게시판의 게시글이라 여기에 나온다).</li>
 *   <li>관리자 계정: ADMIN만.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminSearchService {

    /** 서버 측 비용 제한 — 이보다 짧은 검색어는 쿼리 없이 빈 결과를 돌려준다(코드포인트 기준). */
    static final int MIN_KEYWORD_LENGTH = 2;
    static final int SECTION_LIMIT = 5;

    private final MenuService menuService;
    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final AdminPermissionEvaluator permissionEvaluator;

    @Transactional(readOnly = true)
    public AdminSearchResponse search(String rawKeyword, Authentication authentication) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.codePointCount(0, keyword.length()) < MIN_KEYWORD_LENGTH) {
            return AdminSearchResponse.builder().keyword(keyword).build();
        }

        boolean admin = isAdmin(authentication);
        // MANAGER일 때만, 요청당 한 번 권한 스냅샷을 읽어 메뉴 가시성과 게시글 판정에 같이 쓴다(ADMIN은 캐시를 호출하지 않는다).
        Supplier<PermissionSnapshot> snapshotOnce = memoize(permissionEvaluator::snapshot);

        Section<MenuItem> menus = searchMenus(keyword, snapshotOnce, authentication);

        return AdminSearchResponse.builder()
                .keyword(keyword)
                .menus(menus)
                .posts(searchPostsFor(keyword, admin, snapshotOnce, authentication))
                .members(admin ? searchMembers(keyword) : null)
                .build();
    }

    private Section<MenuItem> searchMenus(String keyword, Supplier<PermissionSnapshot> snapshotOnce, Authentication authentication) {
        List<SidebarMenuResponse> tree = menuService.getSidebarMenus(
                permissionEvaluator.menuUrlVisibility(snapshotOnce, authentication));
        String needle = keyword.toLowerCase(Locale.ROOT);
        List<MenuItem> matched = new ArrayList<>();
        collectMenus(tree, "", needle, matched);
        List<MenuItem> items = matched.size() > SECTION_LIMIT ? matched.subList(0, SECTION_LIMIT) : matched;
        return new Section<>(matched.size(), List.copyOf(items));
    }

    /** 깊이 우선으로 평탄화한다. 자식이 있는 그룹은 사이드바가 자기 URL을 그리지 않으므로 결과에서 빼고 자손만 본다. */
    private void collectMenus(List<SidebarMenuResponse> nodes, String parentPath, String needle, List<MenuItem> out) {
        if (nodes == null) {
            return;
        }
        for (SidebarMenuResponse node : nodes) {
            String path = parentPath.isEmpty() ? node.getMenuName() : parentPath + " > " + node.getMenuName();
            boolean leaf = node.getChildren() == null || node.getChildren().isEmpty();
            if (leaf && matchesMenu(node, needle)) {
                out.add(new MenuItem(node.getMenuName(), path, node.getMenuUrl(), node.getMenuIcon()));
            }
            collectMenus(node.getChildren(), path, needle, out);
        }
    }

    private boolean matchesMenu(SidebarMenuResponse node, String needle) {
        return node.getMenuName() != null
                && node.getMenuName().toLowerCase(Locale.ROOT).contains(needle)
                // 같은 출처 경로만 — 외부 http(s) 메뉴는 사이드바에만 보이고 검색 결과 이동에는 쓰지 않는다
                && SafeUrls.isSameOriginPath(node.getMenuUrl());
    }

    /**
     * 게시글 섹션(PLAN-board.md 쟁점 12): ADMIN은 삭제되지 않은 게시판의 게시글 전체, MANAGER는 <b>READ가 유효한 게시판</b>의 게시글만이고 그런 게시판이
     * 하나도 없으면 섹션 키를 생략한다(null). 검색이 게시판별 권한의 우회 경로가 되지 않도록 쿼리가 게시판 집합으로 한정된다.
     */
    private Section<PostItem> searchPostsFor(String keyword, boolean admin, Supplier<PermissionSnapshot> snapshotOnce,
                                             Authentication authentication) {
        Set<Long> boardIds = null;
        if (!admin) {
            boardIds = permissionEvaluator.readableBoardIds(snapshotOnce, authentication);
            if (boardIds.isEmpty()) {
                return null;
            }
        }
        Page<PostSearchRow> page = postRepository.searchForAdminSearch(boardIds, keyword, PageRequest.of(0, SECTION_LIMIT));
        List<PostItem> items = page.getContent().stream()
                .map(r -> new PostItem(r.id(), r.boardId(), r.boardName(), r.title(), r.useYn(), r.createDate()))
                .toList();
        return new Section<>(page.getTotalElements(), items);
    }

    private Section<MemberItem> searchMembers(String keyword) {
        Page<Member> page = memberRepository.searchByKeyword(keyword,
                PageRequest.of(0, SECTION_LIMIT, Sort.by(Sort.Direction.DESC, "id")));
        List<MemberItem> items = page.getContent().stream()
                .map(m -> new MemberItem(m.getId(), m.getUserId(), m.getUserName(),
                        m.getUserType().name(), m.getStatus().name()))
                .toList();
        return new Section<>(page.getTotalElements(), items);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> Role.ROLE_ADMIN.name().equals(a.getAuthority()));
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private T value;
            private boolean loaded;

            @Override
            public T get() {
                if (!loaded) {
                    value = delegate.get();
                    loaded = true;
                }
                return value;
            }
        };
    }
}
