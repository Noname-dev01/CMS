package com.cms.admin.permission.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.repository.BoardRepository;
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
import com.cms.admin.permission.MemberBoardPermission;
import com.cms.admin.permission.MemberBoardPermissionId;
import com.cms.admin.permission.MemberBoardPermissionRepository;
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
import com.cms.config.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * MANAGER 회원 한 명의 위임 가능 기능 허용 집합과 게시판별 허용 집합을 조회·교체한다(PLAN-member-permission.md §5, PLAN-board.md 쟁점 5).
 * 허용값의 최종 권위는 코드 카탈로그({@link AdminFeature})이고 이 서비스는 위임 가능 기능·게시판의 DB 행만 다룬다 — 상시 허용·위임 불가 기능과
 * ADMIN은 DB를 보지 않는다. 두 집합은 같은 회원 행 잠금·같은 버전·같은 감사 한 건으로 묶인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberPermissionService {

    private static final String NOT_FOUND_MESSAGE = "관리자를 찾을 수 없습니다.";

    /** 감사 라벨 상한(`admin_action_log.target_label` VARCHAR(500)) — 넘으면 끝을 `…`로 표시한다(Aspect는 표시 없이 자르므로 여기서 먼저 맞춘다). */
    static final int MAX_AUDIT_LABEL_LENGTH = 500;

    private static final Comparator<Key> KEY_ORDER = Comparator
            .comparing((Key key) -> key.feature().ordinal())
            .thenComparing(key -> key.action().ordinal());

    private static final Comparator<BoardKey> BOARD_KEY_ORDER = Comparator
            .comparing(BoardKey::boardId)
            .thenComparing(key -> key.action().ordinal());

    private final MemberRepository memberRepository;
    private final MemberPermissionRepository memberPermissionRepository;
    private final MemberBoardPermissionRepository memberBoardPermissionRepository;
    private final BoardRepository boardRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** (기능, 동작) 한 건. 기능 선언 순서 → 동작 선언 순서로 정렬한다. */
    record Key(AdminFeature feature, PermissionAction action) {
    }

    /** (게시판, 동작) 한 건. 게시판 ID → 동작 선언 순서로 정렬한다. */
    record BoardKey(Long boardId, PermissionAction action) {
    }

    /**
     * 조회는 한 읽기 트랜잭션에서 회원(버전)·허용 행·게시판 목록을 읽어 같은 시점을 보여 준다. 캐시를 읽지 않는다 — 저장 직후 무효화 창과
     * 무관하게 DB 기준 버전을 보여 주기 위해서다. 삭제된 회원도 읽기는 허용한다(화면이 읽기 전용으로 보여 준다).
     */
    @Transactional(readOnly = true)
    public MemberPermissionMatrixResponse getMatrix(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        validateTarget(member);
        List<Board> boards = boardRepository.findByDeletedFalseOrderByIdAsc();
        return toMatrix(member, effectiveGrants(readValidRows(memberId)), effectiveBoardGrants(readValidBoardRows(memberId)), boards);
    }

    /**
     * 허용 집합 전체를 교체한다. 검사 순서: 요청 내용(기능·동작·중복·READ 의존 — 기능과 게시판 모두, DB 접근 전) → 회원 행 잠금 →
     * 대상(404·400·409) → 버전 → 요청 게시판 공유 잠금·존재 확인(400) → diff → 변형 행 가드(409) → 쓰기.
     *
     * <p>잠금: 이 트랜잭션의 첫 DB 조회가 {@code member} 행 {@code SELECT … FOR UPDATE}다. 허용 행이 0개여도 잠글 행이 있어 같은 회원의
     * 동시 저장이 직렬화되고, 같은 행을 잠그는 역할·상태 변경({@code AdminMemberService.updateAdminMember})과도 직렬화된다. 부여할 게시판은
     * {@code FOR SHARE}로 잠가 게시판 삭제({@code FOR UPDATE}, PR B)와 직렬화한다(잠금 순서 회원 → 게시판). 버전은 {@code @Version}이 아니라
     * 잠금 아래 수동 비교다. 변경이 없으면 쓰기·버전·이벤트가 없다.
     *
     * <p>게시판 권한 회수는 키 단위 JPQL 벌크 삭제다(멱등 — 회수 대상 게시판이 동시에 삭제돼 행이 먼저 사라져도 0건으로 끝난다, PLAN-board.md
     * 리뷰 R3-3). {@code innodb_snapshot_isolation=ON}이면 그 경합이 오류 1020 → 409가 될 수 있고 전체가 롤백된다(허용, 리뷰 R4-2).
     *
     * <p>감사: 서비스 메서드가 최상위 트랜잭션 진입점이어야 한다(컨트롤러 → 이 메서드 직접 호출). 감사 Aspect는 반환 객체의
     * {@code getMemberId()}·{@code getAuditLabel()}에서 targetId·targetLabel을 추출한다. 실패 감사는 targetLabel이 없다(Aspect 정책).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.PERMISSION_UPDATE, targetType = "MEMBER_PERMISSION",
            targetIdExpression = "memberId", targetLabelExpression = "auditLabel")
    public MemberPermissionUpdateResult replace(Long memberId, MemberPermissionUpdateRequest request) {
        Set<Key> requested = validateGrants(request.getGrants());
        Set<BoardKey> requestedBoards = validateBoardGrants(request.getBoardGrants());

        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        validateTarget(member);
        if (member.getStatus() == MemberStatus.DELETED) {
            throw new ConflictException("삭제된 계정의 권한은 변경할 수 없습니다.");
        }
        if (!member.getPermissionVersion().equals(request.getVersion())) {
            throw new ConflictException("다른 관리자가 먼저 권한을 변경했거나 계정의 역할이 바뀌었습니다. 새로고침한 뒤 다시 시도해 주세요.");
        }
        lockRequestedBoards(requestedBoards);

        Map<Key, MemberPermission> current = readValidRows(memberId);
        Set<Key> added = difference(requested, current.keySet(), KEY_ORDER);
        Set<Key> removed = difference(current.keySet(), requested, KEY_ORDER);

        Map<BoardKey, MemberBoardPermission> currentBoards = readValidBoardRows(memberId);
        Set<BoardKey> addedBoards = difference(requestedBoards, currentBoards.keySet(), BOARD_KEY_ORDER);
        Set<BoardKey> removedBoards = difference(currentBoards.keySet(), requestedBoards, BOARD_KEY_ORDER);

        List<Board> boards = boardRepository.findByDeletedFalseOrderByIdAsc();
        if (added.isEmpty() && removed.isEmpty() && addedBoards.isEmpty() && removedBoards.isEmpty()) {
            return new MemberPermissionUpdateResult(memberId,
                    toMatrix(member, effectiveGrants(current), effectiveBoardGrants(currentBoards), boards),
                    "v" + member.getPermissionVersion() + ": 변경 없음");
        }

        // 대소문자·후행 공백 변형 행 가드: PK가 general_ci·PAD 비교라 수동 삽입된 변형 행이 정상 키와 같은 키로 취급된다.
        // 정확한 행(current)에 없는데 DB PK로는 이미 있다면 그 충돌이다 — 자동 삭제하지 않고 운영 정리를 안내한다.
        for (Key key : added) {
            if (memberPermissionRepository.existsById(new MemberPermissionId(memberId, key.feature().name(), key.action().name()))) {
                throw malformedRowConflict();
            }
        }
        for (BoardKey key : addedBoards) {
            if (memberBoardPermissionRepository.existsById(new MemberBoardPermissionId(memberId, key.boardId(), key.action().name()))) {
                throw malformedRowConflict();
            }
        }

        memberPermissionRepository.deleteAll(removed.stream().map(current::get).toList());
        memberPermissionRepository.saveAll(added.stream()
                .map(key -> new MemberPermission(memberId, key.feature().name(), key.action().name()))
                .toList());
        for (BoardKey key : removedBoards) {
            memberBoardPermissionRepository.deleteKey(memberId, key.boardId(), key.action().name());
        }
        memberBoardPermissionRepository.saveAll(addedBoards.stream()
                .map(key -> new MemberBoardPermission(memberId, key.boardId(), key.action().name()))
                .toList());

        long oldVersion = member.getPermissionVersion();
        member.increasePermissionVersion();
        eventPublisher.publishEvent(new PermissionChangedEvent(memberId));

        List<String> addedItems = new ArrayList<>(notificationLabels(added, addedBoards));
        List<String> removedItems = new ArrayList<>(notificationLabels(removed, removedBoards));
        // 알림 E2 — 실제 변경(추가·회수)이 있을 때만, 커밋 뒤 리스너가 저장한다. 항목은 "공지사항 조회"·"게시판 #3 조회"처럼 코드 상수·숫자다.
        eventPublisher.publishEvent(new NotificationRequestedEvent(memberId, NotificationType.PERMISSION,
                NotificationMessages.permissionChanged(addedItems, removedItems), null));

        List<String> auditAdded = auditItems(added, addedBoards);
        List<String> auditRemoved = auditItems(removed, removedBoards);
        String versionTransition = "v" + oldVersion + "→v" + member.getPermissionVersion();
        logAfterCompletion(currentActorId(), memberId, versionTransition, auditAdded, auditRemoved);

        String label = fitAuditLabel(versionTransition + ": " + changeSummary(auditAdded, auditRemoved));
        // 새 상태 = 요청 집합(검증으로 READ 의존이 이미 보장됨)
        return new MemberPermissionUpdateResult(memberId, toMatrix(member, requested, requestedBoards, boards), label);
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
            if (feature.getKind() == FeatureKind.BOARD_SCOPED) {
                throw new InvalidRequestException(feature.getLabel() + " 권한은 게시판별로(boardGrants) 부여합니다.");
            }
            if (feature.getKind() != FeatureKind.DELEGABLE) {
                throw new InvalidRequestException("위임할 수 없는 기능입니다: " + feature.getLabel());
            }
            // 현재 카탈로그에는 DELEGABLE 기능이 없어(공지는 게시판 권한으로 흡수됨) 도달하지 않는 방어 분기다.
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

    /** 게시판별 요청 검증(DB 접근 없음) — 지원 동작·중복·같은 게시판의 READ 의존. 게시판 존재는 잠금 뒤에 확인한다. */
    private Set<BoardKey> validateBoardGrants(List<MemberPermissionUpdateRequest.BoardGrant> grants) {
        Set<BoardKey> requested = new LinkedHashSet<>();
        for (MemberPermissionUpdateRequest.BoardGrant grant : grants) {
            if (!AdminFeature.BOARD.supports(grant.getAction())) {
                throw new InvalidRequestException("게시판은 " + grant.getAction().getLabel() + " 동작을 지원하지 않습니다.");
            }
            if (!requested.add(new BoardKey(grant.getBoardId(), grant.getAction()))) {
                throw new InvalidRequestException("중복된 게시판 권한 항목이 있습니다.");
            }
        }
        for (BoardKey key : requested) {
            if (key.action() != PermissionAction.READ && !requested.contains(new BoardKey(key.boardId(), PermissionAction.READ))) {
                throw new InvalidRequestException("게시판 #" + key.boardId() + "의 생성·수정·삭제 권한은 조회 권한과 함께 부여해야 합니다.");
            }
        }
        return requested;
    }

    /**
     * 부여할 게시판들을 {@code FOR SHARE}로 잠그고 존재·미삭제를 확인한다. 확인 직후 게시판 삭제가 커밋되어 삭제된 게시판에 권한 행이
     * 생기는 것을 막는다(게시판 삭제는 {@code FOR UPDATE}). 회수 대상 게시판은 잠그지 않는다 — 벌크 삭제가 멱등이라 필요 없다.
     */
    private void lockRequestedBoards(Set<BoardKey> requestedBoards) {
        Set<Long> boardIds = new TreeSet<>();
        requestedBoards.forEach(key -> boardIds.add(key.boardId()));
        if (boardIds.isEmpty()) {
            return;
        }
        Map<Long, Board> locked = new HashMap<>();
        for (Board board : boardRepository.findAllByIdInForShare(boardIds)) {
            locked.put(board.getId(), board);
        }
        for (Long boardId : boardIds) {
            Board board = locked.get(boardId);
            if (board == null || Boolean.TRUE.equals(board.getDeleted())) {
                throw new InvalidRequestException("존재하지 않는 게시판입니다: #" + boardId + " — 새로고침한 뒤 다시 시도해 주세요.");
            }
        }
    }

    private static ConflictException malformedRowConflict() {
        return new ConflictException("권한 테이블에 형식이 올바르지 않은 행이 있어 저장할 수 없습니다. "
                + "운영자가 해당 행을 정리한 뒤 다시 시도해 주세요.");
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

    /** 게시판별 허용 행 중 이 회원의 정확한 행이고 게시판 기능이 지원하는 동작만 읽는다(변형·모르는 동작 행은 판정기가 무시하므로 제외). */
    private Map<BoardKey, MemberBoardPermission> readValidBoardRows(Long memberId) {
        Map<BoardKey, MemberBoardPermission> rows = new TreeMap<>(BOARD_KEY_ORDER);
        for (MemberBoardPermission row : memberBoardPermissionRepository.findByMemberId(memberId)) {
            if (!memberId.equals(row.getMemberId())) {
                continue;
            }
            try {
                PermissionAction action = PermissionAction.valueOf(row.getAction());
                if (AdminFeature.BOARD.supports(action)) {
                    rows.put(new BoardKey(row.getBoardId(), action), row);
                }
            } catch (IllegalArgumentException ignored) {
                // 모르는 동작 행은 판정기가 무시한다
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

    /** 게시판별 유효 허용값: 쓰기 동작은 같은 게시판의 READ가 있을 때만 유효하다. */
    private Set<BoardKey> effectiveBoardGrants(Map<BoardKey, MemberBoardPermission> rows) {
        Set<BoardKey> effective = new TreeSet<>(BOARD_KEY_ORDER);
        for (BoardKey key : rows.keySet()) {
            if (key.action() == PermissionAction.READ || rows.containsKey(new BoardKey(key.boardId(), PermissionAction.READ))) {
                effective.add(key);
            }
        }
        return effective;
    }

    private MemberPermissionMatrixResponse toMatrix(Member member, Set<Key> delegableGrants, Set<BoardKey> boardGrants,
                                                    List<Board> boards) {
        List<MemberPermissionMatrixResponse.ActionColumn> columns = Arrays.stream(PermissionAction.values())
                .map(action -> new MemberPermissionMatrixResponse.ActionColumn(action, action.getLabel()))
                .toList();

        Map<AdminFeature, Set<PermissionAction>> granted = new EnumMap<>(AdminFeature.class);
        for (Key key : delegableGrants) {
            granted.computeIfAbsent(key.feature(), feature -> EnumSet.noneOf(PermissionAction.class)).add(key.action());
        }
        Map<Long, Set<PermissionAction>> grantedByBoard = new HashMap<>();
        for (BoardKey key : boardGrants) {
            grantedByBoard.computeIfAbsent(key.boardId(), id -> EnumSet.noneOf(PermissionAction.class)).add(key.action());
        }
        // 기능 단위 BOARD 행: "어느 게시판에서든 유효한 동작"(판정기의 기능 단위 판정과 같은 의미). 화면은 이 행 대신 boards 표를 그린다.
        Set<PermissionAction> anyBoard = EnumSet.noneOf(PermissionAction.class);
        grantedByBoard.values().forEach(anyBoard::addAll);

        List<MemberPermissionMatrixResponse.FeatureRow> rows = new ArrayList<>();
        for (AdminFeature feature : AdminFeature.values()) {
            Set<PermissionAction> grantedActions = switch (feature.getKind()) {
                case ALWAYS -> feature.getActions();
                case ADMIN_ONLY -> Set.of();
                case DELEGABLE -> granted.getOrDefault(feature, Set.of());
                case BOARD_SCOPED -> anyBoard;
            };
            rows.add(new MemberPermissionMatrixResponse.FeatureRow(feature, feature.getLabel(), feature.getKind(),
                    inActionOrder(feature.getActions()), inActionOrder(grantedActions)));
        }

        List<MemberPermissionMatrixResponse.BoardRow> boardRows = new ArrayList<>();
        for (Board board : boards) {
            boardRows.add(new MemberPermissionMatrixResponse.BoardRow(board.getId(), board.getName(), board.getPublicYn(),
                    board.getAttachmentYn(), inActionOrder(AdminFeature.BOARD.getActions()),
                    inActionOrder(grantedByBoard.getOrDefault(board.getId(), Set.of()))));
        }
        return new MemberPermissionMatrixResponse(member.getId(), member.getUserId(), member.getUserName(),
                member.getStatus(), member.getPermissionVersion(), columns, rows, boardRows);
    }

    private static List<PermissionAction> inActionOrder(Set<PermissionAction> actions) {
        return Arrays.stream(PermissionAction.values()).filter(actions::contains).toList();
    }

    private static <T> Set<T> difference(Set<T> from, Set<T> minus, Comparator<T> order) {
        Set<T> result = new TreeSet<>(order);
        result.addAll(from);
        result.removeAll(minus);
        return result;
    }

    /** 알림 문장용 항목 라벨("공지사항 조회"·"게시판 #3 조회") — 코드 상수·숫자만으로 만들어 사용자 입력(게시판 이름)이 섞이지 않는다. */
    private static List<String> notificationLabels(Set<Key> keys, Set<BoardKey> boardKeys) {
        List<String> labels = new ArrayList<>();
        keys.forEach(key -> labels.add(key.feature().getLabel() + " " + key.action().getLabel()));
        boardKeys.forEach(key -> labels.add(AdminFeature.BOARD.getLabel() + " #" + key.boardId() + " " + key.action().getLabel()));
        return labels;
    }

    /** 감사·로그 항목("공지사항.생성"·"게시판#3.조회") — 코드 상수·숫자만. */
    private static List<String> auditItems(Set<Key> keys, Set<BoardKey> boardKeys) {
        List<String> items = new ArrayList<>();
        keys.forEach(key -> items.add(key.feature().getLabel() + "." + key.action().getLabel()));
        boardKeys.forEach(key -> items.add(AdminFeature.BOARD.getLabel() + "#" + key.boardId() + "." + key.action().getLabel()));
        return items;
    }

    /**
     * 건수 → 회수(-) → 추가(+) 순서(PLAN-board.md 리뷰 R1-7) — 라벨이 상한에서 잘려도 건수와 접근권을 잃은 항목이 먼저 남는다.
     * 예: {@code 추가 1·회수 1 | -공지사항.삭제 | +공지사항.생성}.
     */
    private static String changeSummary(List<String> added, List<String> removed) {
        StringBuilder summary = new StringBuilder("추가 " + added.size() + "·회수 " + removed.size());
        if (!removed.isEmpty()) {
            summary.append(" | ").append(String.join(", ", prefixed("-", removed)));
        }
        if (!added.isEmpty()) {
            summary.append(" | ").append(String.join(", ", prefixed("+", added)));
        }
        return summary.toString();
    }

    private static List<String> prefixed(String prefix, List<String> items) {
        return items.stream().map(item -> prefix + item).toList();
    }

    static String fitAuditLabel(String label) {
        return label.length() <= MAX_AUDIT_LABEL_LENGTH ? label : label.substring(0, MAX_AUDIT_LABEL_LENGTH - 1) + "…";
    }

    /** 실행자 회원 ID(숫자) — 아이디 문자열은 제어문자를 허용해 로그 한 줄을 가를 수 있어 쓰지 않는다(리뷰 R5-2). 식별 불가면 null. */
    private static Long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details ? details.getId() : null;
    }

    /**
     * 전체 diff를 <b>트랜잭션 완료 후</b> 실제 결과와 함께 INFO로 남긴다(PLAN-board.md 리뷰 R2-2·R3-2) — 감사 라벨이 500자에서 잘려도
     * 전체 변경 내역을 추적할 수 있게 하는 보조 기록이다. {@code @TransactionalEventListener}는 완료 상태를 받지 못하므로 동기화를 직접 등록한다.
     *
     * <p>결과 판정: Spring은 <b>커밋 단계에서 예외가 나면(DB는 커밋됐지만 응답이 유실된 경우 포함) 상태를 {@code STATUS_ROLLED_BACK}으로</b>
     * 넘긴다(실측 — 상태 코드만으로는 커밋 응답 유실과 실제 롤백을 구분할 수 없다). 그래서 이 동기화의 {@code beforeCommit}이 불렸는지를 함께 본다 —
     * 커밋 시도 전에 실패했으면(불리지 않음) 확정 롤백이고, 커밋 시도 이후 COMMITTED가 아니면 적용 여부를 모르는 {@code UNKNOWN}이다
     * (확정 변경으로 쓰지 않는다). 값은 전부 숫자·코드 상수다. 캐시 무효화와 무관하다(별도 리스너).
     */
    private static void logAfterCompletion(Long actorId, Long memberId, String versionTransition,
                                           List<String> added, List<String> removed) {
        List<String> addedCopy = List.copyOf(added);
        List<String> removedCopy = List.copyOf(removed);
        Function<String, String> message = result -> "권한 변경 " + result + ": actorMemberId=" + actorId + ", memberId=" + memberId
                + ", " + versionTransition + ", 회수=" + removedCopy + ", 추가=" + addedCopy;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.info(message.apply("NO_TRANSACTION"));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean commitAttempted;

            @Override
            public void beforeCommit(boolean readOnly) {
                commitAttempted = true;
            }

            @Override
            public void afterCompletion(int status) {
                try {
                    String result;
                    if (status == STATUS_COMMITTED) {
                        result = "COMMITTED";
                    } else if (!commitAttempted) {
                        result = "ROLLED_BACK";
                    } else {
                        result = "UNKNOWN(적용 여부 DB 확인 필요)";
                    }
                    log.info(message.apply(result));
                } catch (RuntimeException e) {
                    log.warn("권한 변경 로그 기록 실패(권한 변경 결과와 무관): memberId={}", memberId, e);
                }
            }
        });
    }
}
