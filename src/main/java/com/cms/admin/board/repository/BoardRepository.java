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

    /** 공개 화면 전용 — 공개 게시판(publicYn)이면서 삭제되지 않은 게시판만. 조건이 메서드명으로 고정돼 공개 불변식이 실수로 깨지지 않는다. */
    Optional<Board> findByIdAndDeletedFalseAndPublicYnTrue(Long id);
}
