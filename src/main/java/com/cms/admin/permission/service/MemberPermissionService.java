package com.cms.admin.permission.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.NotificationMessages;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.event.NotificationRequestedEvent;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.FeatureKind;
import com.cms.admin.permission.MemberPermission;
import com.cms.admin.permission.MemberPermissionId;
import com.cms.admin.permission.MemberPermissionRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionChangedEvent;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.dto.response.MemberPermissionMatrixResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * MANAGER 회원 한 명의 위임 가능 기능 허용 집합을 조회·교체한다(PLAN-member-permission.md §5). 허용값의 최종 권위는 코드 카탈로그
 * ({@link AdminFeature})이고 이 서비스는 위임 가능 기능의 DB 행만 다룬다 — 상시 허용·위임 불가 기능과 ADMIN은 DB를 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MemberPermissionService {

    private static final String NOT_FOUND_MESSAGE = "관리자를 찾을 수 없습니다.";

    private static final Comparator<Key> KEY_ORDER = Comparator
            .comparing((Key key) -> key.feature().ordinal())
            .thenComparing(key -> key.action().ordinal());

    private final MemberRepository memberRepository;
    private final MemberPermissionRepository memberPermissionRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** (기능, 동작) 한 건. 기능 선언 순서 → 동작 선언 순서로 정렬한다. */
    record Key(AdminFeature feature, PermissionAction action) {
    }

    /**
     * 조회는 한 읽기 트랜잭션에서 회원(버전)과 허용 행을 읽어 같은 시점을 보여 준다. 캐시를 읽지 않는다 — 저장 직후 무효화 창과
     * 무관하게 DB 기준 버전을 보여 주기 위해서다. 삭제된 회원도 읽기는 허용한다(화면이 읽기 전용으로 보여 준다).
     */
    @Transactional(readOnly = true)
    public MemberPermissionMatrixResponse getMatrix(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        validateTarget(member);
        return toMatrix(member, effectiveGrants(readValidRows(memberId)));
    }

    /**
     * 허용 집합 전체를 교체한다. 검사 순서: 요청 내용(기능·동작·중복·READ 의존, DB 접근 전) → 회원 행 잠금 → 대상(404·400·409) → 버전.
     * 대상의 역할을 DB에서 읽어야 해서 요청 내용 400이 대상 400·404보다 먼저다.
     *
     * <p>잠금: 이 트랜잭션의 첫 DB 조회가 {@code member} 행 {@code SELECT … FOR UPDATE}다. 허용 행이 0개여도 잠글 행이 있어 같은 회원의
     * 동시 저장이 직렬화되고, 같은 행을 잠그는 역할·상태 변경({@code AdminMemberService.updateAdminMember})과도 직렬화된다. 뒤따르는 허용 행
     * 조회의 스냅샷이 잠금 이후에 만들어져 앞선 저장의 커밋을 본다. 버전은 {@code @Version}이 아니라 잠금 아래 수동 비교다.
     * 변경이 없으면 쓰기·버전·이벤트가 없다.
     *
     * <p>감사: 서비스 메서드가 최상위 트랜잭션 진입점이어야 한다(컨트롤러 → 이 메서드 직접 호출, 내부에서 다른 {@code @Transactional}을
     * 거치지 않는다). 감사 Aspect는 반환 객체의 {@code getMemberId()}·{@code getAuditLabel()}에서 targetId·targetLabel을 추출한다.
     * 실패 감사는 targetLabel이 없다(Aspect 정책) — 라벨은 성공·변경 없음에만 남는다.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.PERMISSION_UPDATE, targetType = "MEMBER_PERMISSION",
            targetIdExpression = "memberId", targetLabelExpression = "auditLabel")
    public MemberPermissionUpdateResult replace(Long memberId, MemberPermissionUpdateRequest request) {
        Set<Key> requested = validateGrants(request.getGrants());

        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        validateTarget(member);
        if (member.getStatus() == MemberStatus.DELETED) {
            throw new ConflictException("삭제된 계정의 권한은 변경할 수 없습니다.");
        }
        if (!member.getPermissionVersion().equals(request.getVersion())) {
            throw new ConflictException("다른 관리자가 먼저 권한을 변경했거나 계정의 역할이 바뀌었습니다. 새로고침한 뒤 다시 시도해 주세요.");
        }

        Map<Key, MemberPermission> current = readValidRows(memberId);
        Set<Key> added = new TreeSet<>(KEY_ORDER);
        added.addAll(requested);
        added.removeAll(current.keySet());
        Set<Key> removed = new TreeSet<>(KEY_ORDER);
        removed.addAll(current.keySet());
        removed.removeAll(requested);

        if (added.isEmpty() && removed.isEmpty()) {
            return new MemberPermissionUpdateResult(memberId, toMatrix(member, effectiveGrants(current)),
                    "v" + member.getPermissionVersion() + ": 변경 없음");
        }

        // 대소문자·후행 공백 변형 행 가드: PK가 general_ci·PAD 비교라 수동 삽입된 변형 행이 정상 키와 같은 키로 취급된다.
        // 정확한 행(current)에 없는데 DB PK로는 이미 있다면 그 충돌이다 — 자동 삭제하지 않고 운영 정리를 안내한다.
        for (Key key : added) {
            if (memberPermissionRepository.existsById(new MemberPermissionId(memberId, key.feature().name(), key.action().name()))) {
                throw new ConflictException("권한 테이블에 형식이 올바르지 않은 행이 있어 저장할 수 없습니다. "
                        + "운영자가 해당 행을 정리한 뒤 다시 시도해 주세요.");
            }
        }

        memberPermissionRepository.deleteAll(removed.stream().map(current::get).toList());
        memberPermissionRepository.saveAll(added.stream()
                .map(key -> new MemberPermission(memberId, key.feature().name(), key.action().name()))
                .toList());

        long oldVersion = member.getPermissionVersion();
        member.increasePermissionVersion();
        eventPublisher.publishEvent(new PermissionChangedEvent(memberId));
        // 알림 E2 — 실제 변경(추가·회수)이 있을 때만, 커밋 뒤 리스너가 저장한다. 항목은 "공지사항 조회"처럼 기능·동작 라벨이다.
        eventPublisher.publishEvent(new NotificationRequestedEvent(memberId, NotificationType.PERMISSION,
                NotificationMessages.permissionChanged(notificationLabels(added), notificationLabels(removed)), null));

        String label = "v" + oldVersion + "→v" + member.getPermissionVersion() + ": " + changeSummary(added, removed);
        // 새 상태 = 요청 집합(검증으로 READ 의존이 이미 보장됨)
        return new MemberPermissionUpdateResult(memberId, toMatrix(member, requested), label);
    }

    /** 권한을 관리할 수 있는 대상은 MANAGER 회원뿐이다. ROLE_USER는 관리자 API가 존재를 숨기고(404), ADMIN은 코드 고정이라 거부한다(400). */
    private void validateTarget(Member member) {
        if (member.getUserType() == Role.ROLE_USER) {
            throw new ResourceNotFoundException(NOT_FOUND_MESSAGE);
        }
        if (member.getUserType() == Role.ROLE_ADMIN) {
            throw new InvalidRequestException("관리자(ADMIN)는 모든 권한이 코드로 고정되어 변경할 수 없습니다.");
        }
    }

    /** 요청 항목 검증(DB 접근 없음). 자동 보정은 하지 않고 거부한다. */
    private Set<Key> validateGrants(List<MemberPermissionUpdateRequest.Grant> grants) {
        Set<Key> requested = new LinkedHashSet<>();
        for (MemberPermissionUpdateRequest.Grant grant : grants) {
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
     * 허용 행 중 이 회원의 <b>정확한</b> 행이고 카탈로그로 파싱되는 위임 가능 기능·지원 동작만 diff 대상으로 읽는다. 모르는 기능·동작, 위임 불가
     * 기능, feature·action 문자열이 정확히 일치하지 않는 변형 행은 판정기가 이미 무시하므로 건드리지 않는다(정리는 마이그레이션 규칙). READ 없는
     * 쓰기 행은 카탈로그상 유효하므로 포함된다 — 교체 시 정상 처리된다.
     */
    private Map<Key, MemberPermission> readValidRows(Long memberId) {
        Map<Key, MemberPermission> rows = new TreeMap<>(KEY_ORDER);
        for (MemberPermission row : memberPermissionRepository.findByMemberId(memberId)) {
            if (!memberId.equals(row.getMemberId())) {
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
    private Set<Key> effectiveGrants(Map<Key, MemberPermission> rows) {
        Set<Key> effective = new TreeSet<>(KEY_ORDER);
        for (Key key : rows.keySet()) {
            if (key.action() == PermissionAction.READ || rows.containsKey(new Key(key.feature(), PermissionAction.READ))) {
                effective.add(key);
            }
        }
        return effective;
    }

    private MemberPermissionMatrixResponse toMatrix(Member member, Set<Key> delegableGrants) {
        List<MemberPermissionMatrixResponse.ActionColumn> columns = Arrays.stream(PermissionAction.values())
                .map(action -> new MemberPermissionMatrixResponse.ActionColumn(action, action.getLabel()))
                .toList();

        Map<AdminFeature, Set<PermissionAction>> granted = new EnumMap<>(AdminFeature.class);
        for (Key key : delegableGrants) {
            granted.computeIfAbsent(key.feature(), feature -> EnumSet.noneOf(PermissionAction.class)).add(key.action());
        }

        List<MemberPermissionMatrixResponse.FeatureRow> rows = new ArrayList<>();
        for (AdminFeature feature : AdminFeature.values()) {
            Set<PermissionAction> grantedActions = switch (feature.getKind()) {
                case ALWAYS -> feature.getActions();
                case ADMIN_ONLY -> Set.of();
                case DELEGABLE -> granted.getOrDefault(feature, Set.of());
            };
            rows.add(new MemberPermissionMatrixResponse.FeatureRow(feature, feature.getLabel(), feature.getKind(),
                    inActionOrder(feature.getActions()), inActionOrder(grantedActions)));
        }
        return new MemberPermissionMatrixResponse(member.getId(), member.getUserId(), member.getUserName(),
                member.getStatus(), member.getPermissionVersion(), columns, rows);
    }

    private static List<PermissionAction> inActionOrder(Set<PermissionAction> actions) {
        return Arrays.stream(PermissionAction.values()).filter(actions::contains).toList();
    }

    /** 알림 문장용 항목 라벨("공지사항 조회") — 코드 상수만으로 만들어 사용자 입력이 섞이지 않는다. */
    private static List<String> notificationLabels(Set<Key> keys) {
        return keys.stream().map(key -> key.feature().getLabel() + " " + key.action().getLabel()).toList();
    }

    /** 추가(+) 먼저, 그다음 삭제(-). 항목 형식은 {@code 기능라벨.동작라벨}이며 코드 상수만으로 만들어 사용자 입력이 섞이지 않는다. */
    private static String changeSummary(Set<Key> added, Set<Key> removed) {
        List<String> parts = new ArrayList<>();
        added.forEach(key -> parts.add("+" + key.feature().getLabel() + "." + key.action().getLabel()));
        removed.forEach(key -> parts.add("-" + key.feature().getLabel() + "." + key.action().getLabel()));
        return String.join(", ", parts);
    }
}
