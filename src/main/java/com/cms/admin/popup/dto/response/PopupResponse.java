package com.cms.admin.popup.dto.response;

import com.cms.admin.media.service.MediaImageInfo;
import com.cms.admin.popup.domain.Popup;
import com.cms.admin.popup.domain.PopupType;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.display.DisplayStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관리 화면용 팝업 응답. {@code status}는 서버 {@code Clock} 기준으로 계산한다(브라우저 시계에 의존하지 않음). 목록은 본문({@code contentHtml})을
 * 싣지 않고({@link #summaryOf}) 수정 화면이 상세({@link #detailOf})로 가져온다 — 최대 50KB짜리 본문을 목록에 모두 싣지 않기 위해서다(PLAN-popup.md R1-3).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "팝업 응답")
public class PopupResponse {

    private Long id;
    private String title;
    private PopupType popupType;
    private Integer revision;
    private String linkUrl;
    /** 연결된 라이브러리 이미지 ID(이미지형만, 없으면 null). 이미지 메타는 소유자 테이블이 아니라 이미지 행의 값이다. */
    private Long imageId;
    private String contentType;
    private Long fileSize;
    @Schema(description = "HTML형 본문. 목록 응답에서는 null(상세에서만 채운다)", nullable = true)
    private String contentHtml;
    private Integer posX;
    private Integer posY;
    private Integer width;
    private Integer height;
    private LocalDateTime displayStart;
    private LocalDateTime displayEnd;
    private Boolean useYn;
    /** 소속 사이트 ID(null = 모든 공개 사이트 공통). */
    private Long siteId;
    private DisplayStatus status;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    /** @param image 연결된 이미지(HTML형이거나 연결이 없으면 null) */
    public static PopupResponse summaryOf(Popup popup, LocalDateTime now, MediaImageInfo image) {
        return builderOf(popup, now, image).build();
    }

    public static PopupResponse detailOf(Popup popup, LocalDateTime now, MediaImageInfo image) {
        return builderOf(popup, now, image).contentHtml(popup.getContentHtml()).build();
    }

    private static PopupResponseBuilder builderOf(Popup popup, LocalDateTime now, MediaImageInfo image) {
        return PopupResponse.builder()
                .id(popup.getId())
                .title(popup.getTitle())
                .popupType(popup.getPopupType())
                .revision(popup.getRevision())
                .linkUrl(popup.getLinkUrl())
                .imageId(image == null ? null : image.imageId())
                .contentType(image == null ? null : image.contentType())
                .fileSize(image == null ? null : image.fileSize())
                .posX(popup.getPosX())
                .posY(popup.getPosY())
                .width(popup.getWidth())
                .height(popup.getHeight())
                .displayStart(popup.getDisplayStart())
                .displayEnd(popup.getDisplayEnd())
                .useYn(popup.getUseYn())
                .siteId(popup.getSiteId())
                .status(DisplayPeriod.statusOf(Boolean.TRUE.equals(popup.getUseYn()), popup.getDisplayStart(), popup.getDisplayEnd(), now))
                .createDate(popup.getCreateDate())
                .updateDate(popup.getUpdateDate());
    }
}
