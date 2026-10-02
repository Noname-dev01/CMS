package com.cms.admin.permission.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Role;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.FeatureKind;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionChangedEvent;
import com.cms.admin.permission.PermissionRole;
import com.cms.admin.permission.PermissionRoleRepository;
import com.cms.admin.permission.RolePermission;
import com.cms.admin.permission.RolePermissionId;
import com.cms.admin.permission.RolePermissionRepository;
import com.cms.admin.permission.dto.request.RolePermissionUpdateRequest;
import com.cms.admin.permission.dto.response.RolePermissionMatrixResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 역할(지금은 MANAGER 하나)의 위임 가능 기능 허용 집합을 조회·교체한다(PLAN-permission-management-pr3.md §5).
 * 허용값의 최종 권위는 코드 카탈로그({@link AdminFeature})이고 이 서비스는 위임 가능 기능의 DB 행만 다룬다 — 상시 허용·위임 불가 기능과
 * ADMIN은 DB를 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RolePermissionService {

    private static final Comparator<Key> KEY_ORDER = Comparator
            .comparing((Key key) -> key.feature().ordinal())
            .thenComparing(key -> key.action().ordinal());

    private final PermissionRoleRepository permissionRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** (기능, 동작) 한 건. 기능 선언 순서 → 동작 선언 순서로 정렬한다. */
    record Key(AdminFeature feature, PermissionAction action) {
    }

    /**
     * 조회는 한 읽기 트랜잭션에서 기준 행(버전)과 허용 행을 읽어 같은 시점을 보여 준다. 캐시를 읽지 않는다 — 저장 직후 무효화 창과
     * 무관하게 DB 기준 버전을 보여 주기 위해서다.
     */
    @Transactional(readOnly = true)
    public RolePermissionMatrixResponse getMatrix(Role role) {
        validateManageable(role);
        PermissionRole base = permissionRoleRepository.findById(role.name())
                .orElseThrow(() -> new ResourceNotFoundException("권한 정보를 찾을 수 없습니다."));
        Set<Key> effective = effectiveGrants(readValidRows(role));
        return toMatrix(role, base.getVersion(), effective);
    }

    /**
     * 허용 집합 전체를 교체한다. 검사 순서: 역할 → 요청 내용(기능·동작·중복·READ 의존, DB 접근 전) → 잠금 → 버전. 따라서 400이 409보다 우선한다.
     *
     * <p>잠금: 이 트랜잭션의 첫 DB 조회가 {@code permission_role} 행 {@code SELECT … FOR UPDATE}다. 허용 행이 0개여도 잠글 행이 있어
     * 동시 저장이 직렬화되고, 뒤따르는 허용 행 조회의 스냅샷이 잠금 이후에 만들어져 앞선 저장의 커밋을 본다. 버전은 {@code @Version}이
     * 아니라 잠금 아래 수동 비교다. 변경이 없으면 쓰기·버전·이벤트가 없다.
     *
     * <p>감사: 서비스 메서드가 최상위 트랜잭션 진입점이어야 한다(컨트롤러 → 이 메서드 직접 호출, 내부에서 다른 {@code @Transactional}을
     * 거치지 않는다). 실패 감사는 {@code targetLabel}이 없다(Aspect 정책) — 라벨은 성공·변경 없음에만 남는다.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.PERMISSION_UPDATE, targetType = "ROLE_PERMISSION",
            targetLabelExpression = "auditLabel")
    public RolePermissionUpdateResult replace(Role role, RolePermissionUpdateRequest request) {
        validateManageable(role);
        Set<Key> requested = validateGrants(request.getGrants());

        PermissionRole base = permissionRoleRepository.findByIdForUpdate(role.name())
                .orElseThrow(() -> new ResourceNotFoundException("권한 정보를 찾을 수 없습니다."));
        if (!base.getVersion().equals(request.getVersion())) {
            throw new ConflictException("다른 관리자가 먼저 권한을 변경했습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.");
        }

        Map<Key, RolePermission> current = readValidRows(role);
        Set<Key> added = new TreeSet<>(KEY_ORDER);
        added.addAll(requested);
        added.removeAll(current.keySet());
        Set<Key> removed = new TreeSet<>(KEY_ORDER);
        removed.addAll(current.keySet());
        removed.removeAll(requested);

        if (added.isEmpty() && removed.isEmpty()) {
            return new RolePermissionUpdateResult(toMatrix(role, base.getVersion(), effectiveGrants(current)),
                    role.name() + " v" + base.getVersion() + ": 변경 없음");
        }

        // 대소문자·후행 공백 변형 행 가드: PK가 general_ci·PAD 비교라 수동 삽입된 변형 행이 정상 키와 같은 키로 취급된다.
        // 정확한 행(current)에 없는데 DB PK로는 이미 있다면 그 충돌이다 — 자동 삭제하지 않고 운영 정리를 안내한다.
        for (Key key : added) {
            if (rolePermissionRepository.existsById(new RolePermissionId(role.name(), key.feature().name(), key.action().name()))) {
                throw new ConflictException("권한 테이블에 형식이 올바르지 않은 행이 있어 저장할 수 없습니다. "
                        + "운영자가 해당 행을 정리한 뒤 다시 시도해 주세요.");
            }
        }

        rolePermissionRepository.deleteAll(removed.stream().map(current::get).toList());
        rolePermissionRepository.saveAll(added.stream()
                .map(key -> new RolePermission(role.name(), key.feature().name(), key.action().name()))
                .toList());

        long oldVersion = base.getVersion();
        base.increaseVersion(LocalDateTime.now(clock));
        eventPublisher.publishEvent(new PermissionChangedEvent(role.name()));

        String label = role.name() + " v" + oldVersion + "→v" + base.getVersion() + ": " + changeSummary(added, removed);
        // 새 상태 = 요청 집합(검증으로 READ 의존이 이미 보장됨)
        return new RolePermissionUpdateResult(toMatrix(role, base.getVersion(), requested), label);
    }

    /** 이 서비스가 다루는 역할은 MANAGER뿐이다. ADMIN은 코드 고정이라 거부하고, 그 외 역할은 권한 매트릭스가 없다. */
    private void validateManageable(Role role) {
        if (role == Role.ROLE_ADMIN) {
            throw new InvalidRequestException("관리자(ROLE_ADMIN) 권한은 코드로 고정되어 변경할 수 없습니다.");
        }
        if (role != Role.ROLE_MANAGER) {
            throw new InvalidRequestException("권한을 관리할 수 없는 역할입니다.");
        }
    }

    /** 요청 항목 검증(DB 접근 없음). 자동 보정은 하지 않고 거부한다. */
    private Set<Key> validateGrants(List<RolePermissionUpdateRequest.Grant> grants) {
        Set<Key> requested = new LinkedHashSet<>();
        for (RolePermissionUpdateRequest.Grant grant : grants) {
            AdminFeature feature = grant.getFeature();
            PermissionAction action = grant.getAction();
            if (feature.getKind() != FeatureKind.DELEGABLE) {
                throw new InvalidRequestException("위임할 수 없는 기능입니다: " + feature.getLabel());
            }
            // 현재 카탈로그의 위임 가능 기능(NOTICE)은 모든 동작을 지원해 도달하지 않는 방어 분기다.
            if (!feature.supports(action)) {
                throw new InvalidRequestException(feature.getLabel() + "은(는) " + action.getLabel() + " 동작을 지원하지 않습니다.");
            }
            if (!requested.add(new Key(feature, action))) {
                throw new InvalidRequestException("중복된 권한 항목이 있습니다.");
            }
        }
        for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.DELEGABLE)) {
            boolean anyWrite = Arrays.stream(PermissionAction.values())
                    .anyMatch(action -> action != PermissionAction.READ && requested.contains(new Key(feature, action)));
            if (anyWrite && !requested.contains(new Key(feature, PermissionAction.READ))) {
                throw new InvalidRequestException(feature.getLabel() + "의 생성·수정·삭제 권한은 조회 권한과 함께 부여해야 합니다.");
            }
        }
        return requested;
    }

    /**
     * 허용 행 중 이 역할의 <b>정확한</b> 행이고 카탈로그로 파싱되는 위임 가능 기능·지원 동작만 diff 대상으로 읽는다. 모르는 기능·동작, 위임 불가
     * 기능, 역할 문자열이 정확히 일치하지 않는 변형 행은 판정기가 이미 무시하므로 건드리지 않는다(정리는 마이그레이션 규칙). READ 없는 쓰기 행은
     * 카탈로그상 유효하므로 포함된다 — 교체 시 정상 처리된다.
     */
    private Map<Key, RolePermission> readValidRows(Role role) {
        Map<Key, RolePermission> rows = new java.util.TreeMap<>(KEY_ORDER);
        for (RolePermission row : rolePermissionRepository.findByRole(role.name())) {
            if (!role.name().equals(row.getRole())) {
                continue;
            }
            try {
                AdminFeature feature = AdminFeature.valueOf(row.getFeature());
                PermissionAction action = PermissionAction.valueOf(row.getAction());
                if (feature.getKind() == FeatureKind.DELEGABLE && feature.supports(action)) {
                    rows.put(new Key(feature, action), row);
                }
            } catch (IllegalArgumentException ignored) {
                // 모르는 기능·동작 행은 판정기가 무시한다
            }
        }
        return rows;
    }

    /** 판정기와 같은 의미의 유효 허용값: 쓰기 동작은 같은 기능의 READ가 있을 때만 유효하다. */
    private Set<Key> effectiveGrants(Map<Key, RolePermission> rows) {
        Set<Key> effective = new TreeSet<>(KEY_ORDER);
        for (Key key : rows.keySet()) {
            if (key.action() == PermissionAction.READ || rows.containsKey(new Key(key.feature(), PermissionAction.READ))) {
                effective.add(key);
            }
        }
        return effective;
    }

    private RolePermissionMatrixResponse toMatrix(Role role, Long version, Set<Key> delegableGrants) {
        List<RolePermissionMatrixResponse.ActionColumn> columns = Arrays.stream(PermissionAction.values())
                .map(action -> new RolePermissionMatrixResponse.ActionColumn(action, action.getLabel()))
                .toList();

        Map<AdminFeature, Set<PermissionAction>> granted = new EnumMap<>(AdminFeature.class);
        for (Key key : delegableGrants) {
            granted.computeIfAbsent(key.feature(), feature -> EnumSet.noneOf(PermissionAction.class)).add(key.action());
        }

        List<RolePermissionMatrixResponse.FeatureRow> rows = new ArrayList<>();
        for (AdminFeature feature : AdminFeature.values()) {
            Set<PermissionAction> grantedActions = switch (feature.getKind()) {
                case ALWAYS -> feature.getActions();
                case ADMIN_ONLY -> Set.of();
                case DELEGABLE -> granted.getOrDefault(feature, Set.of());
            };
            rows.add(new RolePermissionMatrixResponse.FeatureRow(feature, feature.getLabel(), feature.getKind(),
                    inActionOrder(feature.getActions()), inActionOrder(grantedActions)));
        }
        return new RolePermissionMatrixResponse(role.name(), version, columns, rows);
    }

    private static List<PermissionAction> inActionOrder(Set<PermissionAction> actions) {
        return Arrays.stream(PermissionAction.values()).filter(actions::contains).toList();
    }

    /** 추가(+) 먼저, 그다음 삭제(-). 항목 형식은 {@code 기능라벨.동작라벨}이며 코드 상수만으로 만들어 사용자 입력이 섞이지 않는다. */
    private static String changeSummary(Set<Key> added, Set<Key> removed) {
        List<String> parts = new ArrayList<>();
        added.forEach(key -> parts.add("+" + key.feature().getLabel() + "." + key.action().getLabel()));
        removed.forEach(key -> parts.add("-" + key.feature().getLabel() + "." + key.action().getLabel()));
        return String.join(", ", parts);
    }
}
