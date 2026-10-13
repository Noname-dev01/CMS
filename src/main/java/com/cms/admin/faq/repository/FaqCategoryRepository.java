package com.cms.admin.faq.repository;

import com.cms.admin.faq.domain.FaqCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FaqCategoryRepository extends JpaRepository<FaqCategory, Long> {

    /** 사이트 삭제 가드용(PLAN-site-menu.md PR ③) — 그 사이트 전용 행이 있는가. */
    boolean existsBySiteId(Long siteId);

    /** 관리 목록 — 노출 여부와 무관하게 전부, 표시 순서대로. 총 행 수는 서비스의 정확한 상한이 제한한다. */
    List<FaqCategory> findAllByOrderByOrdAscIdAsc();

    @Query("select coalesce(max(c.ord), -1) from FaqCategory c")
    int findMaxOrd();
}
