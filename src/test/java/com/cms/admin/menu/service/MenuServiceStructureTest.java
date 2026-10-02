package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 메뉴 구조 일괄 반영({@link MenuService#applyStructure}) 단위 테스트 — PLAN-menu-structure-apply.md 결정 1~4.
 * 실제 잠금 직렬화·동시성은 MenuConcurrencyIntegrationTest가 검증한다(여기서는 검증 규칙·저장 범위만).
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceStructureTest {

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Mock
    MenuRepository menuRepository;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    MenuService menuService;

    private static Menu menu(long menuNo, Long upMenuNo, Integer ord) {
        return menu(menuNo, upMenuNo, ord, true, MenuAccessRole.ALL);
    }

    private static Menu menu(long menuNo, Long upMenuNo, Integer ord, boolean useYn, MenuAccessRole role) {
        return Menu.builder().menuNo(menuNo).menuName("메뉴" + menuNo).upMenuNo(upMenuNo).ord(ord)
                .useYn(useYn).accessRole(role).build();
    }

    /** 현재 상태 그대로의 항목(base = 현재, 새 부모 = 현재). */
    private static MenuStructureRequest.Item same(Menu menu) {
        return MenuStructureRequest.Item.of(menu.getMenuNo(), menu.getUpMenuNo(), menu.getOrd(), menu.getUpMenuNo());
    }

    /** base는 현재 값이고 새 부모만 다른 항목. */
    private static MenuStructureRequest.Item to(Menu menu, Long newParent) {
        return MenuStructureRequest.Item.of(menu.getMenuNo(), menu.getUpMenuNo(), menu.getOrd(), newParent);
    }

    private static MenuStructureRequest request(MenuStructureRequest.Item... items) {
        MenuStructureRequest request = new MenuStructureRequest();
        request.setMenus(new ArrayList<>(List.of(items)));
        return request;
    }

    private void db(Menu... menus) {
        given(menuRepository.findAllForUpdate()).willReturn(List.of(menus));
    }

    // ===================== 정상 =====================

    @Test
    @DisplayName("무변경 요청은 changed=0이고 어떤 행도 쓰지 않는다(updateDate·ord 보존)")
    void noChange_writesNothing() {
        Menu a = menu(1, null, 100);
        Menu b = menu(2, null, 200);
        Menu c = menu(3, 1L, 5);
        db(a, b, c);

        MenuStructureResponse response = menuService.applyStructure(request(same(a), same(b), same(c)));

        assertEquals(0, response.getChanged());
        assertEquals(100, a.getOrd());
        assertEquals(200, b.getOrd());
        assertNull(a.getUpdateDate());
        verify(menuRepository).findAllForUpdate();
        verifyNoMoreInteractions(menuRepository);
    }

    @Test
    @DisplayName("같은 부모 안 재정렬: 요청 순서대로 0..n-1로 다시 매기고 바뀐 행만 쓴다")
    void reorderWithinGroup() {
        Menu a = menu(1, null, 0);
        Menu b = menu(2, null, 1);
        Menu c = menu(3, null, 2);
        db(a, b, c);

        MenuStructureResponse response = menuService.applyStructure(request(same(c), same(a), same(b)));

        assertEquals(0, c.getOrd());
        assertEquals(1, a.getOrd());
        assertEquals(2, b.getOrd());
        assertEquals(3, response.getChanged());
        assertEquals(FIXED_NOW, c.getUpdateDate());
    }

    @Test
    @DisplayName("다른 부모로 이동: 드롭한 위치(index)에 배치되고 새 그룹의 나머지 형제가 밀린다")
    void moveToOtherParent_atDroppedPosition() {
        Menu r1 = menu(1, null, 0);
        Menu r2 = menu(2, null, 1);
        Menu x = menu(3, 1L, 0);
        Menu y = menu(4, 2L, 0);
        db(r1, r2, x, y);

        MenuStructureResponse response = menuService.applyStructure(request(
                same(r1), same(r2), to(x, 2L), same(y)));

        // R2의 자식 순서: [x, y] — 요청 배열에서 x가 y보다 앞
        assertEquals(2L, x.getUpMenuNo());
        assertEquals(0, x.getOrd());
        assertEquals(1, y.getOrd());
        assertEquals(2, response.getChanged());
    }

    @Test
    @DisplayName("서브트리째 이동: 하위 메뉴가 있는 메뉴를 다른 최상위 아래로 옮겨도 자식은 그대로 따라간다(결과 3단)")
    void moveSubtree_childrenFollow() {
        Menu r1 = menu(1, null, 0);
        Menu r2 = menu(2, null, 1);
        Menu x = menu(3, 1L, 0);
        Menu z = menu(4, 3L, 0);
        db(r1, r2, x, z);

        menuService.applyStructure(request(same(r1), same(r2), to(x, 2L), same(z)));

        assertEquals(2L, x.getUpMenuNo());
        assertEquals(3L, z.getUpMenuNo());
    }

    // ===================== 거부: 구조 =====================

    @Test
    @DisplayName("최종 깊이가 3단을 넘으면 400(이동된 서브트리 기준)")
    void depthOverThree_rejected() {
        Menu r1 = menu(1, null, 0);
        Menu r2 = menu(2, null, 1);
        Menu x = menu(3, 1L, 0);
        Menu z = menu(4, 3L, 0);
        db(r1, r2, x, z);

        // r1(자식 x, 손자 z)을 r2 아래로 → r1=2단, x=3단, z=4단
        assertThrows(InvalidRequestException.class, () ->
                menuService.applyStructure(request(to(r1, 2L), same(r2), same(x), same(z))));
        assertNull(r1.getUpdateDate());
    }

    @Test
    @DisplayName("자기 자신이나 자기 자손 아래로 옮기면(순환) 400")
    void cycle_rejected() {
        Menu a = menu(1, null, 0);
        Menu b = menu(2, 1L, 0);
        db(a, b);

        assertThrows(InvalidRequestException.class, () ->
                menuService.applyStructure(request(to(a, 2L), same(b)))); // a를 자기 자식 b 아래로
        assertThrows(InvalidRequestException.class, () ->
                menuService.applyStructure(request(to(a, 1L), same(b)))); // a를 자기 자신 아래로
    }

    @Test
    @DisplayName("활성 메뉴를 비활성 부모 아래로 옮기면 400, 비활성 메뉴는 허용")
    void activeUnderInactiveParent_rejected() {
        Menu inactiveRoot = menu(1, null, 0, false, MenuAccessRole.ALL);
        Menu activeRoot = menu(2, null, 1);
        Menu activeChild = menu(3, 2L, 0);
        Menu inactiveChild = menu(4, 2L, 1, false, MenuAccessRole.ALL);
        db(inactiveRoot, activeRoot, activeChild, inactiveChild);

        assertThrows(InvalidRequestException.class, () -> menuService.applyStructure(request(
                same(inactiveRoot), same(activeRoot), to(activeChild, 1L), same(inactiveChild))));

        MenuStructureResponse ok = menuService.applyStructure(request(
                same(inactiveRoot), same(activeRoot), same(activeChild), to(inactiveChild, 1L)));
        assertEquals(1L, inactiveChild.getUpMenuNo());
        assertEquals(1, ok.getChanged());
    }

    @Test
    @DisplayName("공용 메뉴를 관리자 전용 조상 아래로 옮기면 400, 관리자 전용 메뉴는 허용")
    void commonUnderAdminOnly_rejected() {
        Menu adminRoot = menu(1, null, 0, true, MenuAccessRole.ADMIN);
        Menu commonRoot = menu(2, null, 1);
        Menu commonChild = menu(3, 2L, 0);
        Menu adminChild = menu(4, 2L, 1, true, MenuAccessRole.ADMIN);
        db(adminRoot, commonRoot, commonChild, adminChild);

        assertThrows(InvalidRequestException.class, () -> menuService.applyStructure(request(
                same(adminRoot), same(commonRoot), to(commonChild, 1L), same(adminChild))));

        menuService.applyStructure(request(
                same(adminRoot), same(commonRoot), same(commonChild), to(adminChild, 1L)));
        assertEquals(1L, adminChild.getUpMenuNo());
    }

    @Test
    @DisplayName("요청에 없는 메뉴를 부모로 지정하면 400")
    void parentNotInRequest_rejected() {
        Menu a = menu(1, null, 0);
        Menu b = menu(2, null, 1);
        db(a, b);

        assertThrows(InvalidRequestException.class, () ->
                menuService.applyStructure(request(same(a), to(b, 99L))));
    }

    @Test
    @DisplayName("같은 메뉴 번호가 두 번 오면 400")
    void duplicateMenuNo_rejected() {
        Menu a = menu(1, null, 0);

        assertThrows(InvalidRequestException.class, () -> menuService.applyStructure(request(same(a), same(a))));
    }

    // ===================== 거부: 낡은 초안(409) =====================

    @Test
    @DisplayName("현재 부모 또는 ord가 base와 다르면 409(낡은 초안)이고 아무것도 바뀌지 않는다")
    void staleBase_conflict() {
        Menu a = menu(1, null, 0);
        Menu b = menu(2, null, 1);
        db(a, b);

        // 다른 관리자가 b의 ord를 바꾼 뒤라 base ord(5)가 현재(1)와 다르다
        MenuStructureRequest.Item staleOrd = MenuStructureRequest.Item.of(2L, null, 5, null);
        assertThrows(ConflictException.class, () -> menuService.applyStructure(request(same(a), staleOrd)));
        // 다른 관리자가 b를 a 아래로 옮긴 뒤라 base 부모(1)가 현재(null)와 다르다
        MenuStructureRequest.Item staleParent = MenuStructureRequest.Item.of(2L, 1L, 1, null);
        assertThrows(ConflictException.class, () -> menuService.applyStructure(request(same(a), staleParent)));
        assertEquals(1, b.getOrd());
        assertNull(b.getUpdateDate());
    }

    @Test
    @DisplayName("요청 집합이 현재 메뉴 집합과 다르면(다른 관리자가 생성·삭제) 409")
    void differentSet_conflict() {
        Menu a = menu(1, null, 0);
        Menu b = menu(2, null, 1);
        Menu c = menu(3, null, 2);
        db(a, b, c);

        assertThrows(ConflictException.class, () -> menuService.applyStructure(request(same(a), same(b)))); // c 누락
        assertThrows(ConflictException.class, () -> menuService.applyStructure(
                request(same(a), same(b), MenuStructureRequest.Item.of(99L, null, 2, null)))); // c 대신 낯선 번호
    }

    @Test
    @DisplayName("요청 메뉴 수가 전체 메뉴 수보다 많아도(다른 관리자가 영구삭제한 낡은 초안) 400이 아니라 409")
    void moreThanAll_isStaleDraftConflict() {
        Menu a = menu(1, null, 0);
        db(a);

        assertThrows(ConflictException.class, () -> menuService.applyStructure(
                request(same(a), MenuStructureRequest.Item.of(2L, null, 1, null))));
    }

    // ===================== 기존 데이터(위반·고아) 호환 =====================

    @Test
    @DisplayName("이동하지 않은 서브트리의 기존 위반(4단·권한 불일치)은 다른 가지의 재정렬을 막지 않는다")
    void existingViolations_doNotBlockUnrelatedReorder() {
        Menu adminRoot = menu(1, null, 0, true, MenuAccessRole.ADMIN);
        Menu commonUnderAdmin = menu(2, 1L, 0); // 기존 권한 불일치
        Menu l3 = menu(3, 2L, 0);
        Menu l4 = menu(4, 3L, 0); // 기존 4단
        Menu otherRoot = menu(5, null, 1);
        db(adminRoot, commonUnderAdmin, l3, l4, otherRoot);

        MenuStructureResponse response = menuService.applyStructure(request(
                same(otherRoot), same(adminRoot), same(commonUnderAdmin), same(l3), same(l4))); // 루트 순서만 맞바꿈

        assertEquals(2, response.getChanged());
        assertEquals(0, otherRoot.getOrd());
        assertEquals(0, commonUnderAdmin.getOrd());
        assertNull(commonUnderAdmin.getUpdateDate());
    }

    @Test
    @DisplayName("순서·구성이 그대로인 그룹은 ord가 100·200이어도 다시 매기지 않는다")
    void untouchedGroup_keepsOddOrd() {
        Menu r1 = menu(1, null, 0);
        Menu r2 = menu(2, null, 1);
        Menu a = menu(3, 1L, 100);
        Menu b = menu(4, 1L, 200);
        db(r1, r2, a, b);

        menuService.applyStructure(request(same(r2), same(r1), same(a), same(b))); // 루트만 맞바꿈

        assertEquals(100, a.getOrd());
        assertEquals(200, b.getOrd());
        assertNull(a.getUpdateDate());
    }

    @Test
    @DisplayName("트리에서 도달할 수 없는 고아 행은 요청에 없어도 409가 아니고 건드리지 않는다")
    void orphan_ignored() {
        Menu a = menu(1, null, 0);
        Menu orphan = menu(5, 999L, 0); // 존재하지 않는 부모를 가리키는 행
        db(a, orphan);

        MenuStructureResponse response = menuService.applyStructure(request(same(a)));

        assertEquals(0, response.getChanged());
        assertEquals(999L, orphan.getUpMenuNo());
    }

    @Test
    @DisplayName("고아 행을 부모로 지정하면(요청에 없는 메뉴) 400")
    void orphanAsParent_rejected() {
        Menu a = menu(1, null, 0);
        Menu orphan = menu(5, 999L, 0);
        db(a, orphan);

        assertThrows(InvalidRequestException.class, () -> menuService.applyStructure(request(to(a, 5L))));
    }
}
