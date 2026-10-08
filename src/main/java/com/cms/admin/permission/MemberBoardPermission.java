package com.cms.admin.permission;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원이 게시판별로 허용받은 동작 한 건(PLAN-board.md 쟁점 1). <b>행이 있으면 허용</b>이고 거부 행은 없다. ADMIN 행은 두지 않는다.
 * 기능 단위 {@link MemberPermission}과 별도 테이블이라 공지 권한 경로를 건드리지 않는다. {@code action}은 DB enum이 아닌 VARCHAR다
 * (기존 테이블과 같은 관대한 파싱 — 모르는 동작 행은 스냅샷 빌더가 무시한다). 연관관계 매핑 없이 ID 값만 가진다.
 */
@Entity
@Table(name = "member_board_permission")
@IdClass(MemberBoardPermissionId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBoardPermission {

    @Id
    private Long memberId;

    @Id
    private Long boardId;

    @Id
    private String action;

    public MemberBoardPermission(Long memberId, Long boardId, String action) {
        this.memberId = memberId;
        this.boardId = boardId;
        this.action = action;
    }
}
