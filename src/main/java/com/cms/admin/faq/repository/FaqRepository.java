package com.cms.admin.faq.repository;

import com.cms.admin.faq.domain.Faq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FaqRepository extends JpaRepository<Faq, Long> {

    /** 관리 목록 — 노출 여부와 무관하게 전부. 호출자가 카테고리별로 묶는다. */
    List<Faq> findAllByOrderByCategoryIdAscOrdAscIdAsc();

    List<Faq> findByCategoryIdOrderByOrdAscIdAsc(Long categoryId);

    boolean existsByCategoryId(Long categoryId);

    @Query("select coalesce(max(f.ord), -1) from Faq f where f.categoryId = :categoryId")
    int findMaxOrdByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 공개 페이지용 — 노출 중인 카테고리 ∧ 노출 중인 FAQ ∧ <b>카테고리가 공통이거나 {@code siteId} 전용</b>인 것만, (카테고리 순서, FAQ 순서)대로. 공개 불변식이 쿼리에 고정돼 있어 호출자가 필터를 빠뜨릴 수 없다
     * (PLAN-faq.md 쟁점 8). 카테고리 정보도 함께 가져와 한 번의 쿼리로 묶는다.
     */
    @Query("""
            select new com.cms.admin.faq.repository.PublicFaqRow(c.id, c.name, f.id, f.question, f.answerHtml)
            from Faq f, FaqCategory c
            where f.categoryId = c.id
              and c.useYn = true
              and f.useYn = true
              and (c.siteId is null or c.siteId = :siteId)
            order by c.ord asc, c.id asc, f.ord asc, f.id asc
            """)
    List<PublicFaqRow> findPublicRows(@Param("siteId") Long siteId);
}
