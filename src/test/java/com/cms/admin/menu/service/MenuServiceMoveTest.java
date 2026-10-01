package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuMoveRequest;
import com.cms.admin.menu.dto.response.MenuMoveResponse;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 메뉴 부모 이동({@link MenuService#moveMenu}) 단위 테스트 — PLAN-menu-move.md.
 * 실제 잠금 직렬화·동시성은 MenuConcurrencyIntegrationTest가 검증한다(여기서는 호출 순서·검사 규칙만).
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceMoveTest {

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Mock
    MenuRepository menuRepository;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    MenuService menuService;

    private static Menu menu(long menuNo, Long upMenuNo, boolean useYn, int ord, MenuAccessRole accessRole) {
        return Menu.builder().menuNo(menuNo).menuName("메뉴" + menuNo).menuUrl("/m" + menuNo).menuIcon("icon" + menuNo)
                .menuDesc("설명" + menuNo).upMenuNo(upMenuNo).useYn(useYn).ord(ord).accessRole(accessRole).build();
    }

    private static Menu menu(long menuNo, Long upMenuNo, boolean useYn, int ord) {
        return menu(menuNo, upMenuNo, useYn, ord, MenuAccessRole.ALL);
    }

    private void lock(Menu... menus) {
        for (Menu m : menus) {
            given(menuRepository.findByIdForUpdate(m.getMenuNo())).willReturn(Optional.of(m));
        }
    }

    // ===================== 정상 이동 =====================

    @Test
    @DisplayName("하위 메뉴를 다른 최상위 메뉴 아래로 옮기면 맨 끝(max ord+1)에 배치되고 부모·ord·updateDate만 바뀐다")
    void move_childToOtherRoot_appendsAtEnd() {
        Menu target = menu(11L, 10L, true, 3);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(4);

        MenuMoveResponse response = menuService.moveMenu(11L, MenuMoveRequest.toParent(20L));

        assertEquals(20L, target.getUpMenuNo());
        assertEquals(5, target.getOrd());
        assertEquals(FIXED_NOW, target.getUpdateDate());
        // 다른 필드는 그대로
        assertEquals("메뉴11", target.getMenuName());
        assertEquals("/m11", target.getMenuUrl());
        assertEquals("icon11", target.getMenuIcon());
        assertEquals("설명11", target.getMenuDesc());
        assertEquals(true, target.getUseYn());
        assertEquals(MenuAccessRole.ALL, target.getAccessRole());
        assertEquals(11L, response.getMenuNo());
        assertEquals(20L, response.getUpMenuNo());
        assertEquals(5, response.getOrd());
        assertTrue(response.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("최상위 메뉴를 다른 최상위 메뉴 아래로 옮기고 새 부모에 형제가 없으면 ord는 0")
    void move_rootUnderRoot_firstChildGetsOrdZero() {
        Menu target = menu(30L, null, true, 2);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(30L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(null);

        menuService.moveMenu(30L, MenuMoveRequest.toParent(20L));

        assertEquals(20L, target.getUpMenuNo());
        assertEquals(0, target.getOrd());
    }

    @Test
    @DisplayName("하위 메뉴를 최상위로 승격하면 최상위 형제의 맨 끝에 배치되고 응답 upMenuNo는 null")
    void move_promoteToRoot_appendsAtEndOfRoots() {
        Menu target = menu(11L, 10L, true, 3);
        lock(target);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNoIsNull()).willReturn(4);

        MenuMoveResponse response = menuService.moveMenu(11L, MenuMoveRequest.toParent(null));

        assertNull(target.getUpMenuNo());
        assertEquals(5, target.getOrd());
        assertEquals(FIXED_NOW, target.getUpdateDate());
        assertNull(response.getUpMenuNo());
        assertTrue(response.getWarnings().isEmpty());
    }

    // ===================== 무변경(멱등) — 자식·깊이 검사보다 먼저 =====================

    @Test
    @DisplayName("이미 그 부모 아래면 무변경 200 — ord·updateDate를 유지하고 자식·max ord를 조회하지 않는다")
    void move_alreadyUnderParent_isNoOp() {
        Menu target = menu(11L, 20L, true, 3);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);

        MenuMoveResponse response = menuService.moveMenu(11L, MenuMoveRequest.toParent(20L));

        assertEquals(20L, target.getUpMenuNo());
        assertEquals(3, target.getOrd());
        assertNull(target.getUpdateDate());
        assertEquals(3, response.getOrd());
        assertTrue(response.getWarnings().isEmpty());
        verify(menuRepository, never()).existsByUpMenuNo(anyLong());
        verify(menuRepository, never()).findMaxOrdByUpMenuNo(anyLong());
    }

    @Test
    @DisplayName("자식이 있는 최상위 메뉴에 null(최상위 유지)을 보내도 무변경 200이다 — 자식 검사로 400이 되면 멱등이 깨진다")
    void move_rootWithChildren_toRoot_isNoOpNotRejected() {
        Menu target = menu(10L, null, true, 1);
        lock(target);

        MenuMoveResponse response = menuService.moveMenu(10L, MenuMoveRequest.toParent(null));

        assertEquals(1, response.getOrd());
        assertNull(target.getUpdateDate());
        verify(menuRepository, never()).existsByUpMenuNo(anyLong());
    }

    @Test
    @DisplayName("기존 생성 API로 만든 3단 메뉴에 현재 부모를 다시 지정해도 무변경 200 — 새 부모가 최상위가 아니어도 무변경이 먼저 판정된다")
    void move_legacyThirdLevel_sameParent_isNoOp() {
        Menu target = menu(50L, 40L, true, 0);
        Menu deepParent = menu(40L, 30L, true, 0); // 이미 하위 메뉴인 부모 — 3단 데이터
        lock(target, deepParent);

        MenuMoveResponse response = menuService.moveMenu(50L, MenuMoveRequest.toParent(40L));

        assertEquals(40L, response.getUpMenuNo());
        assertNull(target.getUpdateDate());
    }

    // ===================== 거부 =====================

    @Test
    @DisplayName("자기 자신 아래로는 이동할 수 없다(400) — 아무것도 잠그지 않는다")
    void move_underSelf_400_beforeAnyLock() {
        assertThrows(InvalidRequestException.class, () -> menuService.moveMenu(11L, MenuMoveRequest.toParent(11L)));

        verify(menuRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("이동 대상이 없으면 404")
    void move_targetNotFound_404() {
        given(menuRepository.findByIdForUpdate(11L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> menuService.moveMenu(11L, MenuMoveRequest.toParent(null)));
    }

    @Test
    @DisplayName("새 부모가 없으면 404")
    void move_newParentNotFound_404() {
        Menu target = menu(11L, 10L, true, 3);
        given(menuRepository.findByIdForUpdate(11L)).willReturn(Optional.of(target));
        given(menuRepository.findByIdForUpdate(20L)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> menuService.moveMenu(11L, MenuMoveRequest.toParent(20L)));
        assertEquals(10L, target.getUpMenuNo());
    }

    @Test
    @DisplayName("새 부모가 하위 메뉴이면 400(2단 초과) — 대상은 그대로")
    void move_newParentIsChild_400() {
        Menu target = menu(11L, 10L, true, 3);
        Menu newParent = menu(21L, 20L, true, 0); // 자기 부모가 있는 하위 메뉴
        lock(target, newParent);

        assertThrows(InvalidRequestException.class, () -> menuService.moveMenu(11L, MenuMoveRequest.toParent(21L)));

        assertEquals(10L, target.getUpMenuNo());
        assertNull(target.getUpdateDate());
        verify(menuRepository, never()).existsByUpMenuNo(anyLong());
    }

    @Test
    @DisplayName("자식이 있는 메뉴는 이동할 수 없다(400) — 비활성 자식도 포함하는 existsByUpMenuNo를 쓴다")
    void move_targetHasChildren_400_countsInactiveChildren() {
        Menu target = menu(30L, null, true, 2);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(30L)).willReturn(true);

        assertThrows(InvalidRequestException.class, () -> menuService.moveMenu(30L, MenuMoveRequest.toParent(20L)));

        assertNull(target.getUpMenuNo());
        verify(menuRepository, never()).existsByUpMenuNoAndUseYnTrue(anyLong());
        verify(menuRepository, never()).findMaxOrdByUpMenuNo(anyLong());
    }

    @Test
    @DisplayName("활성 메뉴를 비활성 부모 아래로 옮기면 400")
    void move_activeUnderInactiveParent_400() {
        Menu target = menu(11L, 10L, true, 3);
        Menu inactiveParent = menu(20L, null, false, 1);
        lock(target, inactiveParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);

        assertThrows(InvalidRequestException.class, () -> menuService.moveMenu(11L, MenuMoveRequest.toParent(20L)));

        assertEquals(10L, target.getUpMenuNo());
    }

    @Test
    @DisplayName("비활성 메뉴는 비활성 부모 아래로 옮길 수 있다")
    void move_inactiveUnderInactiveParent_allowed() {
        Menu target = menu(11L, 10L, false, 3);
        Menu inactiveParent = menu(20L, null, false, 1);
        lock(target, inactiveParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(null);

        menuService.moveMenu(11L, MenuMoveRequest.toParent(20L));

        assertEquals(20L, target.getUpMenuNo());
    }

    // ===================== 잠금 규약 =====================

    @Test
    @DisplayName("잠금은 두 id의 menuNo 오름차순 — 대상이 작으면 대상→새 부모")
    void lockOrder_targetSmaller_targetThenParent() {
        Menu target = menu(11L, 10L, true, 3);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(null);

        menuService.moveMenu(11L, MenuMoveRequest.toParent(20L));

        InOrder inOrder = inOrder(menuRepository);
        inOrder.verify(menuRepository).findByIdForUpdate(11L);
        inOrder.verify(menuRepository).findByIdForUpdate(20L);
    }

    @Test
    @DisplayName("잠금은 두 id의 menuNo 오름차순 — 새 부모가 작으면 새 부모→대상")
    void lockOrder_parentSmaller_parentThenTarget() {
        Menu target = menu(30L, null, true, 2);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(30L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(null);

        menuService.moveMenu(30L, MenuMoveRequest.toParent(20L));

        InOrder inOrder = inOrder(menuRepository);
        inOrder.verify(menuRepository).findByIdForUpdate(20L);
        inOrder.verify(menuRepository).findByIdForUpdate(30L);
    }

    @Test
    @DisplayName("승격이면 대상 하나만 잠그고, 원래 부모는 어떤 경우에도 잠그지 않는다")
    void lockOrder_promote_locksOnlyTarget_neverOldParent() {
        Menu target = menu(11L, 10L, true, 3);
        lock(target);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNoIsNull()).willReturn(null);

        menuService.moveMenu(11L, MenuMoveRequest.toParent(null));

        verify(menuRepository).findByIdForUpdate(11L);
        verify(menuRepository, never()).findByIdForUpdate(10L);
    }

    @Test
    @DisplayName("이동 중 원래 부모 행은 잠그지 않는다(하위 메뉴 이동)")
    void lock_neverLocksOldParent_onMoveUnderOtherParent() {
        Menu target = menu(11L, 10L, true, 3);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(null);

        menuService.moveMenu(11L, MenuMoveRequest.toParent(20L));

        verify(menuRepository, never()).findByIdForUpdate(10L);
    }

    @Test
    @DisplayName("잠금 읽기가 자식 존재·max ord 같은 비잠금 읽기보다 항상 먼저다(REPEATABLE READ 스냅샷이 잠금 이후에 고정되게)")
    void locks_precedeNonLockingReads() {
        Menu target = menu(11L, 10L, true, 3);
        Menu newParent = menu(20L, null, true, 1);
        lock(target, newParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(20L)).willReturn(4);

        menuService.moveMenu(11L, MenuMoveRequest.toParent(20L));

        InOrder inOrder = inOrder(menuRepository);
        inOrder.verify(menuRepository).findByIdForUpdate(11L);
        inOrder.verify(menuRepository).findByIdForUpdate(20L);
        inOrder.verify(menuRepository).existsByUpMenuNo(11L);
        inOrder.verify(menuRepository).findMaxOrdByUpMenuNo(20L);
        verify(menuRepository, never()).findById(anyLong());
    }

    // ===================== accessRole 불일치 거부(결정 9d) =====================

    @Test
    @DisplayName("공용 메뉴를 관리자 전용 부모 아래로 옮기면 400으로 거부하고 부모는 바뀌지 않는다")
    void reject_commonUnderAdminOnlyParent() {
        Menu target = menu(11L, 10L, true, 3, MenuAccessRole.ALL);
        Menu adminParent = menu(20L, null, true, 1, MenuAccessRole.ADMIN);
        lock(target, adminParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);

        assertThrows(InvalidRequestException.class, () -> menuService.moveMenu(11L, MenuMoveRequest.toParent(20L)));

        assertEquals(10L, target.getUpMenuNo());
    }

    @Test
    @DisplayName("거부 조건이 아니면(관리자 전용→관리자 전용, 관리자 전용→공용 부모, 승격) 이동되고 warnings는 항상 빈 배열")
    void warning_none_whenNotConflicting() {
        Menu adminTarget = menu(11L, 10L, true, 3, MenuAccessRole.ADMIN);
        Menu adminParent = menu(20L, null, true, 1, MenuAccessRole.ADMIN);
        Menu commonParent = menu(21L, null, true, 2, MenuAccessRole.ALL);
        lock(adminTarget, adminParent, commonParent);
        given(menuRepository.existsByUpMenuNo(11L)).willReturn(false);
        given(menuRepository.findMaxOrdByUpMenuNo(anyLong())).willReturn(null);
        given(menuRepository.findMaxOrdByUpMenuNoIsNull()).willReturn(null);

        assertTrue(menuService.moveMenu(11L, MenuMoveRequest.toParent(20L)).getWarnings().isEmpty()); // 전용 → 전용 부모
        assertTrue(menuService.moveMenu(11L, MenuMoveRequest.toParent(21L)).getWarnings().isEmpty()); // 전용 → 공용 부모
        assertTrue(menuService.moveMenu(11L, MenuMoveRequest.toParent(null)).getWarnings().isEmpty()); // 승격
    }
}
