package com.cms.admin.page.repository;

import com.cms.admin.page.domain.SitePageRevision;
import com.cms.admin.page.domain.SitePageRevisionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SitePageRevisionRepository extends JpaRepository<SitePageRevision, SitePageRevisionId> {

    /**
     * 개정 번호 구간 조회 — 개정은 번호가 연속이라 OFFSET 없이 PK 범위로 읽는다(쪽 번호와 무관한 비용, PLAN-static-page.md R3-2).
     * 본문은 읽지 않는다. 번호가 큰 것(최신)부터.
     */
    @Query("""
            select new com.cms.admin.page.repository.SitePageRevisionSummaryRow(r.revisionNo, r.title, r.createDate)
            from SitePageRevision r
            where r.pageId = :pageId and r.revisionNo between :low and :high
            order by r.revisionNo desc
            """)
    List<SitePageRevisionSummaryRow> findSummaries(@Param("pageId") Long pageId, @Param("low") int low, @Param("high") int high);

    /** 페이지 삭제 전용 — 페이지 행을 잠근 트랜잭션 안에서 개정을 먼저 지운다(FK RESTRICT). */
    @Modifying(flushAutomatically = true)
    @Query("delete from SitePageRevision r where r.pageId = :pageId")
    int deleteByPageId(@Param("pageId") Long pageId);
}
