package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 참조 교체의 존재·출처 확인은 이미지 행을 id 오름차순으로 {@code FOR SHARE} 읽어 한다(존재 확인과 삭제 방지를 한 문장으로 —
 * PLAN-media-library.md D8). 라이브러리 목록·검색은 {@link ContentImageRepositoryCustom}(QueryDSL)이다.
 */
public interface ContentImageRepository extends JpaRepository<ContentImage, Long>, ContentImageRepositoryCustom {

    /** 삭제 경로 전용 — 이미지 행을 배타 락으로 잡는다. 잠금 순서는 가드 → 소유자 → 이미지 → 참조 → 카운터다(PLAN-media-library.md D5). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ContentImage i where i.id = :id")
    Optional<ContentImage> findByIdForUpdate(@Param("id") Long id);

    /** 배너·팝업의 명시적 선택 전용 — 이미지 행을 공유 락으로 잡아 선택이 끝날 때까지 삭제를 막는다. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select i from ContentImage i where i.id = :id")
    Optional<ContentImage> findByIdForShare(@Param("id") Long id);

    /** 게시글 본문 참조 교체 전용 — 요청된 이미지 중 존재하는 것만 id 오름차순으로 공유 락을 잡고 읽는다(게시글 간 교착 방지). */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select i from ContentImage i where i.id in :ids order by i.id asc")
    List<ContentImage> findAllByIdForShare(@Param("ids") Collection<Long> ids);
}
