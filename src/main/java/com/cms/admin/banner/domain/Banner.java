package com.cms.admin.banner.domain;

import com.cms.common.display.DisplayPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공개 메인(/)의 배너(PLAN-public-home-banner.md 쟁점 2). 이미지와의 연결은 이 테이블의 컬럼이 아니라
 * {@code content_image_ref(owner_type='BANNER')}가 한 곳에서 가진다(PLAN-media-library.md D4). 노출은 {@code useYn ∧ [displayStart, displayEnd)}를 둘 다 만족할 때다 — 기간이 비어 있으면 {@code useYn}만 본다.
 * 하드 삭제라 소프트 삭제 컬럼이 없다.
 */
@Entity
@Table(name = "banner")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 관리용 이름이자 공개 화면 이미지의 대체 텍스트. */
    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "display_start")
    private LocalDateTime displayStart;

    @Column(name = "display_end")
    private LocalDateTime displayEnd;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    @Column(nullable = false)
    private Integer ord;

    /** 소속 사이트. {@code null} = 모든 공개 사이트 공통. 공개 사이트(PUBLIC)만 가리킬 수 있다(서비스가 검증). */
    @Column(name = "site_id")
    private Long siteId;

    /** 낡은 화면 검사용 — 메타데이터 교체({@link #replaceMetadata})마다 +1. 순서 변경은 올리지 않는다. */
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /**
     * 메타데이터 전체 교체(PUT). 링크·시작·종료의 {@code null}은 "해제"다. 기간은 호출자가 {@link DisplayPeriod#normalize}·
     * {@link DisplayPeriod#requireValid}를 마친 값이어야 한다.
     */
    public void replaceMetadata(String title, String linkUrl, LocalDateTime displayStart, LocalDateTime displayEnd,
                                boolean useYn, Long siteId, LocalDateTime now) {
        this.version = Math.incrementExact(this.version);
        this.siteId = siteId;
        this.title = title;
        this.linkUrl = linkUrl;
        this.displayStart = displayStart;
        this.displayEnd = displayEnd;
        this.useYn = useYn;
        this.updateDate = now;
    }

    /** 표시 순서만 바꾼다(값이 같으면 호출하지 않는다 — update_date 보존). */
    public void changeOrder(int ord, LocalDateTime now) {
        this.ord = ord;
        this.updateDate = now;
    }

    /** 지금 공개 메인에 노출되는가(관리 화면의 상태 표시용 — 공개 조회는 같은 의미의 DB 조건을 쓴다). */
    public boolean isDisplayedAt(LocalDateTime now) {
        return Boolean.TRUE.equals(useYn) && DisplayPeriod.isActive(displayStart, displayEnd, now);
    }
}
