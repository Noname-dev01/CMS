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
}
