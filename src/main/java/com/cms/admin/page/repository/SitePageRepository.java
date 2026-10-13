package com.cms.admin.page.repository;

import com.cms.admin.page.domain.SitePage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SitePageRepository extends JpaRepository<SitePage, Long> {

    /** 사이트 삭제 가드용(PLAN-site-menu.md PR ③) — 그 사이트 전용 페이지가 있는가. */
    boolean existsBySiteId(Long siteId);

    boolean existsBySlug(String slug);

    /** 수정·삭제 전용 — 대상 페이지 행 하나를 비관적 락으로 잡는다(개정 번호 직렬화·낡은 화면 검사의 기준). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SitePage p where p.id = :id")
    Optional<SitePage> findByIdForUpdate(@Param("id") Long id);

    /** 관리 목록 — 노출 여부와 무관하게 전부, 최근에 만든 순. 현재 개정의 제목만 읽는다. */
    @Query("""
            select new com.cms.admin.page.repository.SitePageSummaryRow(
                p.id, p.slug, r.title, p.currentRevisionNo, p.version, p.useYn, p.footerYn, p.siteId, p.createDate, p.updateDate)
            from SitePage p, SitePageRevision r
            where r.pageId = p.id and r.revisionNo = p.currentRevisionNo
            order by p.id desc
            """)
    List<SitePageSummaryRow> findAllSummaries();

    /**
     * <b>공개 쿼리 4개 공통</b>: 사이트 소속 — 공통이거나 {@code siteId}와 같은 페이지만 조회된다. 다른 사이트 전용 페이지는 비노출·없음과 같은
     * 결과(empty)다(PLAN-site-menu.md PR ③ S3). 조건이 쿼리에 고정돼 호출자가 빠뜨릴 수 없다.
     *
     * <p>공개 현재 버전 — 노출 여부({@code useYn})가 쿼리에 고정돼 있어 비노출 페이지는 조회되지 않는다. 페이지와 현재 개정을 한 쿼리로 읽어
     * 개정 INSERT와 페이지 UPDATE가 한 트랜잭션이므로 불일치가 없다(쟁점 10).
     */
    @Query("""
            select new com.cms.admin.page.repository.PublicSitePageRow(
                p.id, p.slug, r.title, r.content, r.revisionNo, r.createDate, p.currentRevisionNo)
            from SitePage p, SitePageRevision r
            where p.slug = :slug and p.useYn = true
              and (p.siteId is null or p.siteId = :siteId)
              and r.pageId = p.id and r.revisionNo = p.currentRevisionNo
            """)
    Optional<PublicSitePageRow> findPublicCurrent(@Param("slug") String slug, @Param("siteId") Long siteId);

    /** 공개 특정 개정 — {@link #findPublicCurrent}와 같은 노출 조건. 없는 개정은 비어 있다. */
    @Query("""
            select new com.cms.admin.page.repository.PublicSitePageRow(
                p.id, p.slug, r.title, r.content, r.revisionNo, r.createDate, p.currentRevisionNo)
            from SitePage p, SitePageRevision r
            where p.slug = :slug and p.useYn = true
              and (p.siteId is null or p.siteId = :siteId)
              and r.pageId = p.id and r.revisionNo = :revisionNo
            """)
    Optional<PublicSitePageRow> findPublicRevision(@Param("slug") String slug, @Param("revisionNo") int revisionNo,
                                                   @Param("siteId") Long siteId);

    /** 공개 개정 목록의 머리 — 노출 중인 페이지의 현재 제목과 현재 개정 번호(본문 미조회). */
    @Query("""
            select new com.cms.admin.page.repository.PublicSitePageHeaderRow(p.id, p.slug, r.title, p.currentRevisionNo)
            from SitePage p, SitePageRevision r
            where p.slug = :slug and p.useYn = true
              and (p.siteId is null or p.siteId = :siteId)
              and r.pageId = p.id and r.revisionNo = p.currentRevisionNo
            """)
    Optional<PublicSitePageHeaderRow> findPublicHeader(@Param("slug") String slug, @Param("siteId") Long siteId);

    /** 공개 메인 푸터 링크 — 노출 중이고 푸터로 지정된 페이지를 만든 순으로(본문 미조회). */
    @Query("""
            select new com.cms.admin.page.repository.PublicSitePageHeaderRow(p.id, p.slug, r.title, p.currentRevisionNo)
            from SitePage p, SitePageRevision r
            where p.useYn = true and p.footerYn = true
              and (p.siteId is null or p.siteId = :siteId)
              and r.pageId = p.id and r.revisionNo = p.currentRevisionNo
            order by p.id asc
            """)
    List<PublicSitePageHeaderRow> findFooterLinks(@Param("siteId") Long siteId);
}
