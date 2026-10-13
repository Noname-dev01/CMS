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

    /** 사이트 삭제 가드용(PLAN-site-menu.md PR ③) — 그 사이트 전용 행이 있는가. */
    boolean existsBySiteId(Long siteId);

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
     * 공개 메인용 — 노출 여부와 기간 {@code [start, end)}, <b>사이트 소속(공통이거나 {@code siteId}와 같음)</b>이 조건에 고정돼 있다. 이 조건은 {@code DisplayPeriod.isActive}와 같은 의미이고
     * 두 표현의 일치는 DB 시험이 고정한다. 판정 시각은 호출자(공개 서비스)가 주입된 {@code Clock}으로 1회 산출해 넘긴다.
     * <b>이미지 연결이 없는 배너는 제외한다</b>(PLAN-media-library.md R3-2 — 적격성은 소유자 쿼리에서 판정하고 이미지는 호출자가 일괄 조회로 붙인다).
     */
    @Query("""
            select b from Banner b
            where b.useYn = true
              and (b.displayStart is null or b.displayStart <= :now)
              and (b.displayEnd is null or b.displayEnd > :now)
              and (b.siteId is null or b.siteId = :siteId)
              and exists (select 1 from ContentImageRef r, ContentImage i
                          where r.ownerType = 'BANNER' and r.ownerId = b.id and i.id = r.imageId)
            order by b.ord asc, b.id asc
            """)
    List<Banner> findDisplayable(@Param("now") LocalDateTime now, @Param("siteId") Long siteId);

    /**
     * 공개 이미지 다운로드용 — {@link #findDisplayable}과 같은 노출 조건 + id. 이미지 경로는 사이트 맥락이 없으므로 소속 사이트 일치는 보지 않고,
     * <b>소속 사이트가 비활성이면 제외</b>한다(사이트를 끄면 그 전용 콘텐츠가 공개에서 사라져야 한다 — PLAN-site-menu.md PR ③ S3).
     * 조건이 쿼리에 고정돼 노출 재검증을 건너뛸 수 없다.
     */
    @Query("""
            select b from Banner b
            where b.id = :id
              and b.useYn = true
              and (b.displayStart is null or b.displayStart <= :now)
              and (b.displayEnd is null or b.displayEnd > :now)
              and (b.siteId is null or exists (select 1 from Site s where s.id = b.siteId and s.useYn = true))
              and exists (select 1 from ContentImageRef r, ContentImage i
                          where r.ownerType = 'BANNER' and r.ownerId = b.id and i.id = r.imageId)
            """)
    Optional<Banner> findDisplayableById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
