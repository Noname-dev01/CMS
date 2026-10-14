package com.cms.admin.board.repository;

import com.cms.admin.board.domain.Board;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Long> {

    /** 삭제되지 않은 게시판 전체(id 순) — 게시판 관리·권한관리 매트릭스·내 게시판 목록용. */
    List<Board> findByDeletedFalseOrderByIdAsc();

    Optional<Board> findByIdAndDeletedFalse(Long id);

    /** 게시판 수정 전용 — 같은 게시판의 동시 수정을 직렬화한다(공지·메뉴와 같은 명시적 {@code @Query} + {@code @Lock} 패턴). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Board b where b.id = :id and b.deleted = false")
    Optional<Board> findByIdAndDeletedFalseForUpdate(@Param("id") Long id);

    /**
     * 권한 저장이 부여할 게시판들을 공유 잠금으로 읽는다(PLAN-board.md 쟁점 5) — 확인 직후 게시판 삭제(PR B, {@code FOR UPDATE})가
     * 끼어들어 삭제된 게시판에 권한 행이 생기는 것을 막는다. id 순으로 잠가 같은 집합을 잠그는 요청끼리 순서가 같다.
     * 삭제 여부는 호출자가 결과에서 확인한다(삭제된 행도 잠가야 "존재하지 않음"을 일관되게 판정한다).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from Board b where b.id in :ids order by b.id")
    List<Board> findAllByIdInForShare(@Param("ids") Collection<Long> ids);

    /**
     * 게시글 생성 전용 — 게시판 행을 공유 잠금으로 읽어 게시판 삭제(`FOR UPDATE`)와 직렬화한다(PLAN-board.md 쟁점 7). 삭제 검사 직후에 게시글이
     * 생겨 고아가 되는 것을 막는다. 삭제된 게시판은 비어 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from Board b where b.id = :id and b.deleted = false")
    Optional<Board> findByIdAndDeletedFalseForShare(@Param("id") Long id);

    /** 시스템 게시판 ID(공지 게시판 등). 삭제된 게시판이거나 키가 없으면 비어 있다 — 호출자(공개 {@code /notices})는 비어 있으면 404로 닫는다. */
    @Query("select b.id from Board b where b.boardKey = :boardKey and b.deleted = false")
    Optional<Long> findIdByBoardKey(@Param("boardKey") String boardKey);

    /**
     * 현재 사이트의 공지 게시판 ID(PLAN-site-menu.md PR ④ T3). 그 사이트 전용 공지 게시판({@code NOTICE:{siteId}})이 살아 있으면 그것, 없으면
     * 공통 공지 게시판({@code NOTICE})이다. 전용이 있으면 공통을 <b>대체</b>한다(합치지 않는다). 둘 다 없으면 비어 있다.
     */
    default Optional<Long> findNoticeBoardId(Long siteId) {
        return findIdByBoardKey(Board.noticeKeyFor(siteId)).or(() -> findIdByBoardKey(Board.NOTICE_KEY));
    }

    /**
     * 공개 화면 전용 — 공개 게시판(publicYn)이면서 삭제되지 않았고, 공통이거나 현재 사이트 전용인 게시판만(PR ④). 조건이 쿼리에 고정돼 공개 불변식이
     * 실수로 깨지지 않으며, 다른 사이트 전용 게시판은 없는 ID와 구분되지 않는다(호출자는 같은 404로 흡수한다).
     */
    @Query("select b from Board b where b.id = :id and b.deleted = false and b.publicYn = true"
            + " and (b.siteId is null or b.siteId = :siteId)")
    Optional<Board> findPublicById(@Param("id") Long id, @Param("siteId") Long siteId);

    /** 사이트 삭제 가드용(PR ④) — 그 사이트 소속 게시판 행이 있는가(삭제된 행 포함 — FK RESTRICT와 같은 범위). */
    boolean existsBySiteId(Long siteId);

    /** 사이트 전용 공지 게시판 중복 생성 사전 검사(살아 있는 행만 키를 가진다 — 소프트 삭제가 키를 비운다). */
    boolean existsByBoardKey(String boardKey);
}
