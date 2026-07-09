package com.cms.admin.menu.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
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

    @InjectMocks
    MenuService menuService;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
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

    private Menu menuWithAccessRole(Long menuNo, String menuName, Long upMenuNo, MenuAccessRole accessRole) {
        return Menu.builder()
                .menuNo(menuNo)
                .menuName(menuName)
                .useYn(true)
                .ord(0)
                .upMenuNo(upMenuNo)
                .accessRole(accessRole)
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
                .ord(0)
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
    @DisplayName("생성 시 ord 누락은 형제 max(ord)+1로 자동 배치 (최상위, 형제 없음 → 0)")
    void createMenu_ordAutoAssign_topLevel_noSiblings() {
        MenuCreateRequest request = MenuCreateRequest.builder().menuName("메뉴").build();

        given(menuRepository.findMaxOrdByUpMenuNoIsNull()).willReturn(null);
        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        given(menuRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        assertEquals(0, captor.getValue().getOrd());
    }

    @Test
    @DisplayName("생성 시 ord 누락은 형제 max(ord)+1로 자동 배치 (지정 부모 아래 형제 존재)")
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

    @Test
    @DisplayName("생성 시 accessRole 누락은 ALL(공용)로 기본화")
    void createMenu_accessRoleDefaultsToAll() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("메뉴")
                .build();

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        given(menuRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        assertEquals(MenuAccessRole.ALL, captor.getValue().getAccessRole());
    }

    @Test
    @DisplayName("생성 시 accessRole=ADMIN이 그대로 저장된다")
    void createMenu_accessRoleAdminPersisted() {
        MenuCreateRequest request = MenuCreateRequest.builder()
                .menuName("메뉴 관리")
                .accessRole(MenuAccessRole.ADMIN)
                .build();

        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        given(menuRepository.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        menuService.createMenu(request);

        assertEquals(MenuAccessRole.ADMIN, captor.getValue().getAccessRole());
    }

    // ── 수정 ──────────────────────────────────────────

    @Test
    @DisplayName("PATCH로 비활성 부모 아래 자식을 useYn=true로 재활성화 시 400 거부")
    void updateMenu_reactivateUnderInactiveParent_rejected() {
        Menu inactiveChild = menu(2L, "자식", 1L, false, 0);
        Menu inactiveParent = menu(1L, "부모", null, false, 0);

        given(menuRepository.findById(2L)).willReturn(Optional.of(inactiveChild));
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
        given(menuRepository.findById(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertTrue(response.getUseYn());
        assertEquals("변경된 이름", response.getMenuName());
        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("PATCH 시 ord 누락/null은 기존값을 유지")
    void updateMenu_ordNullKeepsExisting() {
        Menu existing = menu(1L, "메뉴", null, true, 5);
        given(menuRepository.findById(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertEquals(5, response.getOrd());
    }

    @Test
    @DisplayName("PATCH 시 accessRole 누락/null은 기존값을 유지")
    void updateMenu_accessRoleNullKeepsExisting() {
        Menu existing = menuWithAccessRole(1L, "메뉴 관리", null, MenuAccessRole.ADMIN);
        given(menuRepository.findById(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertEquals(MenuAccessRole.ADMIN, response.getAccessRole());
    }

    @Test
    @DisplayName("PATCH로 accessRole=ALL을 보내면 ADMIN 전용 메뉴를 공용으로 되돌릴 수 있다")
    void updateMenu_accessRoleRevertsToAll() {
        Menu existing = menuWithAccessRole(1L, "메뉴 관리", null, MenuAccessRole.ADMIN);
        given(menuRepository.findById(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().accessRole(MenuAccessRole.ALL).build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertEquals(MenuAccessRole.ALL, response.getAccessRole());
    }

    // ── 비활성화(삭제) ──────────────────────────────────

    @Test
    @DisplayName("삭제는 row 제거가 아니라 useYn=false 변경이며, MenuResponse를 반환한다")
    void deactivateMenu_setsUseYnFalse_returnsMenuResponse() {
        Menu target = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNoAndUseYnTrue(1L)).willReturn(false);

        MenuResponse response = menuService.deactivateMenu(1L);

        assertEquals(1L, response.getMenuNo());
        assertFalse(response.getUseYn());
        verify(menuRepository, never()).delete(any());
        verify(menuRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("활성 하위 메뉴가 있는 메뉴 비활성화 시 409 거부")
    void deactivateMenu_withActiveChildren_rejected() {
        Menu target = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNoAndUseYnTrue(1L)).willReturn(true);

        assertThrows(ConflictException.class, () -> menuService.deactivateMenu(1L));
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

        given(menuRepository.findById(2L)).willReturn(Optional.of(inactiveChild));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeParent));

        MenuUpdateRequest request = MenuUpdateRequest.builder().useYn(true).build();

        menuService.updateMenu(2L, request);

        verify(menuRepository).findByIdForUpdate(1L);
    }

    @Test
    @DisplayName("동시성 방어: 비활성화 시 findByIdForUpdate로 대상 row를 잠근다")
    void deactivateMenu_locksTargetRow() {
        Menu target = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(target));
        given(menuRepository.existsByUpMenuNoAndUseYnTrue(1L)).willReturn(false);

        menuService.deactivateMenu(1L);

        verify(menuRepository).findByIdForUpdate(1L);
    }

    // ── 트리 조회 ──────────────────────────────────────

    @Test
    @DisplayName("트리 조회: 필터·정렬·계층 구조(menuLevel/topMenuNo)를 올바르게 구성한다")
    void getMenuTree_buildsHierarchyCorrectly() {
        Menu root1 = menu(1L, "루트1", null, true, 0);
        Menu child1 = menu(2L, "자식1", 1L, true, 0);
        Menu grandchild = menu(3L, "손자", 2L, true, 0);
        Menu root2 = menu(5L, "루트2", null, true, 1);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
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
