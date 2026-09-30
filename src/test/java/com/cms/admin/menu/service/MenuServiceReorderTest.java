package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.MenuRepository.SiblingRow;
import com.cms.admin.menu.dto.request.MenuOrderRequest;
import com.cms.admin.menu.dto.request.MenuOrderScope;
import com.cms.admin.menu.dto.response.MenuOrderResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 형제 메뉴 순서 재조정({@link MenuService#reorderMenus}) 단위 테스트 — PLAN-menu-reorder.md.
 * 실제 잠금 직렬화·동시성은 MenuConcurrencyIntegrationTest가 검증한다(여기서는 호출 순서·규칙만).
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceReorderTest {

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Mock
    MenuRepository menuRepository;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    MenuService menuService;

    private static Menu menu(long menuNo, Long upMenuNo, boolean useYn, Integer ord) {
        return Menu.builder().menuNo(menuNo).menuName("메뉴" + menuNo).upMenuNo(upMenuNo).useYn(useYn).ord(ord).build();
    }

    private static SiblingRow row(Menu menu) {
        return new SiblingRow(menu.getMenuNo(), menu.getOrd(), menu.getUseYn());
    }

    /** 루트 형제 스냅샷과 잠금 조회를 함께 준비한다(스냅샷은 menuNo 오름차순으로 준다). */
    private void givenRootSiblings(Menu... menus) {
        given(menuRepository.findRootSiblingRows()).willReturn(java.util.Arrays.stream(menus).map(MenuServiceReorderTest::row).toList());
        for (Menu m : menus) {
            given(menuRepository.findByIdForUpdate(m.getMenuNo())).willReturn(Optional.of(m));
        }
    }

    private static MenuOrderRequest request(Long upMenuNo, MenuOrderScope scope, Long... menuNos) {
        return MenuOrderRequest.builder().upMenuNo(upMenuNo).scope(scope).menuNos(List.of(menuNos)).build();
    }

    private static Map<Long, Integer> ordByMenuNo(MenuOrderResponse response) {
        return response.getMenus().stream()
                .collect(Collectors.toMap(MenuOrderResponse.Item::getMenuNo, MenuOrderResponse.Item::getOrd));
    }

    // ===================== scope=ALL =====================

    @Test
    @DisplayName("ALL: 요청 순서대로 형제 ord를 0..n-1로 재배정하고 바뀐 행만 updateDate를 갱신한다")
    void reorder_all_reassignsOrdAndTouchesOnlyChangedRows() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, true, 1);
        Menu c = menu(3L, null, true, 2);
        givenRootSiblings(a, b, c);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ALL, 1L, 3L, 2L));

        assertEquals(0, a.getOrd());
        assertEquals(1, c.getOrd());
        assertEquals(2, b.getOrd());
        assertNull(a.getUpdateDate(), "순서가 그대로인 행은 건드리지 않는다");
        assertEquals(FIXED_NOW, c.getUpdateDate());
        assertEquals(FIXED_NOW, b.getUpdateDate());
        assertNull(response.getUpMenuNo());
        assertEquals(List.of(1L, 3L, 2L), response.getMenus().stream().map(MenuOrderResponse.Item::getMenuNo).toList());
        assertEquals(Map.of(1L, 0, 3L, 1, 2L, 2), ordByMenuNo(response));
    }

    @Test
    @DisplayName("ALL: 순서가 이미 같으면 어떤 행도 갱신하지 않고 그대로 성공한다(멱등)")
    void reorder_all_sameOrder_isNoOp() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, true, 1);
        givenRootSiblings(a, b);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ALL, 1L, 2L));

        assertNull(a.getUpdateDate());
        assertNull(b.getUpdateDate());
        assertEquals(Map.of(1L, 0, 2L, 1), ordByMenuNo(response));
    }

    @Test
    @DisplayName("ALL: 임의 깊이의 부모(자기 부모가 있는 메뉴) 아래에서도 동작한다")
    void reorder_all_worksUnderNestedParent() {
        Menu child1 = menu(11L, 10L, true, 0);
        Menu child2 = menu(12L, 10L, true, 1);
        given(menuRepository.existsById(10L)).willReturn(true);
        given(menuRepository.findSiblingRowsByUpMenuNo(10L)).willReturn(List.of(row(child1), row(child2)));
        given(menuRepository.findByIdForUpdate(11L)).willReturn(Optional.of(child1));
        given(menuRepository.findByIdForUpdate(12L)).willReturn(Optional.of(child2));

        MenuOrderResponse response = menuService.reorderMenus(request(10L, MenuOrderScope.ALL, 12L, 11L));

        assertEquals(10L, response.getUpMenuNo());
        assertEquals(1, child1.getOrd());
        assertEquals(0, child2.getOrd());
    }

    // ===================== 잠금 =====================

    @Test
    @DisplayName("잠금은 요청 순서가 아니라 menuNo 오름차순이고, 부모 행은 잠그지 않는다")
    void reorder_locksSiblingsInAscendingMenuNoOrder_notParent() {
        Menu c1 = menu(11L, 10L, true, 0);
        Menu c2 = menu(12L, 10L, true, 1);
        Menu c3 = menu(13L, 10L, true, 2);
        given(menuRepository.existsById(10L)).willReturn(true);
        given(menuRepository.findSiblingRowsByUpMenuNo(10L)).willReturn(List.of(row(c1), row(c2), row(c3)));
        given(menuRepository.findByIdForUpdate(11L)).willReturn(Optional.of(c1));
        given(menuRepository.findByIdForUpdate(12L)).willReturn(Optional.of(c2));
        given(menuRepository.findByIdForUpdate(13L)).willReturn(Optional.of(c3));

        menuService.reorderMenus(request(10L, MenuOrderScope.ALL, 13L, 11L, 12L));

        InOrder inOrder = inOrder(menuRepository);
        inOrder.verify(menuRepository).findByIdForUpdate(11L);
        inOrder.verify(menuRepository).findByIdForUpdate(12L);
        inOrder.verify(menuRepository).findByIdForUpdate(13L);
        verify(menuRepository, never()).findByIdForUpdate(10L);
    }

    @Test
    @DisplayName("형제는 엔티티 조회가 아니라 값 프로젝션으로 읽는다(잠금 전 엔티티 선로딩 없음)")
    void reorder_readsSnapshotAsProjection_notEntities() {
        Menu a = menu(1L, null, true, 0);
        givenRootSiblings(a);

        menuService.reorderMenus(request(null, MenuOrderScope.ALL, 1L));

        verify(menuRepository, never()).findAllByOrderByOrdAscMenuNoAsc();
        verify(menuRepository, never()).findAllByUseYnTrueOrderByOrdAscMenuNoAsc();
        verify(menuRepository, never()).findById(anyLong());
    }

    // ===================== 검증 실패 =====================

    @Test
    @DisplayName("부모 메뉴가 없으면 404이고 어떤 행도 잠그지 않는다")
    void reorder_parentNotFound_404() {
        given(menuRepository.existsById(99L)).willReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> menuService.reorderMenus(request(99L, MenuOrderScope.ALL, 1L)));

        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("요청에 중복 menuNo가 있으면 400이고 아무것도 읽거나 잠그지 않는다")
    void reorder_duplicateIds_400() {
        assertThrows(InvalidRequestException.class,
                () -> menuService.reorderMenus(request(null, MenuOrderScope.ALL, 1L, 1L, 2L)));

        verify(menuRepository, never()).findRootSiblingRows();
        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("ALL: 형제 누락·추가·타 부모의 id면 409이고 어떤 행도 잠그지 않는다")
    void reorder_all_setMismatch_409() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, true, 1);
        given(menuRepository.findRootSiblingRows()).willReturn(List.of(row(a), row(b)));

        assertThrows(ConflictException.class, () -> menuService.reorderMenus(request(null, MenuOrderScope.ALL, 1L))); // 누락
        assertThrows(ConflictException.class, () -> menuService.reorderMenus(request(null, MenuOrderScope.ALL, 1L, 2L, 3L))); // 추가(타 부모 id 포함)
        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("잠금 시점에 스냅샷의 형제가 사라졌으면 409")
    void reorder_siblingVanishedAtLock_409() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, true, 1);
        given(menuRepository.findRootSiblingRows()).willReturn(List.of(row(a), row(b)));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(a));
        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.empty());

        assertThrows(ConflictException.class, () -> menuService.reorderMenus(request(null, MenuOrderScope.ALL, 2L, 1L)));
        assertEquals(0, a.getOrd(), "실패 시 어떤 순서도 바뀌지 않는다");
    }

    // ===================== scope=ACTIVE =====================

    @Test
    @DisplayName("ACTIVE: 비활성 형제는 표시 순서상 제자리에 두고 활성 자리에만 요청 순서를 채운다")
    void reorder_active_keepsInactiveInPlace() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, false, 1);
        Menu c = menu(3L, null, true, 2);
        Menu d = menu(4L, null, true, 3);
        givenRootSiblings(a, b, c, d);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 4L, 1L, 3L));

        // F=[A,B(비활성),C,D], 요청 [D,A,C] → T=[D,B,A,C]
        assertEquals(Map.of(4L, 0, 2L, 1, 1L, 2, 3L, 3), ordByMenuNo(response));
        assertEquals(0, d.getOrd());
        assertEquals(1, b.getOrd());
        assertNull(b.getUpdateDate(), "제자리의 비활성 형제는 ord가 그대로라 갱신하지 않는다");
        assertEquals(2, a.getOrd());
        assertEquals(3, c.getOrd());
    }

    @Test
    @DisplayName("ACTIVE: 요청에 비활성 id가 있거나 활성 id가 빠지면 409이고 어떤 행도 잠그지 않는다")
    void reorder_active_setMismatch_409() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, false, 1);
        Menu c = menu(3L, null, true, 2);
        given(menuRepository.findRootSiblingRows()).willReturn(List.of(row(a), row(b), row(c)));

        assertThrows(ConflictException.class,
                () -> menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 1L, 2L, 3L))); // 비활성 포함
        assertThrows(ConflictException.class,
                () -> menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 1L))); // 활성 누락
        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("ACTIVE: 스냅샷엔 활성이었지만 잠근 최신 엔티티가 비활성이면 409이고 어떤 ord도 바뀌지 않는다")
    void reorder_active_recheckUnderLock_deactivatedMeanwhile_409() {
        Menu a = menu(1L, null, true, 0);
        Menu bSnapshotActive = menu(2L, null, true, 1);
        Menu bLockedInactive = menu(2L, null, false, 1); // 그사이 다른 관리자가 비활성화하고 커밋
        Menu c = menu(3L, null, true, 2);
        given(menuRepository.findRootSiblingRows()).willReturn(List.of(row(a), row(bSnapshotActive), row(c)));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(a));
        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(bLockedInactive));
        given(menuRepository.findByIdForUpdate(3L)).willReturn(Optional.of(c));

        assertThrows(ConflictException.class,
                () -> menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 3L, 2L, 1L)));

        assertEquals(0, a.getOrd());
        assertEquals(2, c.getOrd());
        assertNull(a.getUpdateDate());
        assertNull(c.getUpdateDate());
    }

    @Test
    @DisplayName("ACTIVE: 스냅샷엔 비활성이던 형제가 잠근 시점에 재활성화돼 있으면 409")
    void reorder_active_recheckUnderLock_reactivatedMeanwhile_409() {
        Menu a = menu(1L, null, true, 0);
        Menu bSnapshotInactive = menu(2L, null, false, 1);
        Menu bLockedActive = menu(2L, null, true, 1); // 그사이 재활성화
        given(menuRepository.findRootSiblingRows()).willReturn(List.of(row(a), row(bSnapshotInactive)));
        given(menuRepository.findByIdForUpdate(1L)).willReturn(Optional.of(a));
        given(menuRepository.findByIdForUpdate(2L)).willReturn(Optional.of(bLockedActive));

        assertThrows(ConflictException.class,
                () -> menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 1L)));

        assertNull(a.getUpdateDate());
    }

    @Test
    @DisplayName("ACTIVE: ord가 전부 null이어도 표시 순서는 menuNo 기준으로 결정적이다")
    void reorder_active_allNullOrd_deterministicByMenuNo() {
        Menu a = menu(1L, null, true, null);
        Menu b = menu(2L, null, false, null);
        Menu c = menu(3L, null, true, null);
        givenRootSiblings(a, b, c);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 3L, 1L));

        // F=[A,B,C](menuNo 순), 요청 [C,A] → T=[C,B,A]
        assertEquals(Map.of(3L, 0, 2L, 1, 1L, 2), ordByMenuNo(response));
    }

    @Test
    @DisplayName("ACTIVE: ord null은 표시 순서에서 맨 앞(조회 쿼리의 MariaDB NULL 정렬과 동일)")
    void reorder_active_nullOrdSortsFirst() {
        Menu a = menu(1L, null, true, 5);
        Menu b = menu(2L, null, false, null);
        Menu c = menu(3L, null, true, 2);
        givenRootSiblings(a, b, c);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 1L, 3L));

        // F=[B(null),C(2),A(5)], 요청 [A,C] → B는 제자리, 나머지 자리에 A,C → T=[B,A,C]
        assertEquals(Map.of(2L, 0, 1L, 1, 3L, 2), ordByMenuNo(response));
    }

    @Test
    @DisplayName("ACTIVE: 중복 ord는 menuNo로 결정된다")
    void reorder_active_duplicateOrd_tieBrokenByMenuNo() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, false, 0);
        Menu c = menu(3L, null, true, 0);
        givenRootSiblings(a, b, c);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 3L, 1L));

        // F=[A,B,C](동률→menuNo), 요청 [C,A] → T=[C,B,A]
        assertEquals(Map.of(3L, 0, 2L, 1, 1L, 2), ordByMenuNo(response));
    }

    @Test
    @DisplayName("응답 항목은 적용된 표시 순서(비활성 포함 전체)이고 ord는 0..n-1이다")
    void reorder_response_listsAllSiblingsInAppliedOrder() {
        Menu a = menu(1L, null, true, 0);
        Menu b = menu(2L, null, false, 1);
        Menu c = menu(3L, null, true, 2);
        givenRootSiblings(a, b, c);

        MenuOrderResponse response = menuService.reorderMenus(request(null, MenuOrderScope.ACTIVE, 3L, 1L));

        Function<MenuOrderResponse.Item, Long> id = MenuOrderResponse.Item::getMenuNo;
        assertEquals(List.of(3L, 2L, 1L), response.getMenus().stream().map(id).toList());
        assertEquals(List.of(0, 1, 2), response.getMenus().stream().map(MenuOrderResponse.Item::getOrd).toList());
    }
}
