package com.cms.admin.menu.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class MenuService {

    /** 메뉴 최대 깊이(최상위=1). 사이드바 렌더링과 생성·구조 반영 검증이 같은 상한을 쓴다. */
    static final int MAX_MENU_DEPTH = 3;

    /** 조회 쿼리(order by ord asc, menu_no asc)와 같은 표시 순서 — MariaDB는 NULL을 가장 작게 본다. */
    private static final Comparator<Menu> DISPLAY_ORDER =
            Comparator.comparing(Menu::getOrd, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
                    .thenComparing(Menu::getMenuNo);

    private final MenuRepository menuRepository;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_CREATE, targetType = "MENU", targetIdExpression = "menuNo")
    public MenuResponse createMenu(MenuCreateRequest request) {
        String menuName = requireNonBlank(request.getMenuName(), "메뉴명은 공백일 수 없습니다.");
        boolean useYn = request.getUseYn() == null || request.getUseYn();
        MenuAccessRole accessRole = request.getAccessRole() != null ? request.getAccessRole() : MenuAccessRole.ALL;

        // 깊이·권한 불변식 검사가 조상 사슬을 읽고 ord 오버플로 보정이 형제를 다시 매기므로 전체 행 잠금을 첫 조회로 쓴다
        // (PLAN-menu-structure-apply.md 결정 9c — 구조 반영·accessRole 수정과 같은 순서로 잠가 직렬화된다).
        List<Menu> allMenus = menuRepository.findAllForUpdate();

        Long upMenuNo = request.getUpMenuNo();
        if (upMenuNo != null) {
            Menu parent = menuRepository.findByIdForUpdate(upMenuNo)
                    .orElseThrow(() -> new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다."));
            if (useYn && !Boolean.TRUE.equals(parent.getUseYn())) {
                throw new InvalidRequestException("비활성 부모 메뉴 아래에는 활성 메뉴를 생성할 수 없습니다.");
            }
            ParentChain chain = inspectChain(parent);
            if (chain.depth() >= MAX_MENU_DEPTH) {
                throw new InvalidRequestException("메뉴는 최대 " + MAX_MENU_DEPTH + "단까지만 만들 수 있습니다.");
            }
            if (accessRole == MenuAccessRole.ALL && chain.hasAdminOnly()) {
                throw new InvalidRequestException("관리자 전용 메뉴 아래에는 공용 메뉴를 만들 수 없습니다.");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Integer ord = resolveNextOrdForCreate(upMenuNo, allMenus, now);

        Menu saved = menuRepository.save(
                Menu.builder()
                        .menuName(menuName)
                        .menuUrl(request.getMenuUrl())
                        .menuIcon(request.getMenuIcon())
                        .menuDesc(request.getMenuDesc())
                        .useYn(useYn)
                        .accessRole(accessRole)
                        .ord(ord)
                        .upMenuNo(upMenuNo)
                        .createDate(now)
                        .updateDate(now)
                        .build()
        );

        return MenuResponse.from(saved);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_UPDATE, targetType = "MENU", targetIdExpression = "menuNo")
    public MenuResponse updateMenu(Long menuNo, MenuUpdateRequest request) {
        // 일반 수정(이름 등)만 잠금 없는 조회를 쓰면, "일반 수정 조회 → 비활성화 커밋 → 일반
        // 수정 커밋" 순서로 겹칠 때 방금 커밋된 비활성화가 되돌아가는 lost update가 발생한다
        // (감사 M-04, adversarial-review/remediation-plan.md PR 4 참조). 어떤 수정이든 최초
        // 조회부터 비관적 락(PESSIMISTIC_WRITE)으로 대상 row를 잠근다 — deactivateMenu()와
        // 동일하게, 아래 활성 하위 메뉴 검사와 상태 반영이 동시 createMenu(활성 자식)와도 직렬화된다.
        // accessRole이 요청에 있으면 조상·자손의 권한 불변식을 읽으므로 전체 행 잠금을 첫 조회로 쓴다(결정 9c).
        boolean touchesRole = request.getAccessRole() != null;
        List<Menu> allMenus = touchesRole ? menuRepository.findAllForUpdate() : List.of();

        Menu target = menuRepository.findByIdForUpdate(menuNo)
                .orElseThrow(() -> new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        String effectiveMenuName = target.getMenuName();
        if (request.getMenuName() != null) {
            effectiveMenuName = requireNonBlank(request.getMenuName(), "메뉴명은 공백일 수 없습니다.");
        }

        String effectiveMenuUrl = request.getMenuUrl() != null ? request.getMenuUrl() : target.getMenuUrl();
        String effectiveMenuIcon = request.getMenuIcon() != null ? request.getMenuIcon() : target.getMenuIcon();
        String effectiveMenuDesc = request.getMenuDesc() != null ? request.getMenuDesc() : target.getMenuDesc();
        MenuAccessRole effectiveAccessRole = request.getAccessRole() != null ? request.getAccessRole() : target.getAccessRole();

        if (touchesRole && effectiveAccessRole != roleOf(target)) {
            validateRoleChange(target, effectiveAccessRole, allMenus);
        }

        boolean wasActive = Boolean.TRUE.equals(target.getUseYn());
        boolean effectiveUseYn = request.getUseYn() != null ? request.getUseYn() : target.getUseYn();
        boolean reactivating = !wasActive && effectiveUseYn;
        boolean deactivating = wasActive && !effectiveUseYn;

        if (reactivating && target.getUpMenuNo() != null) {
            Menu parent = menuRepository.findByIdForUpdate(target.getUpMenuNo())
                    .orElseThrow(() -> new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다."));
            if (!Boolean.TRUE.equals(parent.getUseYn())) {
                throw new InvalidRequestException("비활성 부모 메뉴 아래로는 재활성화할 수 없습니다.");
            }
        }

        // 활성 하위 메뉴가 있는 부모를 비활성화하면 활성 자식이 비활성 부모 아래 고아로 남는다.
        // deactivateMenu()와 동일한 불변식을 PATCH 경로에도 강제한다. (위에서 대상 row를 이미 잠갔다.)
        if (deactivating && menuRepository.existsByUpMenuNoAndUseYnTrue(menuNo)) {
            throw new ConflictException("활성 하위 메뉴가 있어 비활성화할 수 없습니다.");
        }

        target.update(effectiveMenuName, effectiveMenuUrl, effectiveMenuIcon, effectiveMenuDesc,
                effectiveUseYn, effectiveAccessRole, target.getOrd(), LocalDateTime.now(clock));

        return MenuResponse.from(target);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_DEACTIVATE, targetType = "MENU", targetIdExpression = "menuNo")
    public MenuResponse deactivateMenu(Long menuNo) {
        Menu target = menuRepository.findByIdForUpdate(menuNo)
                .orElseThrow(() -> new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        if (menuRepository.existsByUpMenuNoAndUseYnTrue(menuNo)) {
            throw new ConflictException("활성 하위 메뉴가 있어 비활성화할 수 없습니다.");
        }

        target.deactivate(LocalDateTime.now(clock));

        return MenuResponse.from(target);
    }

    /**
     * 메뉴 구조(부모·순서)를 일괄 반영한다(PLAN-menu-structure-apply.md). 화면이 드래그로 만든 초안(최종 트리)을 한 번에
     * 받아 전체 행을 잠그고 검증한 뒤 저장한다.
     *
     * <p>잠금: 첫 애플리케이션 테이블 조회로 전체 메뉴를 menuNo 오름차순 PESSIMISTIC_WRITE로 읽는다(결정 2). 구조·권한
     * 불변식을 바꾸는 경로(구조 반영·생성·accessRole 수정)가 같은 순서로 전체를 잠가 서로 직렬화된다.
     *
     * <p>낙관적 검사(결정 3): 요청 집합이 현재 <b>트리 도달 가능 집합</b>과 다르거나 어떤 행의 현재 (부모, ord)가
     * 요청의 base와 다르면 409. 고아·루트 없는 순환 행(트리 응답에서 제외되는 행)은 비교에서 빼고 잠금만 한다.
     *
     * <p>검증(결정 4): 부모가 바뀐 메뉴와 그 서브트리에만 깊이(≤3)·활성/비활성 부모·권한 불일치 규칙을 적용한다 —
     * 이동하지 않은 서브트리의 기존 위반은 이 반영을 막지 않는다. 순환은 전체에서 검사한다. 저장: 현재 표시 순서와
     * 요청 순서가 다른 부모 그룹만 0..n-1로 다시 매기고 값이 같은 행은 쓰지 않는다(updateDate 보존).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_STRUCTURE_APPLY, targetType = "MENU")
    public MenuStructureResponse applyStructure(MenuStructureRequest request) {
        List<MenuStructureRequest.Item> items = request.getMenus();

        Set<Long> requestedNos = new LinkedHashSet<>();
        for (MenuStructureRequest.Item item : items) {
            if (!requestedNos.add(item.resolvedMenuNo())) {
                throw new InvalidRequestException("메뉴 번호가 중복되었습니다.");
            }
        }

        // 이 트랜잭션의 첫 애플리케이션 테이블 조회가 잠금 읽기여야 한다(REPEATABLE READ 스냅샷이 일찍 고정되는 것 방지).
        List<Menu> all = menuRepository.findAllForUpdate();
        if (items.size() > all.size()) {
            throw new InvalidRequestException("요청한 메뉴 수가 전체 메뉴 수보다 많습니다.");
        }
        Map<Long, Menu> byNo = new LinkedHashMap<>();
        for (Menu menu : all) {
            byNo.put(menu.getMenuNo(), menu);
        }

        if (!reachableMenuNos(all).equals(requestedNos)) {
            throw structureChanged();
        }
        for (MenuStructureRequest.Item item : items) {
            Menu current = byNo.get(item.resolvedMenuNo());
            if (!Objects.equals(current.getUpMenuNo(), item.resolvedBaseUpMenuNo())
                    || !Objects.equals(current.getOrd(), item.resolvedBaseOrd())) {
                throw structureChanged();
            }
        }

        // 최종 구조: 메뉴 → 새 부모, 부모 → 새 자식 목록(요청 배열 순서)
        Map<Long, Long> finalParent = new HashMap<>();
        Map<Long, List<Long>> finalChildren = new LinkedHashMap<>();
        for (MenuStructureRequest.Item item : items) {
            Long up = item.resolvedUpMenuNo();
            if (up != null && !requestedNos.contains(up)) {
                throw new InvalidRequestException("부모 메뉴가 요청 구조에 없습니다. menuNo=" + item.resolvedMenuNo());
            }
            finalParent.put(item.resolvedMenuNo(), up);
            finalChildren.computeIfAbsent(up, key -> new ArrayList<>()).add(item.resolvedMenuNo());
        }

        Map<Long, Integer> depth = new HashMap<>();
        for (Long menuNo : requestedNos) {
            depth.put(menuNo, finalDepth(menuNo, finalParent, requestedNos.size()));
        }

        // 부모가 바뀐 메뉴의 서브트리(최종 구조 기준)만 규칙을 검사한다.
        Set<Long> affected = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (Long menuNo : requestedNos) {
            if (!Objects.equals(finalParent.get(menuNo), byNo.get(menuNo).getUpMenuNo())) {
                affected.add(menuNo);
                queue.add(menuNo);
            }
        }
        while (!queue.isEmpty()) {
            for (Long child : finalChildren.getOrDefault(queue.poll(), List.of())) {
                if (affected.add(child)) {
                    queue.add(child);
                }
            }
        }
        for (Long menuNo : affected) {
            validateFinalPlacement(byNo.get(menuNo), finalParent, depth, byNo);
        }

        // 현재 부모 그룹별 표시 순서(변경 전 상태). 요청 순서와 같은 그룹은 건드리지 않는다.
        Map<Long, List<Long>> currentOrder = new HashMap<>();
        byNo.values().stream()
                .filter(menu -> requestedNos.contains(menu.getMenuNo()))
                .sorted(DISPLAY_ORDER)
                .forEach(menu -> currentOrder.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu.getMenuNo()));

        LocalDateTime now = LocalDateTime.now(clock);
        int changed = 0;
        for (Map.Entry<Long, List<Long>> group : finalChildren.entrySet()) {
            Long up = group.getKey();
            List<Long> ordered = group.getValue();
            if (ordered.equals(currentOrder.getOrDefault(up, List.of()))) {
                continue;
            }
            for (int index = 0; index < ordered.size(); index++) {
                Menu menu = byNo.get(ordered.get(index));
                if (!Objects.equals(menu.getUpMenuNo(), up)) {
                    menu.changeParent(up, index, now);
                    changed++;
                } else if (!Integer.valueOf(index).equals(menu.getOrd())) {
                    menu.changeOrd(index, now);
                    changed++;
                }
            }
        }
        return MenuStructureResponse.builder().changed(changed).build();
    }

    /** 루트(upMenuNo=null)에서 부모→자식 관계로 도달 가능한 메뉴 번호. 고아·루트 없는 순환 행은 포함되지 않는다. */
    private Set<Long> reachableMenuNos(List<Menu> all) {
        Map<Long, List<Menu>> childrenByParent = new HashMap<>();
        for (Menu menu : all) {
            childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
        }
        Set<Long> reachable = new LinkedHashSet<>();
        // ArrayDeque는 null 원소를 허용하지 않으므로 최상위(upMenuNo=null) 그룹은 큐 밖에서 먼저 시작한다.
        Deque<Long> queue = new ArrayDeque<>();
        for (Menu root : childrenByParent.getOrDefault(null, List.of())) {
            if (reachable.add(root.getMenuNo())) {
                queue.add(root.getMenuNo());
            }
        }
        while (!queue.isEmpty()) {
            for (Menu child : childrenByParent.getOrDefault(queue.poll(), List.of())) {
                if (reachable.add(child.getMenuNo())) {
                    queue.add(child.getMenuNo());
                }
            }
        }
        return reachable;
    }

    /** 최종 구조에서 메뉴의 깊이(최상위=1). 부모 사슬이 메뉴 수를 넘게 이어지면 순환이다. */
    private int finalDepth(Long menuNo, Map<Long, Long> finalParent, int menuCount) {
        int depth = 1;
        Long up = finalParent.get(menuNo);
        while (up != null) {
            depth++;
            if (depth > menuCount + 1) {
                throw new InvalidRequestException("메뉴 구조에 순환이 있습니다. menuNo=" + menuNo);
            }
            up = finalParent.get(up);
        }
        return depth;
    }

    /** 부모가 바뀐 서브트리의 메뉴 한 건이 최종 구조에서 깊이·활성 상태·권한 규칙을 지키는지 검사한다. */
    private void validateFinalPlacement(Menu menu, Map<Long, Long> finalParent, Map<Long, Integer> depth, Map<Long, Menu> byNo) {
        if (depth.get(menu.getMenuNo()) > MAX_MENU_DEPTH) {
            throw new InvalidRequestException("메뉴는 최대 " + MAX_MENU_DEPTH + "단까지만 만들 수 있습니다. 메뉴: " + menu.getMenuName());
        }
        Long parentNo = finalParent.get(menu.getMenuNo());
        if (parentNo == null) {
            return;
        }
        if (Boolean.TRUE.equals(menu.getUseYn()) && !Boolean.TRUE.equals(byNo.get(parentNo).getUseYn())) {
            throw new InvalidRequestException("비활성 상위 메뉴 아래에는 활성 메뉴를 둘 수 없습니다. 메뉴: " + menu.getMenuName());
        }
        if (roleOf(menu) == MenuAccessRole.ALL) {
            for (Long up = parentNo; up != null; up = finalParent.get(up)) {
                if (roleOf(byNo.get(up)) == MenuAccessRole.ADMIN) {
                    throw new InvalidRequestException("관리자 전용 메뉴 아래에는 공용 메뉴를 둘 수 없습니다. 메뉴: " + menu.getMenuName());
                }
            }
        }
    }

    private ConflictException structureChanged() {
        return new ConflictException("메뉴 구조가 변경되었습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.");
    }

    @Transactional(readOnly = true)
    public MenuResponse getMenu(Long menuNo) {
        Menu menu = menuRepository.findById(menuNo)
                .orElseThrow(() -> new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        return MenuResponse.from(menu);
    }

    @Transactional(readOnly = true)
    public List<MenuTreeResponse> getMenuTree(String useYnFilter) {
        if (!"true".equals(useYnFilter) && !"all".equals(useYnFilter)) {
            throw new InvalidRequestException("useYn 파라미터는 true 또는 all만 허용됩니다.");
        }

        List<Menu> menus = "all".equals(useYnFilter)
                ? menuRepository.findAllByOrderByOrdAscMenuNoAsc()
                : menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc();

        return assembleTree(menus);
    }

    /**
     * 사이드바 렌더링용 메뉴 목록. 활성(useYn=true) 메뉴만 대상으로,
     * ADMIN 권한이 없으면 ADMIN 전용 메뉴를 제외한다.
     *
     * <p>최대 {@value #MAX_MENU_DEPTH}단(최상위 + 하위 + 하위의 하위)까지 조립한다.
     * 그보다 깊은 메뉴와, 부모가 노출 대상에서 빠진 하위 메뉴는 렌더링되지 않는다.
     */
    @Transactional(readOnly = true)
    public List<SidebarMenuResponse> getSidebarMenus(boolean isAdmin) {
        List<Menu> menus = menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc().stream()
                .filter(menu -> isAdmin || menu.getAccessRole() != MenuAccessRole.ADMIN)
                .toList();

        Map<Long, List<Menu>> childrenByParent = new LinkedHashMap<>();
        for (Menu menu : menus) {
            childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
        }

        return childrenByParent.getOrDefault(null, List.of()).stream()
                .map(root -> toSidebarNode(root, 1, childrenByParent))
                .toList();
    }

    private SidebarMenuResponse toSidebarNode(Menu menu, int depth, Map<Long, List<Menu>> childrenByParent) {
        List<SidebarMenuResponse> children = depth >= MAX_MENU_DEPTH
                ? List.of()
                : childrenByParent.getOrDefault(menu.getMenuNo(), List.of()).stream()
                        .map(child -> toSidebarNode(child, depth + 1, childrenByParent))
                        .toList();
        return SidebarMenuResponse.of(menu, children);
    }

    /** 조상 사슬 조사 결과. depth는 대상 메뉴 자신의 깊이(최상위=1), hasAdminOnly는 자신 또는 조상에 ADMIN 전용이 있는지. */
    private record ParentChain(int depth, boolean hasAdminOnly) {}

    /** 메뉴 자신에서 최상위까지 올라가며 깊이와 ADMIN 전용 존재 여부를 구한다. 순환 데이터는 한 번 본 노드에서 멈춘다. */
    private ParentChain inspectChain(Menu menu) {
        int depth = 1;
        boolean hasAdminOnly = roleOf(menu) == MenuAccessRole.ADMIN;
        Set<Long> visited = new HashSet<>();
        visited.add(menu.getMenuNo());
        Long upMenuNo = menu.getUpMenuNo();
        while (upMenuNo != null && visited.add(upMenuNo)) {
            depth++;
            Menu ancestor = menuRepository.findById(upMenuNo).orElse(null);
            if (ancestor == null) {
                break;
            }
            hasAdminOnly |= roleOf(ancestor) == MenuAccessRole.ADMIN;
            upMenuNo = ancestor.getUpMenuNo();
        }
        return new ParentChain(depth, hasAdminOnly);
    }

    /** accessRole이 null인 레거시 행은 공용(ALL)으로 간주한다. */
    private static MenuAccessRole roleOf(Menu menu) {
        return menu.getAccessRole() == null ? MenuAccessRole.ALL : menu.getAccessRole();
    }

    /**
     * accessRole 변경이 권한 불변식(ALL 메뉴의 조상 사슬에 ADMIN 전용이 없어야 한다)을 깨는지 검사한다.
     * ADMIN→ALL이면 자기 조상에 ADMIN 전용이 없어야 하고, ALL→ADMIN이면 자기 서브트리에 ALL 자손이 없어야 한다.
     */
    private void validateRoleChange(Menu target, MenuAccessRole newRole, List<Menu> allMenus) {
        if (newRole == MenuAccessRole.ALL) {
            if (target.getUpMenuNo() != null) {
                Menu parent = menuRepository.findById(target.getUpMenuNo()).orElse(null);
                if (parent != null && inspectChain(parent).hasAdminOnly()) {
                    throw new InvalidRequestException("관리자 전용 메뉴 아래의 메뉴는 공용으로 바꿀 수 없습니다.");
                }
            }
            return;
        }
        Map<Long, List<Menu>> childrenByParent = new HashMap<>();
        for (Menu menu : allMenus) {
            childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(target.getMenuNo());
        visited.add(target.getMenuNo());
        while (!queue.isEmpty()) {
            for (Menu child : childrenByParent.getOrDefault(queue.poll(), List.of())) {
                if (!visited.add(child.getMenuNo())) {
                    continue;
                }
                if (roleOf(child) == MenuAccessRole.ALL) {
                    throw new InvalidRequestException("공용 하위 메뉴가 있어 관리자 전용으로 바꿀 수 없습니다. 하위 메뉴를 먼저 관리자 전용으로 바꾸세요.");
                }
                queue.add(child.getMenuNo());
            }
        }
    }

    /**
     * 생성용 다음 ord. 같은 부모 그룹의 최대 ord가 Integer.MAX_VALUE면 +1이 음수로 넘쳐 새 메뉴가 맨 앞에 놓이므로,
     * 그 경우에만 그룹을 현재 표시 순서 그대로 0..n-1로 재번호한 뒤 n을 돌려준다(순서는 바뀌지 않는다 — 결정 9e).
     */
    private Integer resolveNextOrdForCreate(Long upMenuNo, List<Menu> allMenus, LocalDateTime now) {
        Integer maxOrd = upMenuNo == null
                ? menuRepository.findMaxOrdByUpMenuNoIsNull()
                : menuRepository.findMaxOrdByUpMenuNo(upMenuNo);
        if (maxOrd == null) {
            return 0;
        }
        if (maxOrd < Integer.MAX_VALUE) {
            return maxOrd + 1;
        }
        List<Menu> siblings = allMenus.stream()
                .filter(menu -> Objects.equals(menu.getUpMenuNo(), upMenuNo))
                .sorted(DISPLAY_ORDER)
                .toList();
        for (int index = 0; index < siblings.size(); index++) {
            if (!Integer.valueOf(index).equals(siblings.get(index).getOrd())) {
                siblings.get(index).changeOrd(index, now);
            }
        }
        return siblings.size();
    }

    private String requireNonBlank(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new InvalidRequestException(message);
        }
        return trimmed;
    }

    /**
     * upMenuNo 관계를 따라 트리를 조립한다. 최상위(upMenuNo=null)에서 시작해 재귀적으로
     * 계층(menuLevel)·최상위(topMenuNo)를 계산한다. 정상 경로에서는 발생하지 않지만
     * DB 직접 조작 등으로 순환·고아 데이터가 있을 경우, 순환 가지는 감지 즉시 트리에서
     * 제외하고, 어느 루트에서도 도달하지 못한 노드(루트 없는 순환·고아 노드)는 순회 종료 후
     * 한 번에 걸러내며, 두 경우 모두 WARN 로그로 남긴다.
     */
    private List<MenuTreeResponse> assembleTree(List<Menu> menus) {
        Map<Long, List<Menu>> childrenByParent = new LinkedHashMap<>();
        for (Menu menu : menus) {
            childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
        }

        Set<Long> visited = new HashSet<>();
        List<MenuTreeResponse> roots = new ArrayList<>();
        for (Menu root : childrenByParent.getOrDefault(null, List.of())) {
            MenuTreeResponse node = buildNode(root, 1, root.getMenuNo(), childrenByParent, visited, new LinkedHashSet<>());
            if (node != null) {
                roots.add(node);
            }
        }

        List<Long> unvisited = menus.stream()
                .map(Menu::getMenuNo)
                .filter(menuNo -> !visited.contains(menuNo))
                .toList();
        if (!unvisited.isEmpty()) {
            log.warn("메뉴 트리 조립 시 미방문 노드 발견(루트 없는 순환 또는 고아 노드로 추정) menuNo={}", unvisited);
        }

        return roots;
    }

    private MenuTreeResponse buildNode(Menu menu, int level, Long topMenuNo,
                                        Map<Long, List<Menu>> childrenByParent,
                                        Set<Long> visited, Set<Long> pathStack) {
        if (pathStack.contains(menu.getMenuNo())) {
            List<Long> chain = new ArrayList<>(pathStack);
            chain.add(menu.getMenuNo());
            log.warn("메뉴 트리 순환 데이터 감지, 해당 가지를 트리에서 제외합니다. menuNo 체인={}", chain);
            return null;
        }

        visited.add(menu.getMenuNo());
        pathStack.add(menu.getMenuNo());

        List<MenuTreeResponse> childNodes = new ArrayList<>();
        for (Menu child : childrenByParent.getOrDefault(menu.getMenuNo(), List.of())) {
            MenuTreeResponse childNode = buildNode(child, level + 1, topMenuNo, childrenByParent, visited, pathStack);
            if (childNode != null) {
                childNodes.add(childNode);
            }
        }

        pathStack.remove(menu.getMenuNo());

        return MenuTreeResponse.of(menu, level, topMenuNo, childNodes);
    }
}
