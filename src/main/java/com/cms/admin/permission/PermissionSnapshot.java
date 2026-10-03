package com.cms.admin.permission;

import java.util.Set;

/**
 * 한 시점의 DB 허용 행(회원 × 기능 × 동작) 불변 스냅샷. 판정기는 요청마다 하나를 받아 쓴다.
 * 행이 있으면 허용이다(거부 행은 없다).
 */
public final class PermissionSnapshot {

    /** 허용 행이 하나도 없는 스냅샷 — 로드 실패 시 fail-closed 판정에 쓴다. */
    public static final PermissionSnapshot EMPTY = new PermissionSnapshot(Set.of());

    public record Grant(Long memberId, AdminFeature feature, PermissionAction action) { }

    private final Set<Grant> grants;

    public PermissionSnapshot(Set<Grant> grants) {
        this.grants = Set.copyOf(grants);
    }

    /** 회원 ID가 null이면(식별할 수 없는 주체) 항상 false다. */
    public boolean has(Long memberId, AdminFeature feature, PermissionAction action) {
        return memberId != null && grants.contains(new Grant(memberId, feature, action));
    }

    public Set<Grant> grants() {
        return grants;
    }
}
