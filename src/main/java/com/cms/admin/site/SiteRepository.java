package com.cms.admin.site;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    List<Site> findAllByOrderByIdAsc();

    boolean existsBySiteKey(String siteKey);

    /**
     * 사이트 행을 PESSIMISTIC_WRITE로 잠가 읽는다 — 그 사이트의 메뉴·사이트 쓰기가 모두 지나는 단일 문(PLAN-site-menu.md D4).
     * 호출 트랜잭션은 READ_COMMITTED여야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Site s where s.id = :id")
    Optional<Site> findByIdForUpdate(@Param("id") Long id);

    /**
     * 모든 사이트 행을 id 오름차순으로 잠가 읽는다 — 기본 사이트 변경 전용(PLAN-site-menu.md PR ② P2-5). 옛 기본·새 기본 두 행을 함께 바꾸므로
     * 고정 순서(id 오름차순)로 전부 잠가 기본 변경끼리의 교착을 막는다. 호출 트랜잭션은 READ_COMMITTED여야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Site s order by s.id asc")
    List<Site> findAllForUpdate();

    /** 공개 라우팅 해석 — 공개 타입이고 활성인 사이트만(PR ② P2-1). */
    @Query("select s from Site s where s.siteKey = :siteKey and s.siteType = com.cms.admin.site.SiteType.PUBLIC and s.useYn = true")
    Optional<Site> findActivePublicByKey(@Param("siteKey") String siteKey);

    /** 기본 사이트 후보 — 표시가 붙은 모든 행(0개·2개 이상이면 호출자가 fail-closed). */
    List<Site> findByDefaultYnTrue();
}
