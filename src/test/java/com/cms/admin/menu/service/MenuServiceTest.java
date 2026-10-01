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
    @DisplayName("관리자 전용 메뉴 아래에 공용 메뉴(기본값 포함)를 만들면 400, 관리자 전용 메뉴는 허용")
    void createMenu_commonUnderAdminOnly_rejected() {
        Menu adminParent = menuWithAccessRole(1L, "전용 그룹", null, MenuAccessRole.ADMIN);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(adminParent));
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        // accessRole 미지정 = 공용(ALL)
        assertThrows(InvalidRequestException.class, () -> menuService.createMenu(
                MenuCreateRequest.builder().menuName("공용").upMenuNo(1L).build()));
        verify(menuRepository, never()).save(any());

        menuService.createMenu(MenuCreateRequest.builder().menuName("전용").upMenuNo(1L)
                .accessRole(MenuAccessRole.ADMIN).build());
        verify(menuRepository).save(any(Menu.class));
    }

    @Test
    @DisplayName("관리자 전용 조상(2단 위)이 있어도 그 아래 공용 메뉴 생성은 400")
    void createMenu_commonUnderAdminOnlyGrandparent_rejected() {
        Menu adminRoot = menuWithAccessRole(1L, "전용 그룹", null, MenuAccessRole.ADMIN);
        Menu middle = menuWithAccessRole(2L, "중간", 1L, MenuAccessRole.ADMIN);
        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(middle));
        given(menuRepository.findById(1L)).willReturn(Optional.of(adminRoot));

        assertThrows(InvalidRequestException.class, () -> menuService.createMenu(
                MenuCreateRequest.builder().menuName("공용").upMenuNo(2L).build()));
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
    @DisplayName("PATCH 권한 변경: 관리자 전용 조상 아래 메뉴를 공용으로 바꾸면 400")
    void updateMenu_adminToCommonUnderAdminAncestor_rejected() {
        Menu adminParent = menuWithAccessRole(1L, "전용 그룹", null, MenuAccessRole.ADMIN);
        Menu child = menuWithAccessRole(2L, "전용 자식", 1L, MenuAccessRole.ADMIN);
        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(child));
        given(menuRepository.findById(1L)).willReturn(Optional.of(adminParent));

        assertThrows(InvalidRequestException.class, () -> menuService.updateMenu(2L,
                MenuUpdateRequest.builder().accessRole(MenuAccessRole.ALL).build()));
        assertEquals(MenuAccessRole.ADMIN, child.getAccessRole());
    }

    @Test
    @DisplayName("PATCH 권한 변경: 공용 하위(손자 포함)가 있는 메뉴를 관리자 전용으로 바꾸면 400, 하위가 전용뿐이면 허용")
    void updateMenu_commonToAdminWithCommonDescendant_rejected() {
        Menu group = menuWithAccessRole(1L, "그룹", null, MenuAccessRole.ALL);
        Menu child = menuWithAccessRole(2L, "자식", 1L, MenuAccessRole.ADMIN);
        Menu grandchild = menuWithAccessRole(3L, "손자", 2L, MenuAccessRole.ALL); // 기존 위반 — 손자가 공용
        given(menuRepository.findAllForUpdate()).willReturn(List.of(group, child, grandchild));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(group));

        assertThrows(InvalidRequestException.class, () -> menuService.updateMenu(1L,
                MenuUpdateRequest.builder().accessRole(MenuAccessRole.ADMIN).build()));
        assertEquals(MenuAccessRole.ALL, group.getAccessRole());
    }

    @Test
    @DisplayName("PATCH 권한 변경: 하위가 전부 관리자 전용이면 공용→관리자 전용 허용")
    void updateMenu_commonToAdminWithAdminDescendantsOnly_allowed() {
        Menu group = menuWithAccessRole(1L, "그룹", null, MenuAccessRole.ALL);
        Menu child = menuWithAccessRole(2L, "자식", 1L, MenuAccessRole.ADMIN);
        given(menuRepository.findAllForUpdate()).willReturn(List.of(group, child));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(group));

        menuService.updateMenu(1L, MenuUpdateRequest.builder().accessRole(MenuAccessRole.ADMIN).build());

        assertEquals(MenuAccessRole.ADMIN, group.getAccessRole());
    }

    @Test
    @DisplayName("PATCH에 accessRole이 없으면 전체 행 잠금 없이 대상 행만 잠근다(이름 수정 등)")
    void updateMenu_withoutAccessRole_locksOnlyTargetRow() {
        Menu existing = menu(1L, "메뉴", null, true, 0);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        menuService.updateMenu(1L, MenuUpdateRequest.builder().menuName("새 이름").build());

        verify(menuRepository, never()).findAllForUpdate();
    }

    @Test
    @DisplayName("PATCH에 accessRole이 있으면 같은 값이어도 전체 행 잠금을 첫 조회로 쓴다")
    void updateMenu_withAccessRole_locksAllRowsFirst() {
        Menu existing = menuWithAccessRole(1L, "메뉴", null, MenuAccessRole.ALL);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        menuService.updateMenu(1L, MenuUpdateRequest.builder().accessRole(MenuAccessRole.ALL).build());

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(menuRepository);
        inOrder.verify(menuRepository).findAllForUpdate();
        inOrder.verify(menuRepository).findByIdForUpdate(1L);
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
    @DisplayName("PATCH 시 accessRole 누락/null은 기존값을 유지")
    void updateMenu_accessRoleNullKeepsExisting() {
        Menu existing = menuWithAccessRole(1L, "메뉴 관리", null, MenuAccessRole.ADMIN);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().menuName("변경된 이름").build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertEquals(MenuAccessRole.ADMIN, response.getAccessRole());
    }

    @Test
    @DisplayName("PATCH로 accessRole=ALL을 보내면 ADMIN 전용 메뉴를 공용으로 되돌릴 수 있다")
    void updateMenu_accessRoleRevertsToAll() {
        Menu existing = menuWithAccessRole(1L, "메뉴 관리", null, MenuAccessRole.ADMIN);
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(existing));

        MenuUpdateRequest request = MenuUpdateRequest.builder().accessRole(MenuAccessRole.ALL).build();

        MenuResponse response = menuService.updateMenu(1L, request);

        assertEquals(MenuAccessRole.ALL, response.getAccessRole());
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
        assertEquals(FIXED_NOW, target.getUpdateDate()); // 비활성화 시각은 주입된 KST Clock
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

        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(inactiveChild));
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

    // ── 사이드바 조회 ──────────────────────────────────────

    @Test
    @DisplayName("사이드바 조회: ADMIN은 ADMIN 전용 메뉴를 포함한 전체 활성 메뉴를 본다")
    void getSidebarMenus_admin_seesAll() {
        Menu dashboard = menuWithAccessRole(1L, "대시보드", null, MenuAccessRole.ALL);
        Menu menuManage = menuWithAccessRole(2L, "메뉴 관리", null, MenuAccessRole.ADMIN);
        Menu memberGroup = menuWithAccessRole(3L, "회원 관리", null, MenuAccessRole.ALL);
        Menu myInfo = menuWithAccessRole(4L, "내 정보", 3L, MenuAccessRole.ALL);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(dashboard, menuManage, memberGroup, myInfo));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(true);

        assertEquals(3, sidebar.size());
        assertEquals("메뉴 관리", sidebar.get(1).getMenuName());
        assertEquals(1, sidebar.get(2).getChildren().size());
        assertEquals("내 정보", sidebar.get(2).getChildren().get(0).getMenuName());
    }

    @Test
    @DisplayName("사이드바 조회: ADMIN이 아니면 ADMIN 전용 메뉴(최상위·하위 모두)가 제외된다")
    void getSidebarMenus_nonAdmin_adminOnlyExcluded() {
        Menu dashboard = menuWithAccessRole(1L, "대시보드", null, MenuAccessRole.ALL);
        Menu menuManage = menuWithAccessRole(2L, "메뉴 관리", null, MenuAccessRole.ADMIN);
        Menu memberGroup = menuWithAccessRole(3L, "회원 관리", null, MenuAccessRole.ALL);
        Menu memberList = menuWithAccessRole(4L, "관리자 조회", 3L, MenuAccessRole.ADMIN);
        Menu myInfo = menuWithAccessRole(5L, "내 정보", 3L, MenuAccessRole.ALL);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(dashboard, menuManage, memberGroup, memberList, myInfo));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(false);

        assertEquals(2, sidebar.size());
        assertEquals("대시보드", sidebar.get(0).getMenuName());
        assertEquals("회원 관리", sidebar.get(1).getMenuName());
        assertEquals(1, sidebar.get(1).getChildren().size());
        assertEquals("내 정보", sidebar.get(1).getChildren().get(0).getMenuName());
    }

    @Test
    @DisplayName("사이드바 조회: accessRole이 null인 레거시 행은 공용(ALL)으로 간주되어 노출된다")
    void getSidebarMenus_nullAccessRole_treatedAsAll() {
        Menu legacy = menu(1L, "레거시 메뉴", null, true, 0); // accessRole 미지정(null)

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc()).willReturn(List.of(legacy));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(false);

        assertEquals(1, sidebar.size());
    }

    @Test
    @DisplayName("사이드바 조회: 3단(손자)까지 조립되고 4단 메뉴는 포함되지 않는다")
    void getSidebarMenus_limitedToThreeLevels() {
        Menu root = menuWithAccessRole(1L, "루트", null, MenuAccessRole.ALL);
        Menu child = menuWithAccessRole(2L, "자식", 1L, MenuAccessRole.ALL);
        Menu grandchild = menuWithAccessRole(3L, "손자", 2L, MenuAccessRole.ALL);
        Menu greatGrandchild = menuWithAccessRole(4L, "증손", 3L, MenuAccessRole.ALL);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(root, child, grandchild, greatGrandchild));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(true);

        assertEquals(1, sidebar.size());
        SidebarMenuResponse childNode = sidebar.get(0).getChildren().get(0);
        assertEquals("자식", childNode.getMenuName());
        assertEquals(1, childNode.getChildren().size());
        SidebarMenuResponse grandchildNode = childNode.getChildren().get(0);
        assertEquals("손자", grandchildNode.getMenuName());
        assertTrue(grandchildNode.getChildren().isEmpty());
    }

    @Test
    @DisplayName("사이드바 조회: 중간 메뉴가 ADMIN 전용이라 제외되면 비관리자에게는 그 아래 3단 메뉴도 보이지 않는다")
    void getSidebarMenus_nonAdmin_grandchildHiddenWhenParentExcluded() {
        Menu root = menuWithAccessRole(1L, "루트", null, MenuAccessRole.ALL);
        Menu adminChild = menuWithAccessRole(2L, "관리자 그룹", 1L, MenuAccessRole.ADMIN);
        Menu grandchild = menuWithAccessRole(3L, "손자", 2L, MenuAccessRole.ADMIN);

        given(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc())
                .willReturn(List.of(root, adminChild, grandchild));

        List<SidebarMenuResponse> sidebar = menuService.getSidebarMenus(false);

        assertEquals(1, sidebar.size());
        assertTrue(sidebar.get(0).getChildren().isEmpty());
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
