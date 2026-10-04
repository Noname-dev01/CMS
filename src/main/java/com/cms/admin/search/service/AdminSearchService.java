package com.cms.admin.search.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.admin.search.dto.AdminSearchResponse;
import com.cms.admin.search.dto.AdminSearchResponse.MemberItem;
import com.cms.admin.search.dto.AdminSearchResponse.MenuItem;
import com.cms.admin.search.dto.AdminSearchResponse.NoticeItem;
import com.cms.admin.search.dto.AdminSearchResponse.Section;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 상단바 통합 검색. 결과는 <b>현재 사용자가 원래 볼 수 있는 것만</b> 담는다 — 검색이 권한 우회 경로가 되지 않도록
 * 섹션 노출을 이 서비스에서 판정기({@link AdminPermissionEvaluator})로 결정한다(계획서 PLAN-admin-unified-search.md §5-C).
 *
 * <ul>
 *   <li>메뉴: 사이드바와 같은 {@code getSidebarMenus(menuUrlVisibility)} 결과를 평탄화해 이름으로 거른다.</li>
 *   <li>공지: ADMIN 또는 {@code NOTICE:READ} 허용 MANAGER만(권한이 없으면 섹션 키 생략).</li>
 *   <li>관리자 계정: ADMIN만.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminSearchService {

    /** 서버 측 비용 제한 — 이보다 짧은 검색어는 쿼리 없이 빈 결과를 돌려준다(코드포인트 기준). */
    static final int MIN_KEYWORD_LENGTH = 2;
    static final int SECTION_LIMIT = 5;

    /**
     * 메뉴 이동 대상으로 허용하는 같은 출처 경로 — {@code /}로 시작하고 {@code //}·{@code \}·공백·제어문자가 없다.
     * 메뉴 URL은 저장 시 길이만 검사하므로 {@code javascript:}·{@code //host} 등이 결과 이동에 쓰이지 않게 걸러낸다.
     */
    private static final Pattern SAFE_PATH = Pattern.compile("^/(?![/\\\\])[^\\s\\\\\\x00-\\x1f\\x7f]*$");

    private final MenuService menuService;
    private final NoticeRepository noticeRepository;
    private final MemberRepository memberRepository;
    private final AdminPermissionEvaluator permissionEvaluator;

    @Transactional(readOnly = true)
    public AdminSearchResponse search(String rawKeyword, Authentication authentication) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.codePointCount(0, keyword.length()) < MIN_KEYWORD_LENGTH) {
            return AdminSearchResponse.builder().keyword(keyword).build();
        }

        boolean admin = isAdmin(authentication);
        // MANAGER일 때만, 요청당 한 번 권한 스냅샷을 읽어 메뉴 가시성과 공지 판정에 같이 쓴다(ADMIN은 캐시를 호출하지 않는다).
        Supplier<PermissionSnapshot> snapshotOnce = memoize(permissionEvaluator::snapshot);

        Section<MenuItem> menus = searchMenus(keyword, snapshotOnce, authentication);
        boolean noticeReadable = admin
                || permissionEvaluator.allows(snapshotOnce.get(), authentication, AdminFeature.NOTICE, PermissionAction.READ);

        return AdminSearchResponse.builder()
                .keyword(keyword)
                .menus(menus)
                .notices(noticeReadable ? searchNotices(keyword) : null)
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
                && node.getMenuUrl() != null
                && SAFE_PATH.matcher(node.getMenuUrl()).matches();
    }

    private Section<NoticeItem> searchNotices(String keyword) {
        NoticeSearchRequest request = NoticeSearchRequest.builder().keyword(keyword).build();
        Page<Notice> page = noticeRepository.searchNotices(request,
                PageRequest.of(0, SECTION_LIMIT, Sort.by(Sort.Direction.DESC, "createDate")));
        List<NoticeItem> items = page.getContent().stream()
                .map(n -> new NoticeItem(n.getId(), n.getTitle(), n.getUseYn(), n.getCreateDate()))
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
