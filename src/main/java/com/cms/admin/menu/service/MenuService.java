package com.cms.admin.menu.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuAccessRole;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuMoveRequest;
import com.cms.admin.menu.dto.request.MenuOrderRequest;
import com.cms.admin.menu.dto.request.MenuOrderScope;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuMoveResponse;
import com.cms.admin.menu.dto.response.MenuOrderResponse;
import com.cms.admin.menu.dto.response.MenuResponse;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_CREATE, targetType = "MENU", targetIdExpression = "menuNo")
    public MenuResponse createMenu(MenuCreateRequest request) {
        String menuName = requireNonBlank(request.getMenuName(), "메뉴명은 공백일 수 없습니다.");
        boolean useYn = request.getUseYn() == null || request.getUseYn();
        MenuAccessRole accessRole = request.getAccessRole() != null ? request.getAccessRole() : MenuAccessRole.ALL;

        Long upMenuNo = request.getUpMenuNo();
        if (upMenuNo != null) {
            Menu parent = menuRepository.findByIdForUpdate(upMenuNo)
                    .orElseThrow(() -> new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다."));
            if (useYn && !Boolean.TRUE.equals(parent.getUseYn())) {
                throw new InvalidRequestException("비활성 부모 메뉴 아래에는 활성 메뉴를 생성할 수 없습니다.");
            }
        }

        Integer ord = request.getOrd() != null ? request.getOrd() : resolveNextOrd(upMenuNo);

        LocalDateTime now = LocalDateTime.now(clock);
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
        Integer effectiveOrd = request.getOrd() != null ? request.getOrd() : target.getOrd();

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
                effectiveUseYn, effectiveAccessRole, effectiveOrd, LocalDateTime.now(clock));

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
     * 같은 부모 아래 형제들의 순서를 한 번에 확정한다(PLAN-menu-reorder.md).
     *
     * <p>잠금 규약: 형제 전체를 menuNo 오름차순으로 하나씩 findByIdForUpdate로 잠근다. 부모 행은
     * 잠그지 않는다(재활성화의 자식→부모 순서와 반대 방향 사이클을 만들지 않기 위해). 형제 id는
     * 엔티티가 아니라 값 프로젝션으로 먼저 읽어(Menu에는 @DynamicUpdate가 없어 오래된 엔티티가
     * 영속성 컨텍스트에 있으면 전체 컬럼 UPDATE로 동시 수정을 덮어쓴다) 잠금 후에 처음 적재한다.
     *
     * <p>계약: 요청한 형제들끼리의 상대 순서는 확정된다. 스냅샷 이후 동시에 생성된 형제의 위치는
     * 보장하지 않는다. 같은 집합의 순서를 다른 관리자가 먼저 바꿨다면 마지막 쓰기가 이긴다.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_REORDER, targetType = "MENU", targetIdExpression = "upMenuNo")
    public MenuOrderResponse reorderMenus(MenuOrderRequest request) {
        Long upMenuNo = request.getUpMenuNo();
        MenuOrderScope scope = request.getScope();
        List<Long> requested = request.getMenuNos();

        Set<Long> requestedSet = new LinkedHashSet<>(requested);
        if (requestedSet.size() != requested.size()) {
            throw new InvalidRequestException("메뉴 번호가 중복되었습니다.");
        }

        if (upMenuNo != null && !menuRepository.existsById(upMenuNo)) {
            throw new ResourceNotFoundException("부모 메뉴를 찾을 수 없습니다.");
        }

        // 스냅샷(비잠금, menuNo 오름차순). 요청은 scope가 가리키는 집합과 정확히 같아야 한다.
        List<MenuRepository.SiblingRow> snapshot = upMenuNo == null
                ? menuRepository.findRootSiblingRows()
                : menuRepository.findSiblingRowsByUpMenuNo(upMenuNo);
        Set<Long> expected = snapshot.stream()
                .filter(row -> scope == MenuOrderScope.ALL || Boolean.TRUE.equals(row.useYn()))
                .map(MenuRepository.SiblingRow::menuNo)
                .collect(Collectors.toSet());
        if (!expected.equals(requestedSet)) {
            throw siblingsChanged();
        }

        // 스냅샷은 ACTIVE여도 형제 전체를 잠근다 — 전체를 다시 매겨야 하므로. menuNo 오름차순 고정.
        List<Menu> siblings = new ArrayList<>();
        for (MenuRepository.SiblingRow row : snapshot) {
            Menu locked = menuRepository.findByIdForUpdate(row.menuNo()).orElseThrow(this::siblingsChanged);
            // 부모 이동(moveMenu)이 생기면 스냅샷엔 이 그룹의 형제였던 행이 그사이 다른 그룹으로 옮겨졌을 수 있다.
            // 그 행에 이 그룹의 ord를 쓰면 다른 그룹의 순서를 오염시키므로 잠근 최신 부모로 재검사한다.
            if (!Objects.equals(locked.getUpMenuNo(), upMenuNo)) {
                throw siblingsChanged();
            }
            siblings.add(locked);
        }

        // 잠금 후 재검증: 잠근(최신) 엔티티로 활성 집합이 여전히 요청과 같은지 확인한다.
        if (scope == MenuOrderScope.ACTIVE) {
            Set<Long> activeNow = siblings.stream()
                    .filter(menu -> Boolean.TRUE.equals(menu.getUseYn()))
                    .map(Menu::getMenuNo)
                    .collect(Collectors.toSet());
            if (!activeNow.equals(requestedSet)) {
                throw siblingsChanged();
            }
        }

        // 현재 표시 순서 F — 조회 쿼리(order by ord asc, menu_no asc)와 같다. MariaDB는 NULL을 가장 작게 본다.
        List<Menu> display = new ArrayList<>(siblings);
        display.sort(Comparator.comparing(Menu::getOrd, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
                .thenComparing(Menu::getMenuNo));

        List<Long> target = new ArrayList<>(display.size());
        if (scope == MenuOrderScope.ALL) {
            target.addAll(requested);
        } else {
            // 비활성 형제는 F의 자리에 그대로 두고, 활성 자리에 요청 순서를 채운다.
            Iterator<Long> next = requested.iterator();
            for (Menu menu : display) {
                target.add(Boolean.TRUE.equals(menu.getUseYn()) ? next.next() : menu.getMenuNo());
            }
        }

        Map<Long, Menu> byMenuNo = siblings.stream().collect(Collectors.toMap(Menu::getMenuNo, menu -> menu));
        LocalDateTime now = LocalDateTime.now(clock);
        List<MenuOrderResponse.Item> items = new ArrayList<>(target.size());
        for (int index = 0; index < target.size(); index++) {
            Menu menu = byMenuNo.get(target.get(index));
            if (!Integer.valueOf(index).equals(menu.getOrd())) {
                menu.changeOrd(index, now);
            }
            items.add(MenuOrderResponse.Item.builder().menuNo(menu.getMenuNo()).ord(index).build());
        }

        return MenuOrderResponse.builder().upMenuNo(upMenuNo).menus(items).build();
    }

    /**
     * 메뉴의 부모를 바꿔 이동한다(PLAN-menu-move.md). menuNo를 유지하므로 감사 로그 참조가 끊기지 않는다.
     *
     * <p>결과는 항상 2단 이하: 자식이 하나라도 있는 메뉴는 이동할 수 없고(비활성 자식 포함) 새 부모는 최상위
     * 메뉴여야 한다. 위치는 새 부모 아래 맨 끝이다.
     *
     * <p>잠금 규약: 요청의 두 id(대상 T, 새 부모 N — 승격이면 T만)를 <b>menuNo 오름차순</b>으로 잠근다.
     * 원래 부모는 잠그지 않는다(T가 떠나는 것은 원래 부모의 불변식을 깨지 않는다). <b>이 트랜잭션의 첫
     * 애플리케이션 테이블 조회가 잠금 읽기여야 한다</b> — 그 전에 비잠금 읽기를 하면 REPEATABLE READ 스냅샷이
     * 일찍 고정돼 뒤이은 {@code existsByUpMenuNo}·{@code max ord}가 오래된 값을 볼 수 있다. 모든 검사는 잠근
     * 최신 엔티티로 한다. 알려진 교착 계열은 InnoDB 탐지 → 409로 종료된다(계획서 결정 9).
     *
     * <p>검사 우선순위(두 잠금을 모두 얻었을 때): 무변경(200) → N이 최상위 아님(400) → 자식 있음(400) →
     * 활성 T를 비활성 N 아래로(400). 무변경을 먼저 판정해 이미 원하는 상태인 요청이 멱등하게 성공한다.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.MENU_MOVE, targetType = "MENU", targetIdExpression = "menuNo")
    public MenuMoveResponse moveMenu(Long menuNo, MenuMoveRequest request) {
        Long newParentNo = request.resolvedUpMenuNo();
        if (menuNo.equals(newParentNo)) {
            throw new InvalidRequestException("메뉴를 자기 자신 아래로 이동할 수 없습니다.");
        }

        // 두 행을 menuNo 오름차순으로 잠근다(첫 애플리케이션 테이블 조회가 잠금 읽기).
        Menu target;
        Menu newParent = null;
        if (newParentNo == null) {
            target = lockMenu(menuNo, "메뉴를 찾을 수 없습니다.");
        } else if (menuNo < newParentNo) {
            target = lockMenu(menuNo, "메뉴를 찾을 수 없습니다.");
            newParent = lockMenu(newParentNo, "부모 메뉴를 찾을 수 없습니다.");
        } else {
            newParent = lockMenu(newParentNo, "부모 메뉴를 찾을 수 없습니다.");
            target = lockMenu(menuNo, "메뉴를 찾을 수 없습니다.");
        }

        // 이미 원하는 부모 아래(또는 최상위)면 무변경 — 자식·깊이 검사보다 먼저 판정한다.
        if (Objects.equals(target.getUpMenuNo(), newParentNo)) {
            return moveResponse(target, List.of());
        }

        if (newParent != null && newParent.getUpMenuNo() != null) {
            throw new InvalidRequestException("하위 메뉴 아래로는 이동할 수 없습니다. 최상위 메뉴만 상위 메뉴로 지정할 수 있습니다.");
        }
        if (menuRepository.existsByUpMenuNo(menuNo)) {
            throw new InvalidRequestException("하위 메뉴가 있는 메뉴는 이동할 수 없습니다.");
        }
        if (newParent != null && Boolean.TRUE.equals(target.getUseYn()) && !Boolean.TRUE.equals(newParent.getUseYn())) {
            throw new InvalidRequestException("비활성 상위 메뉴 아래로는 활성 메뉴를 이동할 수 없습니다.");
        }

        target.changeParent(newParentNo, resolveNextOrd(newParentNo), LocalDateTime.now(clock));

        List<String> warnings = new ArrayList<>();
        if (newParent != null
                && newParent.getAccessRole() == MenuAccessRole.ADMIN
                && target.getAccessRole() == MenuAccessRole.ALL) {
            warnings.add("상위 메뉴가 관리자 전용이라 MANAGER에게는 이 메뉴가 사이드바에 표시되지 않습니다.");
        }
        return moveResponse(target, warnings);
    }

    private Menu lockMenu(Long menuNo, String notFoundMessage) {
        return menuRepository.findByIdForUpdate(menuNo)
                .orElseThrow(() -> new ResourceNotFoundException(notFoundMessage));
    }

    private MenuMoveResponse moveResponse(Menu menu, List<String> warnings) {
        return MenuMoveResponse.builder()
                .menuNo(menu.getMenuNo())
                .upMenuNo(menu.getUpMenuNo())
                .ord(menu.getOrd())
                .warnings(warnings)
                .build();
    }

    private ConflictException siblingsChanged() {
        return new ConflictException("형제 메뉴 구성이 변경되었습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.");
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
     * <p>SB Admin 2 사이드바 UI 제약에 따라 2단(최상위 + 직계 하위)까지만 조립한다.
     * 3단 이하 메뉴와, 부모가 노출 대상에서 빠진 하위 메뉴는 렌더링되지 않는다.
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
                .map(root -> SidebarMenuResponse.of(
                        root,
                        childrenByParent.getOrDefault(root.getMenuNo(), List.of()).stream()
                                .map(child -> SidebarMenuResponse.of(child, List.of()))
                                .toList()))
                .toList();
    }

    private Integer resolveNextOrd(Long upMenuNo) {
        Integer maxOrd = upMenuNo == null
                ? menuRepository.findMaxOrdByUpMenuNoIsNull()
                : menuRepository.findMaxOrdByUpMenuNo(upMenuNo);
        return maxOrd == null ? 0 : maxOrd + 1;
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
