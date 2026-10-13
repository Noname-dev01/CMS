package com.cms.admin.menu;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    /** 트리 조회 - 한 사이트의 비활성 포함 전체 */
    List<Menu> findAllBySiteIdOrderByOrdAscMenuNoAsc(Long siteId);

    /** 트리 조회 - 한 사이트의 활성 메뉴만 */
    List<Menu> findAllBySiteIdAndUseYnTrueOrderByOrdAscMenuNoAsc(Long siteId);

    /** 메뉴가 속한 사이트 ID만 읽는다(스칼라 투영 — 엔티티를 영속성 컨텍스트에 올리지 않는다). 단일 행 연산이 사이트 행을 먼저 잠그려고 쓴다. */
    @Query("select m.siteId from Menu m where m.menuNo = :menuNo")
    Optional<Long> findSiteIdByMenuNo(@Param("menuNo") Long menuNo);

    /** 사이트 삭제 가드 — 메뉴가 하나라도 있는가. */
    boolean existsBySiteId(Long siteId);

    /** 링크 정책 강화 가드 — 절대(외부) URL을 가진 메뉴 수. */
    @Query("select count(m) from Menu m where m.siteId = :siteId and (lower(m.menuUrl) like 'http://%' or lower(m.menuUrl) like 'https://%')")
    long countAbsoluteUrlMenus(@Param("siteId") Long siteId);

    /** 사이트 목록의 메뉴 개수 표시용 — [siteId, count] 행. */
    @Query("select m.siteId, count(m) from Menu m group by m.siteId")
    List<Object[]> countGroupedBySite();

    /** 비활성화 시 활성 하위 메뉴 존재 여부 확인 */
    boolean existsByUpMenuNoAndUseYnTrue(Long upMenuNo);

    /** 영구삭제 시 하위 메뉴 존재 여부 확인 — 활성·비활성 무관(비활성 자식도 재활성화되면 부모가 필요하다) */
    boolean existsByUpMenuNo(Long upMenuNo);

    /** 한 사이트의 최상위(upMenuNo IS NULL) 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.siteId = :siteId and m.upMenuNo is null")
    Integer findMaxOrdBySiteIdAndUpMenuNoIsNull(@Param("siteId") Long siteId);

    /** 지정 부모 아래 형제 중 최대 ord (형제 없으면 null) */
    @Query("select max(m.ord) from Menu m where m.upMenuNo = :upMenuNo")
    Integer findMaxOrdByUpMenuNo(@Param("upMenuNo") Long upMenuNo);

    /**
     * 한 사이트의 메뉴 행을 menuNo 오름차순으로 PESSIMISTIC_WRITE 잠금하며 읽는다 — 구조 반영·생성이 쓴다(PLAN-menu-structure-apply.md 결정 2·9).
     * 사이트 행 잠금(단일 문, PLAN-site-menu.md D4) 뒤에 부르며, 사이트 문을 모르는 쓰기(롤백 구간의 이전 앱 등)에 대한 방어와
     * 기존 동작 보존을 위해 유지한다. 다른 사이트의 행은 잠그지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Menu m where m.siteId = :siteId order by m.menuNo asc")
    List<Menu> findAllBySiteIdForUpdate(@Param("siteId") Long siteId);

    /**
     * 생성/재활성화 시 부모 row, 비활성화(PATCH useYn=false)·영구삭제(DELETE) 시 대상 row를
     * PESSIMISTIC_WRITE로 잠근 뒤 조회한다. 잠금 획득 → 검증 → 상태 반영이 한 트랜잭션에서
     * 직렬화되도록 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Menu m where m.menuNo = :menuNo")
    Optional<Menu> findByIdForUpdate(@Param("menuNo") Long menuNo);
}
