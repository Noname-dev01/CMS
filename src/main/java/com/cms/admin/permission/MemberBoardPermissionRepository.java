package com.cms.admin.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemberBoardPermissionRepository extends JpaRepository<MemberBoardPermission, MemberBoardPermissionId> {

    /** 게시판별 허용 행 전체를 (memberId, boardId, action) 값으로만 읽는다 — 영속성 컨텍스트에 엔티티를 올리지 않는다. */
    @Query("select p.memberId, p.boardId, p.action from MemberBoardPermission p")
    List<Object[]> findAllGrantRows();

    /** 한 회원의 게시판별 허용 행(권한관리 조회·저장용). action은 DB 콜레이션(general_ci)으로 비교되므로 호출자가 정확 일치를 다시 확인한다. */
    List<MemberBoardPermission> findByMemberId(Long memberId);

    /** 역할이 바뀐 회원의 게시판별 허용 행을 변형 행까지 전부 지운다. 삭제 건수를 돌려준다. */
    @Modifying
    @Query("delete from MemberBoardPermission p where p.memberId = :memberId")
    int deleteByMemberId(@Param("memberId") Long memberId);

    /**
     * 키 하나를 벌크로 지운다 — 이미 지워진 행이면 0건으로 끝난다(멱등, PLAN-board.md 쟁점 5·리뷰 R3-3). 엔티티 삭제는 회수 대상 게시판이
     * 동시에 삭제된 경우 stale-state 예외가 나므로 쓰지 않는다. 영속성 컨텍스트는 비우지 않는다(호출 트랜잭션의 회원 버전 변경이 남아야 한다).
     */
    @Modifying
    @Query("delete from MemberBoardPermission p where p.memberId = :memberId and p.boardId = :boardId and p.action = :action")
    int deleteKey(@Param("memberId") Long memberId, @Param("boardId") Long boardId, @Param("action") String action);
}
