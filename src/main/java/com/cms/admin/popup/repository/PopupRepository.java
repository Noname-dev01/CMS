package com.cms.admin.popup.repository;

import com.cms.admin.popup.domain.Popup;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PopupRepository extends JpaRepository<Popup, Long> {

    /** 관리 목록 — 노출 여부·기간과 무관하게 전부, 최근 등록 순. 총 행 수는 서비스의 소프트 상한이 제한한다. */
    List<Popup> findAllByOrderByIdDesc();

    /**
     * 수정·삭제 전용 — 대상 행 하나를 비관적 락으로 잡는다. 수정은 이 락 아래에서 {@code revision}을 올리므로 이 선언이 빠지면 동시 수정이
     * 같은 값을 읽고 증가분이 유실된다(PLAN-popup.md R4-2 — {@code PopupConcurrencyIntegrationTest}가 변이 실험으로 고정).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Popup p where p.id = :id")
    Optional<Popup> findByIdForUpdate(@Param("id") Long id);

    /**
     * 공개 메인용 — 노출 여부와 기간 {@code [start, end)}이 조건에 고정돼 있다. 이 조건은 {@code DisplayPeriod.isActive}와 같은 의미이고
     * 두 표현의 일치는 DB 시험이 고정한다. 판정 시각은 호출자(공개 서비스)가 주입된 {@code Clock}으로 1회 산출해 넘기고, 건수는 {@code pageable}로 자른다.
     * <b>적격성(HTML형이거나 이미지 연결이 있는 이미지형)을 {@code LIMIT} 전에 판정</b>한다 — 이미지 연결이 없는 불완전한 이미지형이 5개 슬롯을 차지해
     * 정상 팝업이 밀려나지 않게 한다(PLAN-media-library.md R3-2).
     */
    @Query("""
            select p from Popup p
            where p.useYn = true
              and (p.displayStart is null or p.displayStart <= :now)
              and (p.displayEnd is null or p.displayEnd > :now)
              and (p.popupType = com.cms.admin.popup.domain.PopupType.HTML
                   or exists (select 1 from ContentImageRef r, ContentImage i
                              where r.ownerType = 'POPUP' and r.ownerId = p.id and i.id = r.imageId))
            order by p.id asc
            """)
    List<Popup> findDisplayable(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 공개 이미지 다운로드용 — {@link #findDisplayable}과 같은 조건 + id + <b>이미지형</b>. 조건이 쿼리에 고정돼 노출 재검증을 건너뛸 수 없고,
     * HTML형 팝업의 이미지 요청은 행이 없는 것과 같은 결과가 된다.
     */
    @Query("""
            select p from Popup p
            where p.id = :id
              and p.popupType = com.cms.admin.popup.domain.PopupType.IMAGE
              and p.useYn = true
              and (p.displayStart is null or p.displayStart <= :now)
              and (p.displayEnd is null or p.displayEnd > :now)
              and exists (select 1 from ContentImageRef r, ContentImage i
                          where r.ownerType = 'POPUP' and r.ownerId = p.id and i.id = r.imageId)
            """)
    Optional<Popup> findDisplayableImageById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
