package com.cms.admin.popup.dto.response;

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
    private DisplayStatus status;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static PopupResponse summaryOf(Popup popup, LocalDateTime now) {
        return builderOf(popup, now).build();
    }

    public static PopupResponse detailOf(Popup popup, LocalDateTime now) {
        return builderOf(popup, now).contentHtml(popup.getContentHtml()).build();
    }

    private static PopupResponseBuilder builderOf(Popup popup, LocalDateTime now) {
        return PopupResponse.builder()
                .id(popup.getId())
                .title(popup.getTitle())
                .popupType(popup.getPopupType())
                .revision(popup.getRevision())
                .linkUrl(popup.getLinkUrl())
                .contentType(popup.getContentType())
                .fileSize(popup.getFileSize())
                .posX(popup.getPosX())
                .posY(popup.getPosY())
                .width(popup.getWidth())
                .height(popup.getHeight())
                .displayStart(popup.getDisplayStart())
                .displayEnd(popup.getDisplayEnd())
                .useYn(popup.getUseYn())
                .status(DisplayPeriod.statusOf(Boolean.TRUE.equals(popup.getUseYn()), popup.getDisplayStart(), popup.getDisplayEnd(), now))
                .createDate(popup.getCreateDate())
                .updateDate(popup.getUpdateDate());
    }
}
