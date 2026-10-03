package com.cms.admin.menu.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuDeleteResult;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.admin.permission.AdminPermissionEvaluator;
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
import java.util.function.Predicate;

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
    private final AdminPermissionEvaluator adminPermissionEvaluator;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_CREATE, targetType = "MENU", targetIdExpression = "menuNo")
    public MenuResponse createMenu(MenuCreateRequest request) {
        String menuName = requireNonBlank(request.getMenuName(), "메뉴명은 공백일 수 없습니다.");
        boolean useYn = request.getUseYn() == null || request.getUseYn();

        // 깊이 검사가 조상 사슬을 읽고 ord 오버플로 보정이 형제를 다시 매기므로 전체 행 잠금을 첫 조회로 쓴다
        // (PLAN-menu-structure-apply.md 결정 9c — 구조 반영과 같은 순서로 잠가 직렬화된다).
        List<Menu> allMenus = menuRepository.findAllForUpdate();

        Long upMenuNo = request.getUpMenuNo();
        if (upMenuNo != null) {
            Menu parent = menuRepository.findByIdForUpdate(upMenuNo)
                    .orElseThrow(() -> new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다."));
            if (useYn && !Boolean.TRUE.equals(parent.getUseYn())) {
                throw new InvalidRequestException("비활성 부모 메뉴 아래에는 활성 메뉴를 생성할 수 없습니다.");
            }
            if (depthOf(parent) >= MAX_MENU_DEPTH) {
                throw new InvalidRequestException("메뉴는 최대 " + MAX_MENU_DEPTH + "단까지만 만들 수 있습니다.");
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
        // 조회부터 비관적 락(PESSIMISTIC_WRITE)으로 대상 row를 잠근다 — deleteMenu()와
        // 동일하게, 아래 활성 하위 메뉴 검사와 상태 반영이 동시 createMenu(활성 자식)와도 직렬화된다.
        Menu target = menuRepository.findByIdForUpdate(menuNo)
                .orElseThrow(() -> new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        String effectiveMenuName = target.getMenuName();
        if (request.getMenuName() != null) {
            effectiveMenuName = requireNonBlank(request.getMenuName(), "메뉴명은 공백일 수 없습니다.");
        }

        String effectiveMenuUrl = request.getMenuUrl() != null ? request.getMenuUrl() : target.getMenuUrl();
        String effectiveMenuIcon = request.getMenuIcon() != null ? request.getMenuIcon() : target.getMenuIcon();
        String effectiveMenuDesc = request.getMenuDesc() != null ? request.getMenuDesc() : target.getMenuDesc();

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
        // 비활성화는 이 PATCH 경로(useYn=false)가 유일하므로 여기서 불변식을 강제한다. (위에서 대상 row를 이미 잠갔다.)
        if (deactivating && menuRepository.existsByUpMenuNoAndUseYnTrue(menuNo)) {
            throw new ConflictException("활성 하위 메뉴가 있어 비활성화할 수 없습니다.");
        }

        target.update(effectiveMenuName, effectiveMenuUrl, effectiveMenuIcon, effectiveMenuDesc,
                effectiveUseYn, target.getOrd(), LocalDateTime.now(clock));

        return MenuResponse.from(target);
    }

    /**
     * 메뉴 영구삭제(하드 삭제, PLAN-menu-permanent-delete.md). <b>비활성 상태이고 하위 메뉴가 없는</b> 메뉴만 지운다 —
     * 활성이거나 자식이 하나라도(활성·비활성 무관) 있으면 409. 비활성화는 {@link #updateMenu}(PATCH useYn=false)가 담당한다.
     *
     * <p>잠금: 대상 행 하나만 잠근다. 생성·구조 반영은 전체 행(대상 포함)을, 일반 수정·재활성화는 대상 행을
     * 잠그므로 모두 이 삭제와 직렬화된다. 하위 존재 검사는 비잠금 읽기라 <b>잠금 읽기 뒤에</b> 호출해야 REPEATABLE READ
     * 스냅샷이 잠금 이후에 고정된다.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_DELETE, targetType = "MENU",
            targetIdExpression = "menuNo", targetLabelExpression = "auditLabel")
    public MenuDeleteResult deleteMenu(Long menuNo) {
        Menu target = menuRepository.findByIdForUpdate(menuNo)
                .orElseThrow(() -> new ResourceNotFoundException("메뉴를 찾을 수 없습니다."));

        if (Boolean.TRUE.equals(target.getUseYn())) {
            throw new ConflictException("활성 메뉴는 영구삭제할 수 없습니다. 먼저 비활성화해주세요.");
        }
        if (menuRepository.existsByUpMenuNo(menuNo)) {
            throw new ConflictException("하위 메뉴가 있어 영구삭제할 수 없습니다. 하위 메뉴를 먼저 삭제하거나 이동해주세요.");
        }

        // 행이 사라진 뒤에는 이름·URL을 알 수 없으므로 삭제 전에 감사용 스냅샷을 만든다.
        MenuDeleteResult snapshot = new MenuDeleteResult(target.getMenuNo(), target.getMenuName(), target.getMenuUrl());
        menuRepository.delete(target);

        return snapshot;
    }

    /**
     * 메뉴 구조(부모·순서)를 일괄 반영한다(PLAN-menu-structure-apply.md). 화면이 드래그로 만든 초안(최종 트리)을 한 번에
     * 받아 전체 행을 잠그고 검증한 뒤 저장한다.
     *
     * <p>잠금: 첫 애플리케이션 테이블 조회로 전체 메뉴를 menuNo 오름차순 PESSIMISTIC_WRITE로 읽는다(결정 2). 구조를
     * 바꾸는 경로(구조 반영·생성)가 같은 순서로 전체를 잠가 서로 직렬화된다.
     *
     * <p>낙관적 검사(결정 3): 요청 집합이 현재 <b>트리 도달 가능 집합</b>과 다르거나 어떤 행의 현재 (부모, ord)가
     * 요청의 base와 다르면 409. 고아·루트 없는 순환 행(트리 응답에서 제외되는 행)은 비교에서 빼고 잠금만 한다.
     *
     * <p>검증(결정 4): 부모가 바뀐 메뉴와 그 서브트리에만 깊이(≤3)·활성/비활성 부모 규칙을 적용한다 —
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
        Map<Long, Menu> byNo = new LinkedHashMap<>();
        for (Menu menu : all) {
            byNo.put(menu.getMenuNo(), menu);
        }

        // 집합 불일치를 개수 검사보다 먼저 판정한다 — 다른 관리자가 메뉴를 영구삭제하면 낡은 초안은 현재 행 수보다
        // 항목이 많아지는데, 이를 400으로 걸러내면 화면이 초안을 못 버린다(409에서만 폐기). 요청 집합이 도달 집합과
        // 같다면 요청 수 ≤ 전체 행 수는 도달 집합 ⊆ 전체 행이라 저절로 성립해 별도 개수 검사가 필요 없다.
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

    /** 부모가 바뀐 서브트리의 메뉴 한 건이 최종 구조에서 깊이·활성 상태 규칙을 지키는지 검사한다. */
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

    public List<MenuTreeResponse> getMenuTree(String useYnFilter) {
        if (!"true".equals(useYnFilter) && !"all".equals(useYnFilter)) {
            throw new InvalidRequestException("useYn 파라미터는 true 또는 all만 허용됩니다.");
        }

        // 노출 안내(exposure)는 필터와 무관하게 전체 메뉴로 계산한다 — 비활성 조상 아래 메뉴가 안내에서 어긋나지 않도록.
        // 권한이 사용자별이라 "MANAGER 한 명의 시점"이 없다 — 안내는 "권한을 받으면 MANAGER가 볼 수 있는가"(카탈로그 분류)만 본다.
        // 그래서 권한 스냅샷(캐시 로드)이 필요 없다.
        Predicate<String> managerVisible = adminPermissionEvaluator.anyManagerMenuUrlVisibility();
        List<Menu> allMenus = menuRepository.findAllByOrderByOrdAscMenuNoAsc();
        MenuVisibility.Result visibility = MenuVisibility.evaluate(allMenus, managerVisible);

        List<Menu> menus = "all".equals(useYnFilter)
                ? allMenus
                : allMenus.stream().filter(menu -> Boolean.TRUE.equals(menu.getUseYn())).toList();

        return assembleTree(menus, visibility);
    }

    /**
     * 사이드바 렌더링용 메뉴 목록. 활성(useYn=true) 메뉴만 대상으로, 호출자가 넘긴 판정({@code urlVisible})으로
     * 노출 여부를 정한다 — 가지치기 규칙은 {@link MenuVisibility} 참조. 판정은 요청당 한 번 받은 권한 스냅샷으로 만든 것이어야 한다.
     *
     * <p>최대 {@value #MAX_MENU_DEPTH}단(최상위 + 하위 + 하위의 하위)까지 조립한다.
     * 그보다 깊은 메뉴와, 부모가 노출 대상에서 빠진 하위 메뉴는 렌더링되지 않는다.
     */
    @Transactional(readOnly = true)
    public List<SidebarMenuResponse> getSidebarMenus(Predicate<String> urlVisible) {
        return MenuVisibility.evaluate(menuRepository.findAllByUseYnTrueOrderByOrdAscMenuNoAsc(), urlVisible).sidebar();
    }

    /** 메뉴 번호 하나의 부모 사슬 깊이(최상위=1). 순환 데이터는 한 번 본 노드에서 멈춘다. */
    private int depthOf(Menu menu) {
        int depth = 1;
        Set<Long> visited = new HashSet<>();
        visited.add(menu.getMenuNo());
        Long upMenuNo = menu.getUpMenuNo();
        while (upMenuNo != null && visited.add(upMenuNo)) {
            depth++;
            Menu ancestor = menuRepository.findById(upMenuNo).orElse(null);
            if (ancestor == null) {
                break;
            }
            upMenuNo = ancestor.getUpMenuNo();
        }
        return depth;
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
    private List<MenuTreeResponse> assembleTree(List<Menu> menus, MenuVisibility.Result visibility) {
        Map<Long, List<Menu>> childrenByParent = new LinkedHashMap<>();
        for (Menu menu : menus) {
            childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
        }

        Set<Long> visited = new HashSet<>();
        List<MenuTreeResponse> roots = new ArrayList<>();
        for (Menu root : childrenByParent.getOrDefault(null, List.of())) {
            MenuTreeResponse node = buildNode(root, 1, root.getMenuNo(), childrenByParent, visibility, visited, new LinkedHashSet<>());
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
                                        Map<Long, List<Menu>> childrenByParent, MenuVisibility.Result visibility,
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
            MenuTreeResponse childNode = buildNode(child, level + 1, topMenuNo, childrenByParent, visibility, visited, pathStack);
            if (childNode != null) {
                childNodes.add(childNode);
            }
        }

        pathStack.remove(menu.getMenuNo());

        return MenuTreeResponse.of(menu, level, topMenuNo, visibility.exposureOf(menu.getMenuNo()).code(), visibility.exposureOf(menu.getMenuNo()).label(), childNodes);
    }
}
