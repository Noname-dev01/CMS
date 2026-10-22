package com.cms.admin.popup.domain;

import com.cms.common.display.DisplayPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 공개 메인(/)의 레이어 팝업(PLAN-popup.md). 종류는 {@link PopupType}이고 생성 후 바뀌지 않는다 — 이미지형은 링크와 이미지(연결은 {@code content_image_ref(owner_type=POPUP)} 한 곳 —
 * PLAN-media-library.md D4, {@code popup.storage_key} 등 옛 사본 컬럼은 새 앱이 쓰지도 읽지도 않는다), HTML형은 {@link #contentHtml}만 채운다. 노출은 {@code useYn ∧ [displayStart, displayEnd)}를 둘 다 만족할 때다 — 기간이 비어 있으면
 * {@code useYn}만 본다. 하드 삭제라 소프트 삭제 컬럼이 없다.
 */
@Entity
@Table(name = "popup")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Popup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 관리용 이름이자 공개 화면 대화상자의 접근성 이름(이미지형의 대체 텍스트). */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 수정할 때마다 +1(행 락 아래). 방문자 브라우저의 "오늘 하루 보지 않기"가 수정 전 팝업에 대한 것인지 가린다 — 시각(초 단위)은 같은 초의
     * 두 번째 수정을 구분하지 못한다(PLAN-popup.md R1-2).
     */
    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "popup_type", nullable = false, length = 10)
    private PopupType popupType;

    /** 이미지형만. HTML형은 본문 안의 링크를 쓴다. */
    @Column(name = "link_url", length = 500)
    private String linkUrl;

    /** HTML형만. 저장 시 정리된 HTML이고 출력 시에도 한 번 더 정리한다. */
    @Column(name = "content_html", columnDefinition = "MEDIUMTEXT")
    private String contentHtml;

    @Column(name = "pos_x", nullable = false)
    private Integer posX;

    @Column(name = "pos_y", nullable = false)
    private Integer posY;

    @Column(nullable = false)
    private Integer width;

    /** HTML형만. {@code null}이면 내용 높이에 맞춘다. */
    private Integer height;

    @Column(name = "display_start")
    private LocalDateTime displayStart;

    @Column(name = "display_end")
    private LocalDateTime displayEnd;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    /** 소속 사이트. {@code null} = 모든 공개 사이트 공통. 공개 사이트(PUBLIC)만 가리킬 수 있다(서비스가 검증). */
    @Column(name = "site_id")
    private Long siteId;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /**
     * 메타데이터·본문 전체 교체(PUT). 종류·이미지 파일은 바뀌지 않는다. {@code revision}을 올린다(오버플로 시 쓰기 전에
     * {@link ArithmeticException}). 기간은 호출자가 {@link DisplayPeriod}의 범위·정규화·순서 검증을 마친 값이어야 하고, 종류에 맞지 않는 값
     * (이미지형의 본문, HTML형의 링크·높이 위반)은 서비스가 이미 거른다.
     */
    public void replace(String title, String linkUrl, String contentHtml, int posX, int posY, int width, Integer height,
                        LocalDateTime displayStart, LocalDateTime displayEnd, boolean useYn, Long siteId, LocalDateTime now) {
        this.revision = Math.incrementExact(this.revision);
        this.siteId = siteId;
        this.title = title;
        this.linkUrl = linkUrl;
        this.contentHtml = contentHtml;
        this.posX = posX;
        this.posY = posY;
        this.width = width;
        this.height = height;
        this.displayStart = displayStart;
        this.displayEnd = displayEnd;
        this.useYn = useYn;
        this.updateDate = now;
    }

    /** 지금 공개 메인에 노출되는가(관리 화면의 상태 표시용 — 공개 조회는 같은 의미의 DB 조건을 쓴다). */
    public boolean isDisplayedAt(LocalDateTime now) {
        return Boolean.TRUE.equals(useYn) && DisplayPeriod.isActive(displayStart, displayEnd, now);
    }
}
