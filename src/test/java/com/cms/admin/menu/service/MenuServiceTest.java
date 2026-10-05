package com.cms.admin.menu.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuDeleteResult;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    MenuRepository menuRepository;

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    com.cms.admin.permission.AdminPermissionEvaluator adminPermissionEvaluator;

    @InjectMocks
    MenuService menuService;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        org.mockito.Mockito.lenient().when(adminPermissionEvaluator.anyManagerMenuUrlVisibility()).thenReturn(url -> false);
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(MenuService.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(MenuService.class)).detachAppender(logAppender);
    }

    private Menu menu(Long menuNo, String menuName, Long upMenuNo, Boolean useYn, Integer ord) {
        return Menu.builder()
                .menuNo(menuNo)
                .menuName(menuName)
                .useYn(useYn)
                .ord(ord)
                .upMenuNo(upMenuNo)
                .build();
    }

    // ── 생성 ──────────────────────────────────────────

    @Test
    @DisplayName("메뉴 생성 성공")
    void createMenu_success() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("회원 관리")
                .menuUrl("/admin/member/manage")
                .useYn(true)
                .build();

        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> {
            Menu m = invocation.getArgument(0);
            return menu(1L, m.getMenuName(), m.getUpMenuNo(), m.getUseYn(), m.getOrd());
        });

        MenuResponse response = menuService.createMenu(request);

        assertEquals(1L, response.getMenuNo());
        assertEquals("회원 관리", response.getMenuName());
        assertTrue(response.getUseYn());
        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("메뉴 생성 시 createDate·updateDate는 주입된 KST Clock에서 나온다")
    void createMenu_usesInjectedClock() {
        MenuCreateRequest request = MenuCreateRequest.builder().menuName("시각 메뉴").useYn(true).build();
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository).save(captor.capture());
        assertEquals(FIXED_NOW, captor.getValue().getCreateDate());
        assertEquals(FIXED_NOW, captor.getValue().getUpdateDate());
    }

    @Test
    @DisplayName("존재하지 않는 부모 지정 생성 실패 (404)")
    void createMenu_parentNotFound() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("하위 메뉴")
                .upMenuNo(99L)
                .build();

        given(menuRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> menuService.createMenu(request));
    }

    @Test
    @DisplayName("활성 메뉴를 비활성 부모 아래 생성 시 400 거부")
    void createMenu_activeUnderInactiveParent_rejected() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("하위 메뉴")
                .useYn(true)
                .upMenuNo(1L)
                .build();

        Menu inactiveParent = menu(1L, "부모", null, false, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(inactiveParent));

        assertThrows(InvalidRequestException.class, () -> menuService.createMenu(request));
        verify(menuRepository, never()).save(any());
    }

    @Test
    @DisplayName("2단 부모 아래 생성은 허용된다(결과 3단)")
    void createMenu_underSecondLevelParent_allowed() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("3단 메뉴").useYn(true).upMenuNo(2L).build();
        Menu secondLevel = menu(2L, "2단", 1L, true, 0);
        Menu root = menu(1L, "1단", null, true, 0);
        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(secondLevel));
        given(menuRepository.findById(1L)).willReturn(Optional.of(root));
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        verify(menuRepository).save(any(Menu.class));
    }

    @Test
    @DisplayName("3단 부모 아래 생성(결과 4단)은 400 거부")
    void createMenu_underThirdLevelParent_rejected() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("4단 메뉴").useYn(true).upMenuNo(3L).build();
        Menu thirdLevel = menu(3L, "3단", 2L, true, 0);
        Menu secondLevel = menu(2L, "2단", 1L, true, 0);
        Menu root = menu(1L, "1단", null, true, 0);
        given(menuRepository.findByIdForUpdate(3L)).willReturn(Optional.of(thirdLevel));
        given(menuRepository.findById(2L)).willReturn(Optional.of(secondLevel));
        given(menuRepository.findById(1L)).willReturn(Optional.of(root));

        assertThrows(InvalidRequestException.class, () -> menuService.createMenu(request));
        verify(menuRepository, never()).save(any());
    }

    @Test
    @DisplayName("순환 데이터의 부모 아래 생성은 무한 루프 없이 깊이를 계산한다")
    void createMenu_cyclicParentChain_doesNotLoop() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("메뉴").useYn(true).upMenuNo(10L).build();
        Menu a = menu(10L, "A", 20L, true, 0);
        Menu b = menu(20L, "B", 10L, true, 0);
        given(menuRepository.findByIdForUpdate(10L)).willReturn(Optional.of(a));
        given(menuRepository.findById(20L)).willReturn(Optional.of(b));
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        // A(10) → B(20) → A(10, 이미 방문)에서 멈춘다: 깊이 2이므로 생성은 가능하고 무한 루프만 없어야 한다.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> menuService.createMenu(request));
    }

    @Test
    @DisplayName("생성은 전체 행 잠금(findAllForUpdate)을 첫 조회로 쓴다 — 최상위 생성도 포함")
    void createMenu_locksAllRowsFirst() {
        MenuCreateRequest request = MenuCreateRequest.builder().menuName("최상위").build();
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(menuRepository);
        inOrder.verify(menuRepository).findAllForUpdate();
        inOrder.verify(menuRepository).save(any(Menu.class));
    }

    @Test
    @DisplayName("같은 부모 그룹의 최대 ord가 Integer.MAX_VALUE면 그룹을 표시 순서대로 0..n-1 재번호한 뒤 마지막에 생성한다")
    void createMenu_ordOverflow_renumbersGroupAndAppendsLast() {
        Menu first = menu(1L, "첫째", null, true, 5);
        Menu last = menu(2L, "마지막", null, true, Integer.MAX_VALUE);
        given(menuRepository.findAllForUpdate()).willReturn(List.of(last, first)); // 일부러 표시 순서와 다르게 반환
        given(menuRepository.findMaxOrdByUpMenuNoIsNull()).willReturn(Integer.MAX_VALUE);
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        MenuResponse response = menuService.createMenu(MenuCreateRequest.builder().menuName("새 메뉴").build());

        assertEquals(0, first.getOrd());
        assertEquals(1, last.getOrd());
        assertEquals(2, response.getOrd(), "새 메뉴는 재번호된 그룹의 맨 끝");
    }

    @Test
    @DisplayName("PATCH(이름 수정 등)는 전체 행 잠금 없이 대상 행만 잠근다")
    void updateMenu_locksOnlyTargetRow() {
        Menu existing = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        menuService.updateMenu(1L, MenuUpdateRequest.builder().menuName("새 이름").build());

        verify(menuRepository, never()).findAllForUpdate();
    }

    @Test
    @DisplayName("생성: 공백만 있는 URL은 null로 저장한다")
    void createMenu_blankUrl_storedAsNull() {
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(MenuCreateRequest.builder().menuName("그룹").menuUrl("   ").build());

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository).save(captor.capture());
        assertNull(captor.getValue().getMenuUrl());
    }

    @Test
    @DisplayName("수정: URL이 null이면 기존값 유지, 빈 문자열이면 URL 제거(null), 값이 있으면 교체")
    void updateMenu_urlNullKeepsBlankClearsValueReplaces() {
        Menu existing = urlMenu(1L, "메뉴", null, "/admin/old", 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        menuService.updateMenu(1L, MenuUpdateRequest.builder().menuName("이름만").build());
        assertEquals("/admin/old", existing.getMenuUrl());

        menuService.updateMenu(1L, MenuUpdateRequest.builder().menuUrl("https://example.com/x").build());
        assertEquals("https://example.com/x", existing.getMenuUrl());

        menuService.updateMenu(1L, MenuUpdateRequest.builder().menuUrl("").build());
        assertNull(existing.getMenuUrl());
    }

    @Test
    @DisplayName("사이드바 조회: 저장 검증 도입 전에 들어간 위험 URL은 null로 바뀌어 담기고, 외부 http(s)와 경로는 그대로 남는다")
    void getSidebarMenus_unsafeStoredUrl_nulledOut() {
        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc()).willReturn(List.of(
                urlMenu(1L, "js", null, "javascript:alert(1)", 0),
                urlMenu(2L, "slashes", null, "//evil.example/x", 1),
                urlMenu(3L, "data", null, "data:text/html,x", 2),
                urlMenu(4L, "relative", null, "admin/x", 3),
                urlMenu(5L, "external", null, "https://example.com/x", 4),
                urlMenu(6L, "path", null, "/admin/x?a=1", 5)));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(url -> true);

        assertEquals(6, sidebar.size());
        for (int i = 0; i < 4; i++) {
            assertNull(sidebar.get(i).getMenuUrl(), sidebar.get(i).getMenuName());
            assertFalse(sidebar.get(i).isExternal());
        }
        assertEquals("https://example.com/x", sidebar.get(4).getMenuUrl());
        assertTrue(sidebar.get(4).isExternal());
        assertEquals("/admin/x?a=1", sidebar.get(5).getMenuUrl());
        assertFalse(sidebar.get(5).isExternal());
    }

    @Test
    @DisplayName("생성 시 useYn 누락은 true로 기본화")
    void createMenu_useYnDefaultsToTrue() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("메뉴")
                .build();

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        given(menuRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        assertTrue(captor.getValue().getUseYn());
    }

    @Test
    @DisplayName("생성은 항상 형제 max(ord)+1로 맨 끝에 배치 (최상위, 형제 없음 → 0)")
    void createMenu_ordAutoAssign_topLevel_noSiblings() {
        MenuCreateRequest request = MenuCreateRequest.builder().menuName("메뉴").build();

        given(menuRepository.findMaxOrdByUpMenuNoIsNull()).willReturn(null);
        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        given(menuRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        assertEquals(0, captor.getValue().getOrd());
    }

    @Test
    @DisplayName("생성은 항상 형제 max(ord)+1로 맨 끝에 배치 (지정 부모 아래 형제 존재)")
    void createMenu_ordAutoAssign_underParent_withSiblings() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("메뉴")
                .upMenuNo(1L)
                .build();

        Menu activeParent = menu(1L, "부모", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));
        given(menuRepository.findMaxOrdByUpMenuNo(1L)).willReturn(2);
        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        given(menuRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        assertEquals(3, captor.getValue().getOrd());
    }

    // ── 수정 ──────────────────────────────────────────

    @Test
    @DisplayName("PATCH로 비활성 부모 아래 자식을 useYn=true로 재활성화 시 400 거부")
    void updateMenu_reactivateUnderInactiveParent_rejected() {
        Menu inactiveChild = menu(2L, "자식", 1L, false, 0);
        Menu inactiveParent = menu(1L, "부모", null, false, 0);

        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(inactiveChild));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(inactiveParent));

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(true).build();

        assertThrows(InvalidRequestException.class, () -> menuService.updateMenu(2L, request));
    }

    @Test
    @DisplayName("PATCH로 활성 하위 메뉴가 있는 부모를 useYn=false로 비활성화 시 409 거부")
    void updateMenu_deactivateWithActiveChildren_rejected() {
        Menu activeParent = menu(1L, "부모", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));
        given(menuRepository.existsByUpMenuNoAndUseYnTrue(1L)).willReturn(true);

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(false).build();

        assertThrows(ConflictException.class, () -> menuService.updateMenu(1L, request));
        assertTrue(activeParent.getUseYn(), "거부 시 대상은 활성 상태로 유지되어야 한다");
    }

    @Test
    @DisplayName("PATCH로 활성 하위 메뉴가 없는 부모는 useYn=false로 비활성화 성공")
    void updateMenu_deactivateWithoutActiveChildren_success() {
        Menu activeParent = menu(1L, "부모", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));
        given(menuRepository.existsByUpMenuNoAndUseYnTrue(1L)).willReturn(false);

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(false).build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertFalse(response.getUseYn());
    }

    @Test
    @DisplayName("동시성 방어: PATCH 비활성화(useYn=false) 시 findByIdForUpdate로 대상 row를 잠근다")
    void updateMenu_deactivate_locksTargetRow() {
        Menu activeParent = menu(1L, "부모", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));
        given(menuRepository.existsByUpMenuNoAndUseYnTrue(1L)).willReturn(false);

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(false).build();

        menuService.updateMenu(1L, request);

        verify(menuRepository).findByIdForUpdate(1L);
        verify(menuRepository, never()).findById(any());
    }

    @Test
    @DisplayName("PATCH 시 useYn 누락/null은 기존값을 유지")
    void updateMenu_useYnNullKeepsExisting() {
        Menu existing = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertTrue(response.getUseYn());
        assertEquals("변경된 이름", response.getMenuName());
        verify(menuRepository, never()).findById(any());
    }

    @Test
    @DisplayName("PATCH는 순서(ord)를 바꾸지 않는다 — 구조·순서는 구조 반영 API로만 바뀐다")
    void updateMenu_neverChangesOrd() {
        Menu existing = menu(1L, "메뉴", null, true, 5);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertEquals(5, response.getOrd());
    }

    @Test
    @DisplayName("동시성 방어: 일반 수정(useYn 미포함)도 findByIdForUpdate로 대상 row를 잠근다")
    void updateMenu_generalEdit_locksTargetRow() {
        Menu existing = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        menuService.updateMenu(1L, request);

        verify(menuRepository).findByIdForUpdate(1L);
        verify(menuRepository, never()).findById(any());
    }

    // ── 영구삭제(하드 삭제) ───────────────────────────────

    @Test
    @DisplayName("비활성 + 하위 없음 메뉴는 row를 삭제하고, 삭제 전 이름·URL 스냅샷을 반환한다")
    void deleteMenu_inactiveLeaf_deletesRow_returnsSnapshot() {
        Menu target = Menu.builder().menuNo(1L).menuName("지울 메뉴").menuUrl("/old").useYn(false).ord(0).build();
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNo(1L)).willReturn(false);

        MenuDeleteResult result = menuService.deleteMenu(1L);

        assertEquals(1L, result.getMenuNo());
        assertEquals("지울 메뉴", result.getMenuName());
        assertEquals("/old", result.getMenuUrl());
        assertEquals("지울 메뉴 (/old)", result.getAuditLabel());
        verify(menuRepository).delete(target);
    }

    @Test
    @DisplayName("URL이 없으면 감사 라벨은 이름만이다")
    void deleteMenu_auditLabelWithoutUrl_isNameOnly() {
        Menu target = menu(1L, "그룹", null, false, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNo(1L)).willReturn(false);

        assertEquals("그룹", menuService.deleteMenu(1L).getAuditLabel());
    }

    @Test
    @DisplayName("활성 메뉴는 영구삭제할 수 없다 — 409, row 미삭제")
    void deleteMenu_activeMenu_rejected() {
        Menu target = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));

        assertThrows(ConflictException.class, () -> menuService.deleteMenu(1L));

        verify(menuRepository, never()).delete(any());
        verify(menuRepository, never()).existsByUpMenuNo(any());
    }

    @Test
    @DisplayName("하위 메뉴가 있으면(활성 아닌 비활성 자식만이어도) 영구삭제할 수 없다 — 409")
    void deleteMenu_withAnyChildren_rejected() {
        Menu target = menu(1L, "부모", null, false, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNo(1L)).willReturn(true);

        assertThrows(ConflictException.class, () -> menuService.deleteMenu(1L));

        verify(menuRepository, never()).delete(any());
        // 활성 자식 전용 검사(existsByUpMenuNoAndUseYnTrue)가 아니라 전체 자식 검사를 써야 한다
        verify(menuRepository, never()).existsByUpMenuNoAndUseYnTrue(any());
    }

    @Test
    @DisplayName("없는 메뉴 영구삭제는 404")
    void deleteMenu_notFound() {
        given(menuRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> menuService.deleteMenu(99L));

        verify(menuRepository, never()).delete(any());
    }

    // ── 동시성 방어 (락 메서드 호출 검증) ──────────────────

    @Test
    @DisplayName("동시성 방어: 부모 지정 생성 시 findByIdForUpdate로 부모 row를 잠근다")
    void createMenu_locksParentRow() {
        Menu activeParent = menu(1L, "부모", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        MenuCreateRequest request = MenuCreateRequest.builder().menuName("메뉴").upMenuNo(1L).build();

        menuService.createMenu(request);

        verify(menuRepository).findByIdForUpdate(1L);
    }

    @Test
    @DisplayName("동시성 방어: 재활성화 시 findByIdForUpdate로 부모 row를 잠근다")
    void updateMenu_reactivate_locksParentRow() {
        Menu inactiveChild = menu(2L, "자식", 1L, false, 0);
        Menu activeParent = menu(1L, "부모", null, true, 0);

        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(inactiveChild));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(true).build();

        menuService.updateMenu(2L, request);

        verify(menuRepository).findByIdForUpdate(1L);
    }

    @Test
    @DisplayName("동시성 방어: 영구삭제 시 findByIdForUpdate로 대상 row를 잠근다")
    void deleteMenu_locksTargetRow() {
        Menu target = menu(1L, "메뉴", null, false, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNo(1L)).willReturn(false);

        menuService.deleteMenu(1L);

        verify(menuRepository).findByIdForUpdate(1L);
        verify(menuRepository, never()).findById(any());
    }

    // ── 트리 조회 ──────────────────────────────────────

    @Test
    @DisplayName("트리 조회: 필터·정렬·계층 구조(menuLevel/topMenuNo)를 올바르게 구성한다")
    void getMenuTree_buildsHierarchyCorrectly() {
        Menu root1 = menu(1L, "루트1", null, true, 0);
        Menu child1 = menu(2L, "자식1", 1L, true, 0);
        Menu grandchild = menu(3L, "손자", 2L, true, 0);
        Menu root2 = menu(5L, "루트2", null, true, 1);

        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(root1, child1, grandchild, root2));

        List<MenuTreeResponse> tree = menuService.getMenuTree("true");

        assertEquals(2, tree.size());
        MenuTreeResponse root1Node = tree.get(0);
        assertEquals("1", root1Node.getId());
        assertEquals(1, root1Node.getData().getMenuLevel());
        assertEquals(1L, root1Node.getData().getTopMenuNo());
        assertEquals(1, root1Node.getChildren().size());

        MenuTreeResponse child1Node = root1Node.getChildren().get(0);
        assertEquals(2, child1Node.getData().getMenuLevel());
        assertEquals(1L, child1Node.getData().getTopMenuNo());
        assertEquals(1, child1Node.getChildren().size());

        MenuTreeResponse grandchildNode = child1Node.getChildren().get(0);
        assertEquals(3, grandchildNode.getData().getMenuLevel());
        assertEquals(1L, grandchildNode.getData().getTopMenuNo());

        MenuTreeResponse root2Node = tree.get(1);
        assertEquals(5L, root2Node.getData().getTopMenuNo());
        assertTrue(root2Node.getChildren().isEmpty());
    }

    @Test
    @DisplayName("트리 조회: useYn=all이면 비활성 메뉴도 포함한다")
    void getMenuTree_allFilter_includesInactive() {
        Menu root = menu(1L, "루트", null, true, 0);
        Menu inactiveChild = menu(2L, "비활성 자식", 1L, false, 0);

        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(root, inactiveChild));

        List<MenuTreeResponse> tree = menuService.getMenuTree("all");

        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());
        assertFalse(tree.get(0).getChildren().get(0).getData().getUseYn());
    }

    @Test
    @DisplayName("트리 조회: 허용되지 않은 useYn 값은 InvalidRequestException")
    void getMenuTree_invalidFilter_rejected() {
        assertThrows(InvalidRequestException.class, () -> menuService.getMenuTree("false"));
    }

    // ── 사이드바 조회 ──────────────────────────────────────

    private Menu urlMenu(Long menuNo, String menuName, Long upMenuNo, String menuUrl, int ord) {
        return Menu.builder().menuNo(menuNo).menuName(menuName).menuUrl(menuUrl)
                .useYn(true).ord(ord).upMenuNo(upMenuNo).build();
    }

    /** MANAGER 관점: 대시보드·내 정보·공지(READ 보유)만 보이는 판정. */
    private static final java.util.function.Predicate<String> MANAGER_WITH_NOTICE = url ->
            "/admin".equals(url) || "/admin/member/info".equals(url) || "/admin/notice/manage".equals(url);

    @Test
    @DisplayName("사이드바 조회: 전부 보이는 판정(ADMIN)이면 URL이 없는 빈 그룹까지 전체 활성 메뉴를 본다")
    void getSidebarMenus_visibleAll_seesEverything() {
        Menu dashboard = urlMenu(1L, "대시보드", null, "/admin", 0);
        Menu menuManage = urlMenu(2L, "메뉴 관리", null, "/admin/menu/manage", 1);
        Menu emptyGroup = urlMenu(3L, "빈 그룹", null, null, 2);
        Menu memberGroup = urlMenu(4L, "회원 관리", null, null, 3);
        Menu myInfo = urlMenu(5L, "내 정보", 4L, "/admin/member/info", 0);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(dashboard, menuManage, emptyGroup, memberGroup, myInfo));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(url -> true);

        assertEquals(4, sidebar.size());
        assertEquals("빈 그룹", sidebar.get(2).getMenuName());
        assertEquals("내 정보", sidebar.get(3).getChildren().get(0).getMenuName());
    }

    @Test
    @DisplayName("사이드바 조회: MANAGER 관점에서 위임 불가·미분류·URL 없는 리프는 빠지고, 보이는 자식이 있는 그룹은 남는다")
    void getSidebarMenus_manager_prunesInvisibleLeaves() {
        Menu dashboard = urlMenu(1L, "대시보드", null, "/admin", 0);
        Menu menuManage = urlMenu(2L, "메뉴 관리", null, "/admin/menu/manage", 1);
        Menu memberGroup = urlMenu(3L, "회원 관리", null, null, 2);
        Menu memberList = urlMenu(4L, "관리자 조회", 3L, "/admin/member/manage", 0);
        Menu myInfo = urlMenu(5L, "내 정보", 3L, "/admin/member/info", 1);
        Menu emptyGroup = urlMenu(6L, "빈 그룹", null, null, 3);
        Menu external = urlMenu(7L, "외부", null, "https://example.com", 4);
        Menu withQuery = urlMenu(8L, "쿼리", null, "/admin/notice/manage?x=1", 5);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(dashboard, menuManage, memberGroup, memberList, myInfo, emptyGroup, external, withQuery));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(MANAGER_WITH_NOTICE);

        assertEquals(List.of("대시보드", "회원 관리"), sidebar.stream().map(SidebarMenuResponse::getMenuName).toList());
        assertEquals(List.of("내 정보"), sidebar.get(1).getChildren().stream().map(SidebarMenuResponse::getMenuName).toList());
    }

    @Test
    @DisplayName("사이드바 조회: 부모 자신의 URL이 위임 불가여도 보이는 자식(공지)이 있으면 부모는 그룹으로 노출된다")
    void getSidebarMenus_manager_parentShownByVisibleChild() {
        Menu adminParent = urlMenu(1L, "관리 그룹", null, "/admin/menu/manage", 0);
        Menu notice = urlMenu(2L, "공지사항", 1L, "/admin/notice/manage", 0);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc()).willReturn(List.of(adminParent, notice));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(MANAGER_WITH_NOTICE);

        assertEquals(1, sidebar.size());
        assertEquals("공지사항", sidebar.get(0).getChildren().get(0).getMenuName());
    }

    @Test
    @DisplayName("사이드바 조회: 공지 권한이 회수되면(공지 URL 비노출) 공지 메뉴와 그것만 담은 그룹이 사라진다")
    void getSidebarMenus_manager_noticeRevoked() {
        Menu group = urlMenu(1L, "업무", null, null, 0);
        Menu notice = urlMenu(2L, "공지사항", 1L, "/admin/notice/manage", 0);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc()).willReturn(List.of(group, notice));

        assertTrue(menuService.getSidebarMenus(url -> "/admin".equals(url)).isEmpty());
    }

    @Test
    @DisplayName("사이드바 조회: 3단(손자)까지 조립되고 4단 메뉴는 포함되지 않는다")
    void getSidebarMenus_limitedToThreeLevels() {
        Menu root = menu(1L, "루트", null, true, 0);
        Menu child = menu(2L, "자식", 1L, true, 0);
        Menu grandchild = menu(3L, "손자", 2L, true, 0);
        Menu greatGrandchild = menu(4L, "증손", 3L, true, 0);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(root, child, grandchild, greatGrandchild));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(url -> true);

        assertEquals(1, sidebar.size());
        SidebarMenuResponse childNode = sidebar.get(0).getChildren().get(0);
        assertEquals("자식", childNode.getMenuName());
        assertEquals(1, childNode.getChildren().size());
        SidebarMenuResponse grandchildNode = childNode.getChildren().get(0);
        assertEquals("손자", grandchildNode.getMenuName());
        assertTrue(grandchildNode.getChildren().isEmpty());
    }

    // ── 노출 안내(exposure) ──────────────────────────────────

    private Map<Long, MenuTreeResponse.Data> flatten(List<MenuTreeResponse> tree) {
        Map<Long, MenuTreeResponse.Data> result = new java.util.HashMap<>();
        java.util.ArrayDeque<MenuTreeResponse> queue = new java.util.ArrayDeque<>(tree);
        while (!queue.isEmpty()) {
            MenuTreeResponse node = queue.poll();
            result.put(node.getData().getMenuNo(), node.getData());
            queue.addAll(node.getChildren());
        }
        return result;
    }

    @Test
    @DisplayName("exposure: 리프는 자기 URL 기준(상시/기능 권한/ADMIN 전용), 그룹은 보이는 자식 기준")
    void getMenuTree_exposure_leafByUrlAndGroupByChildren() {
        Menu dashboard = urlMenu(1L, "대시보드", null, "/admin", 0);
        Menu noticeGroup = urlMenu(2L, "그룹", null, null, 1);
        Menu notice = urlMenu(3L, "공지", 2L, "/admin/notice/manage", 0);
        Menu menuManage = urlMenu(4L, "메뉴 관리", null, "/admin/menu/manage", 2);
        Menu emptyGroup = urlMenu(5L, "빈 그룹", null, null, 3);
        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(dashboard, noticeGroup, notice, menuManage, emptyGroup));
        given(adminPermissionEvaluator.anyManagerMenuUrlVisibility()).willReturn(MANAGER_WITH_NOTICE);

        Map<Long, MenuTreeResponse.Data> data = flatten(menuService.getMenuTree("all"));

        assertEquals("ALL_ADMINS", data.get(1L).getExposure());
        assertEquals("VISIBLE_BY_CHILDREN", data.get(2L).getExposure());
        assertEquals("PERMISSION:NOTICE", data.get(3L).getExposure());
        assertEquals("ADMIN_ONLY", data.get(4L).getExposure());
        assertEquals("ADMIN_ONLY", data.get(5L).getExposure(), "자식 없는 URL null 그룹");
    }

    @Test
    @DisplayName("exposure: /admin URL 부모 아래 ADMIN 전용 자식만 있으면 자식이 가지치기돼 부모는 자기 URL 기준 ALL_ADMINS")
    void getMenuTree_exposure_parentFallsBackToOwnUrlWhenChildrenPruned() {
        Menu parent = urlMenu(1L, "대시보드 부모", null, "/admin", 0);
        Menu adminChild = urlMenu(2L, "메뉴 관리", 1L, "/admin/menu/manage", 0);
        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc()).willReturn(List.of(parent, adminChild));
        given(adminPermissionEvaluator.anyManagerMenuUrlVisibility()).willReturn(MANAGER_WITH_NOTICE);

        Map<Long, MenuTreeResponse.Data> data = flatten(menuService.getMenuTree("all"));

        assertEquals("ALL_ADMINS", data.get(1L).getExposure());
        assertEquals("ADMIN_ONLY", data.get(2L).getExposure());
        // 실제 MANAGER 사이드바도 부모를 리프(대시보드 링크)로 그린다 — 안내와 같은 입력·같은 규칙
        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc()).willReturn(List.of(parent, adminChild));
        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(MANAGER_WITH_NOTICE);
        assertEquals(1, sidebar.size());
        assertTrue(sidebar.get(0).getChildren().isEmpty());
    }

    @Test
    @DisplayName("exposure: 비활성 메뉴·비활성 조상 아래 메뉴·4단 메뉴는 NOT_SHOWN")
    void getMenuTree_exposure_notShown() {
        Menu inactiveParent = Menu.builder().menuNo(1L).menuName("비활성").menuUrl("/admin").useYn(false).ord(0).build();
        Menu underInactive = urlMenu(2L, "비활성 아래", 1L, "/admin", 0);
        Menu root = urlMenu(3L, "루트", null, null, 1);
        Menu child = urlMenu(4L, "자식", 3L, null, 0);
        Menu grandchild = urlMenu(5L, "손자", 4L, "/admin", 0);
        Menu level4 = urlMenu(6L, "4단", 5L, "/admin", 0);
        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(inactiveParent, underInactive, root, child, grandchild, level4));
        given(adminPermissionEvaluator.anyManagerMenuUrlVisibility()).willReturn(MANAGER_WITH_NOTICE);

        Map<Long, MenuTreeResponse.Data> data = flatten(menuService.getMenuTree("all"));

        assertEquals("NOT_SHOWN", data.get(1L).getExposure());
        assertEquals("NOT_SHOWN", data.get(2L).getExposure());
        assertEquals("ALL_ADMINS", data.get(5L).getExposure());
        assertEquals("NOT_SHOWN", data.get(6L).getExposure());
    }

    @Test
    @DisplayName("exposure: 위임 가능 기능은 권한을 받으면 MANAGER가 볼 수 있으므로 공지만 담은 그룹은 VISIBLE_BY_CHILDREN, 공지 리프는 PERMISSION:NOTICE")
    void getMenuTree_exposure_delegableLeafMakesGroupVisible() {
        Menu group = urlMenu(1L, "업무", null, null, 0);
        Menu notice = urlMenu(2L, "공지", 1L, "/admin/notice/manage", 0);
        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc()).willReturn(List.of(group, notice));
        // 실제 판정(카탈로그 분류)을 그대로 쓴다 — 이 판정은 캐시를 읽지 않는다
        given(adminPermissionEvaluator.anyManagerMenuUrlVisibility())
                .willReturn(new com.cms.admin.permission.AdminPermissionEvaluator(null).anyManagerMenuUrlVisibility());

        Map<Long, MenuTreeResponse.Data> data = flatten(menuService.getMenuTree("all"));

        assertEquals("VISIBLE_BY_CHILDREN", data.get(1L).getExposure());
        assertEquals("PERMISSION:NOTICE", data.get(2L).getExposure());
    }

    @Test
    @DisplayName("트리 조회: 권한 스냅샷(캐시)을 읽지 않는다 — 노출 안내는 카탈로그 분류만 본다")
    void getMenuTree_doesNotReadPermissionSnapshot() {
        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc()).willReturn(List.of(menu(1L, "루트", null, true, 0)));

        menuService.getMenuTree("true");

        verify(adminPermissionEvaluator, org.mockito.Mockito.never()).snapshot();
    }

    // ── 순환/미방문 노드 방어 ──────────────────────────────

    @Test
    @DisplayName("순환 데이터 방어: 순환 가지는 무한 재귀 없이 트리에서 제외되고 WARN 로그로 탐지 가능하다")
    void getMenuTree_cyclicBranch_excludedAndLogged() {
        // 실제 DB는 menuNo가 PK라 중복될 수 없지만, "DB 직접 조작 등 외부 원인으로 순환이
        // 존재할 수 있다"는 방어 대상 시나리오를 재현하기 위해 동일 menuNo가 두 번 등장하는
        // 상황을 모의(mock)한다. root(1)->a(2)까지는 정상이며, a(2)의 자식 자리에 같은
        // menuNo=2를 가진 노드를 다시 주입해 재귀 중 조상 재방문(순환)을 유도한다.
        Menu root = menu(1L, "루트", null, true, 0);
        Menu a = menu(2L, "A", 1L, true, 0);
        Menu cyclicDuplicate = menu(2L, "A(순환 중복)", 2L, true, 0);

        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(root, a, cyclicDuplicate));

        List<MenuTreeResponse> tree = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> menuService.getMenuTree("all"));

        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());
        assertTrue(tree.get(0).getChildren().get(0).getChildren().isEmpty());

        boolean warnLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("순환"));
        assertTrue(warnLogged, "순환 감지 시 WARN 로그가 남아야 한다");
    }

    @Test
    @DisplayName("미방문 노드 방어: 루트 없는 순환(A↔B 서로 부모)은 트리에서 제외되고 WARN 로그로 기록된다")
    void getMenuTree_rootlessCycle_excludedAndLogged() {
        Menu a = menu(10L, "A", 20L, true, 0);
        Menu b = menu(20L, "B", 10L, true, 0);

        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc()).willReturn(List.of(a, b));

        List<MenuTreeResponse> tree = menuService.getMenuTree("all");

        assertTrue(tree.isEmpty());

        boolean warnLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("미방문"));
        assertTrue(warnLogged, "루트 없는 순환은 미방문 노드로 WARN 로그가 남아야 한다");
    }

    @Test
    @DisplayName("미방문 노드 방어: 고아 노드(존재하지 않는 upMenuNo 참조)는 트리에서 제외되고 WARN 로그로 기록된다")
    void getMenuTree_orphanNode_excludedAndLogged() {
        Menu root = menu(1L, "루트", null, true, 0);
        Menu child = menu(2L, "자식", 1L, true, 0);
        Menu orphan = menu(30L, "고아", 999L, true, 0);

        given(menuRepository.findAllByOrderByOrdAscMenuNoAsc()).willReturn(List.of(root, child, orphan));

        List<MenuTreeResponse> tree = menuService.getMenuTree("all");

        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());

        boolean warnLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("미방문"));
        assertTrue(warnLogged, "고아 노드는 미방문 노드로 WARN 로그가 남아야 한다");
    }
}
