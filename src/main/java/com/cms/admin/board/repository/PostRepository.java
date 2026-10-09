package com.cms.admin.board.repository;

import com.cms.admin.board.domain.Post;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long>, PostRepositoryCustom {

    /** 관리 단건 조회용 — 락 없음. 삭제된 게시글과 다른 게시판 소속 게시글은 대상에서 제외한다(IDOR 방지: 경로의 게시판과 소속이 같아야 한다). */
    Optional<Post> findByIdAndBoardIdAndDeletedFalse(Long id, Long boardId);

    /**
     * 공개(비로그인) 상세 조회 전용. 조건(게시판 소속·노출·미삭제)이 메서드명으로 고정돼 공개 조회 불변식이 실수로 깨지지 않는다.
     * 게시판의 공개 여부는 {@code PublicBoardService}가 별도로 확인한다.
     */
    Optional<Post> findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(Long id, Long boardId);

    /** 게시판 삭제 검사 — 살아 있는(소프트 삭제되지 않은) 게시글이 하나라도 있는지(노출 여부 무관). */
    boolean existsByBoardIdAndDeletedFalse(Long boardId);

    /**
     * PATCH·DELETE·첨부 업로드·첨부 삭제 전용 — 비관적 락으로 대상 row를 잠근다. 공지와 같은 명시적 {@code @Query} + {@code @Lock} 패턴.
     * 첨부 업로드·삭제도 이 락을 재사용해 같은 게시글의 첨부 개수 상한 검사와 동시 삭제 경합을 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id and p.boardId = :boardId and p.deleted = false")
    Optional<Post> findByIdAndBoardIdAndDeletedFalseForUpdate(@Param("id") Long id, @Param("boardId") Long boardId);
}
