package com.cms.admin.banner.repository;

import com.cms.admin.banner.domain.Banner;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BannerRepository extends JpaRepository<Banner, Long> {

    /** 관리 목록 — 노출 여부·기간과 무관하게 전부, 표시 순서대로. */
    List<Banner> findAllByOrderByOrdAscIdAsc();

    /** 수정·삭제 전용 — 대상 행 하나를 비관적 락으로 잡는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Banner b where b.id = :id")
    Optional<Banner> findByIdForUpdate(@Param("id") Long id);

    /**
     * 순서 저장 전용 — 가드 행을 잡은 뒤 모든 배너 행을 id 순으로 잠그고 읽는다. 단건 수정은 가드 없이 행 락만 잡으므로,
     * 비잠금 스냅샷으로 전 컬럼을 UPDATE하면 방금 커밋된 수정(노출 여부 등)을 이전 값으로 되돌릴 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Banner b order by b.id")
    List<Banner> findAllForUpdate();

    /** 새 배너의 {@code ord} 계산용(가드 행을 잡은 뒤 호출). 배너가 없으면 -1. */
    @Query("select coalesce(max(b.ord), -1) from Banner b")
    int findMaxOrd();

    /**
     * 공개 메인용 — 노출 여부와 기간 {@code [start, end)}이 조건에 고정돼 있다. 이 조건은 {@code DisplayPeriod.isActive}와 같은 의미이고
     * 두 표현의 일치는 DB 시험이 고정한다. 판정 시각은 호출자(공개 서비스)가 주입된 {@code Clock}으로 1회 산출해 넘긴다.
     */
    @Query("""
            select b from Banner b
            where b.useYn = true
              and (b.displayStart is null or b.displayStart <= :now)
              and (b.displayEnd is null or b.displayEnd > :now)
            order by b.ord asc, b.id asc
            """)
    List<Banner> findDisplayable(@Param("now") LocalDateTime now);

    /** 공개 이미지 다운로드용 — {@link #findDisplayable}과 같은 조건 + id. 조건이 쿼리에 고정돼 노출 재검증을 건너뛸 수 없다. */
    @Query("""
            select b from Banner b
            where b.id = :id
              and b.useYn = true
              and (b.displayStart is null or b.displayStart <= :now)
              and (b.displayEnd is null or b.displayEnd > :now)
            """)
    Optional<Banner> findDisplayableById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
