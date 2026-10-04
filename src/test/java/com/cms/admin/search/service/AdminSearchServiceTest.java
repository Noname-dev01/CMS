package com.cms.admin.search.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.admin.search.dto.AdminSearchResponse;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 통합 검색의 권한 필터(PLAN-admin-unified-search.md §5-C, §7 ④). 판정기는 실제 객체, 캐시·리포지토리·메뉴 서비스는 목이다.
 * 공지 노출을 다루는 MANAGER 주체는 회원 ID를 가진 {@code CustomUserDetails}여야 한다(@WithMockUser는 위임 기능이 전부 거부된다).
 */
class AdminSearchServiceTest {

    private static final long MANAGER_ID = 7L;

    private MenuService menuService;
    private NoticeRepository noticeRepository;
    private MemberRepository memberRepository;
    private PermissionCache cache;
    private AdminSearchService service;

    @BeforeEach
    void setUp() {
        menuService = mock(MenuService.class);
        noticeRepository = mock(NoticeRepository.class);
        memberRepository = mock(MemberRepository.class);
        cache = mock(PermissionCache.class);
        service = new AdminSearchService(menuService, noticeRepository, memberRepository, new AdminPermissionEvaluator(cache));

        when(menuService.getSidebarMenus(any())).thenReturn(List.of());
        when(noticeRepository.searchNotices(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(memberRepository.searchByKeyword(anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    private static Authentication auth(Role role) {
        com.cms.config.auth.CustomUserDetails details = TestMembers.detached(MANAGER_ID, role);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private void managerHasNoticeRead(boolean has) {
        Set<PermissionSnapshot.Grant> grants = has
                ? Set.of(new PermissionSnapshot.Grant(MANAGER_ID, AdminFeature.NOTICE, PermissionAction.READ))
                : Set.of();
        when(cache.snapshot()).thenReturn(new PermissionSnapshot(grants));
    }

    private static SidebarMenuResponse menu(long no, String name, String url, SidebarMenuResponse... children) {
        return SidebarMenuResponse.builder().menuNo(no).menuName(name).menuUrl(url).menuIcon("fas fa-fw fa-circle")
                .children(List.of(children)).build();
    }

    private static Notice notice(long id, String title) {
        return Notice.builder().id(id).title(title).useYn(true).deleted(false).createDate(LocalDateTime.of(2026, 10, 1, 10, 0)).build();
    }

    private static Member member(long id, String userId, String userName) {
        return Member.builder().id(id).userId(userId).userName(userName).userType(Role.ROLE_MANAGER).status(MemberStatus.ACTIVE).build();
    }

    @Test
    @DisplayName("ADMIN은 메뉴·공지·관리자 세 섹션을 모두 받고 권한 캐시를 조회하지 않는다")
    void admin_getsAllSections_withoutCache() {
        when(noticeRepository.searchNotices(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notice(31, "공지 제목")), Pageable.ofSize(5), 12));
        when(memberRepository.searchByKeyword(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(member(9, "mgr01", "김관리")), Pageable.ofSize(5), 1));

        AdminSearchResponse result = service.search("공지", auth(Role.ROLE_ADMIN));

        assertThat(result.getMenus()).isNotNull();
        assertThat(result.getNotices().getTotal()).isEqualTo(12);
        assertThat(result.getNotices().getItems()).extracting(AdminSearchResponse.NoticeItem::getId).containsExactly(31L);
        assertThat(result.getMembers().getItems()).extracting(AdminSearchResponse.MemberItem::getUserId).containsExactly("mgr01");
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("공지 읽기 권한이 있는 MANAGER는 메뉴·공지를 받고 관리자 섹션은 받지 않는다(조회도 하지 않는다)")
    void manager_withNoticeRead_getsMenusAndNotices_neverMembers() {
        managerHasNoticeRead(true);

        AdminSearchResponse result = service.search("공지", auth(Role.ROLE_MANAGER));

        assertThat(result.getMenus()).isNotNull();
        assertThat(result.getNotices()).isNotNull();
        assertThat(result.getMembers()).isNull();
        verify(memberRepository, never()).searchByKeyword(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("공지 읽기 권한이 없는 MANAGER는 공지 섹션이 응답에서 빠지고 공지 조회도 하지 않는다")
    void manager_withoutNoticeRead_noticeSectionOmitted() {
        managerHasNoticeRead(false);

        AdminSearchResponse result = service.search("공지", auth(Role.ROLE_MANAGER));

        assertThat(result.getMenus()).isNotNull();
        assertThat(result.getNotices()).isNull();
        assertThat(result.getMembers()).isNull();
        verify(noticeRepository, never()).searchNotices(any(), any(Pageable.class));
        verify(memberRepository, never()).searchByKeyword(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("MANAGER의 권한 스냅샷은 메뉴 가시성과 공지 판정에 한 번만 조회된다")
    void manager_snapshotLoadedOnce() {
        managerHasNoticeRead(true);

        service.search("공지", auth(Role.ROLE_MANAGER));

        verify(cache, times(1)).snapshot();
    }

    @Test
    @DisplayName("MANAGER가 메뉴 서비스에 넘기는 가시성 판정은 그 회원 본인의 권한을 따른다")
    @SuppressWarnings("unchecked")
    void manager_menuVisibilityFollowsOwnGrants() {
        managerHasNoticeRead(false);
        var captor = org.mockito.ArgumentCaptor.forClass(Predicate.class);

        service.search("공지", auth(Role.ROLE_MANAGER));

        verify(menuService).getSidebarMenus(captor.capture());
        Predicate<String> visible = captor.getValue();
        assertThat(visible.test("/admin/notice/manage")).isFalse(); // 공지 READ 없음
        assertThat(visible.test("/admin")).isTrue();                 // 상시 허용
        assertThat(visible.test("/admin/member/manage")).isFalse();  // ADMIN 전용
    }

    @Test
    @DisplayName("검색어가 null·공백·1글자면 어떤 조회도 하지 않고 섹션 없는 빈 결과를 돌려준다")
    void shortKeyword_returnsEmpty_withoutQueries() {
        for (String keyword : new String[]{null, "", "   ", "a", " 가 "}) {
            AdminSearchResponse result = service.search(keyword, auth(Role.ROLE_ADMIN));

            assertThat(result.getMenus()).as("메뉴: " + keyword).isNull();
            assertThat(result.getNotices()).as("공지: " + keyword).isNull();
            assertThat(result.getMembers()).as("관리자: " + keyword).isNull();
        }
        verifyNoInteractions(menuService, noticeRepository, memberRepository, cache);
    }

    @Test
    @DisplayName("2코드포인트 검색어는 조회한다(서로게이트 쌍 이모지 1개는 1코드포인트라 조회하지 않는다)")
    void minLengthIsCountedInCodePoints() {
        service.search("공지", auth(Role.ROLE_ADMIN));
        verify(menuService, times(1)).getSidebarMenus(any());

        service.search("😀", auth(Role.ROLE_ADMIN)); // 😀 — char 2개, 코드포인트 1개
        verify(menuService, times(1)).getSidebarMenus(any());
    }

    @Test
    @DisplayName("메뉴는 자식 없는 URL 메뉴만 결과가 되고 그룹은 건너뛰며 경로는 '상위 > 하위'로 표시된다")
    void menus_groupsSkipped_leafPathShown() {
        when(menuService.getSidebarMenus(any())).thenReturn(List.of(
                menu(1, "공지 그룹", null,
                        menu(2, "공지사항 관리", "/admin/notice/manage")),
                menu(3, "공지 링크 그룹", "/admin/x", menu(4, "무관", "/admin/y"))));

        AdminSearchResponse result = service.search("공지", auth(Role.ROLE_ADMIN));

        assertThat(result.getMenus().getTotal()).isEqualTo(1);
        AdminSearchResponse.MenuItem item = result.getMenus().getItems().get(0);
        assertThat(item.getName()).isEqualTo("공지사항 관리");
        assertThat(item.getPath()).isEqualTo("공지 그룹 > 공지사항 관리");
        assertThat(item.getUrl()).isEqualTo("/admin/notice/manage");
    }

    @Test
    @DisplayName("안전한 같은 출처 경로가 아닌 메뉴 URL(javascript:·//host·절대 URL·역슬래시·공백·제어문자)은 결과에서 제외된다")
    void menus_unsafeUrlsExcluded() {
        when(menuService.getSidebarMenus(any())).thenReturn(List.of(
                menu(1, "공지 a", "javascript:alert(1)"),
                menu(2, "공지 b", "//evil.example/x"),
                menu(3, "공지 c", "https://evil.example/x"),
                menu(4, "공지 d", "/admin\\evil"),
                menu(5, "공지 e", "/admin/a b"),
                menu(6, "공지 f", "/admin/a\nb"),
                menu(7, "공지 g", "data:text/html,x"),
                menu(8, "공지 h", null),
                menu(9, "공지 ok", "/admin/notice/manage")));

        AdminSearchResponse result = service.search("공지", auth(Role.ROLE_ADMIN));

        assertThat(result.getMenus().getItems()).extracting(AdminSearchResponse.MenuItem::getName).containsExactly("공지 ok");
        assertThat(result.getMenus().getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("메뉴 결과는 섹션당 5건으로 자르고 전체 건수는 total로 알려준다")
    void menus_limitedToFive_totalReported() {
        List<SidebarMenuResponse> menus = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            menus.add(menu(i, "공지 메뉴 " + i, "/admin/m" + i));
        }
        when(menuService.getSidebarMenus(any())).thenReturn(menus);

        AdminSearchResponse result = service.search("공지", auth(Role.ROLE_ADMIN));

        assertThat(result.getMenus().getItems()).hasSize(5);
        assertThat(result.getMenus().getTotal()).isEqualTo(8);
    }

    @Test
    @DisplayName("메뉴 이름 비교는 대소문자를 구분하지 않는다")
    void menus_caseInsensitive() {
        when(menuService.getSidebarMenus(any())).thenReturn(List.of(menu(1, "Dashboard Home", "/admin")));

        AdminSearchResponse result = service.search("dASH", auth(Role.ROLE_ADMIN));

        assertThat(result.getMenus().getItems()).hasSize(1);
    }

    @Test
    @DisplayName("관리자 항목은 이메일 등 불필요한 필드를 담지 않는다")
    void memberItem_exposesOnlyIdentityFields() {
        assertThat(AdminSearchResponse.MemberItem.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .containsExactlyInAnyOrder("id", "userId", "userName", "userType", "status");
    }
}
