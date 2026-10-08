package com.cms.admin.permission;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 한 시점의 DB 허용 행 불변 스냅샷 — 기능 단위(회원 × 기능 × 동작)와 게시판 단위(회원 × 게시판 × 동작). 판정기는 요청마다 하나를 받아 쓴다.
 * 행이 있으면 허용이다(거부 행은 없다). 두 집합은 같은 읽기 트랜잭션에서 적재된다({@link PermissionCache}).
 */
public final class PermissionSnapshot {

    /** 허용 행이 하나도 없는 스냅샷 — 로드 실패 시 fail-closed 판정에 쓴다. */
    public static final PermissionSnapshot EMPTY = new PermissionSnapshot(Set.of(), Set.of());

    public record Grant(Long memberId, AdminFeature feature, PermissionAction action) { }

    public record BoardGrant(Long memberId, Long boardId, PermissionAction action) { }

    private final Set<Grant> grants;
    private final Set<BoardGrant> boardGrants;

    public PermissionSnapshot(Set<Grant> grants) {
        this(grants, Set.of());
    }

    public PermissionSnapshot(Set<Grant> grants, Set<BoardGrant> boardGrants) {
        this.grants = Set.copyOf(grants);
        this.boardGrants = Set.copyOf(boardGrants);
    }

    /** 회원 ID가 null이면(식별할 수 없는 주체) 항상 false다. */
    public boolean has(Long memberId, AdminFeature feature, PermissionAction action) {
        return memberId != null && grants.contains(new Grant(memberId, feature, action));
    }

    /** 게시판 단위 행 존재 여부(의존 규칙 미적용 — 판정기가 READ 의존을 함께 본다). 회원·게시판 ID가 null이면 false. */
    public boolean hasBoard(Long memberId, Long boardId, PermissionAction action) {
        return memberId != null && boardId != null && boardGrants.contains(new BoardGrant(memberId, boardId, action));
    }

    /**
     * 그 회원이 동작을 <b>유효하게</b> 가진 게시판 ID(쓰기 동작은 같은 게시판의 READ도 있어야 한다 — 의존 규칙).
     * 기능 단위 판정("어느 게시판이든")과 게시판 목록 필터가 쓴다.
     */
    public Set<Long> boardIds(Long memberId, PermissionAction action) {
        Set<Long> ids = new LinkedHashSet<>();
        if (memberId == null) {
            return ids;
        }
        for (BoardGrant grant : boardGrants) {
            if (grant.memberId().equals(memberId) && grant.action() == action
                    && (action == PermissionAction.READ || hasBoard(memberId, grant.boardId(), PermissionAction.READ))) {
                ids.add(grant.boardId());
            }
        }
        return ids;
    }

    public Set<Grant> grants() {
        return grants;
    }

    public Set<BoardGrant> boardGrants() {
        return boardGrants;
    }
}
