package com.cms.admin.menu.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;

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

        LocalDateTime now = LocalDateTime.now();
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
        // useYn=false 요청은 비활성화 전환 가능성이 있으므로, deactivateMenu()와 동일하게
        // 대상 row를 비관적 락(PESSIMISTIC_WRITE)으로 잠근 뒤 조회한다. 이렇게 해야 아래
        // 활성 하위 메뉴 검사와 상태 반영이 동시 createMenu(활성 자식)와 직렬화된다.
        boolean deactivationRequested = Boolean.FALSE.equals(request.getUseYn());
        Menu target = (deactivationRequested
                ? menuRepository.findByIdForUpdate(menuNo)
                : menuRepository.findById(menuNo))
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
                effectiveUseYn, effectiveAccessRole, effectiveOrd);

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

        target.deactivate();

        return MenuResponse.from(target);
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
