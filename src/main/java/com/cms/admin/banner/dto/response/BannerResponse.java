package com.cms.admin.banner.dto.response;

import com.cms.admin.banner.domain.Banner;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 관리 화면용 배너 응답. {@code status}는 서버 {@code Clock} 기준으로 계산한다(브라우저 시계에 의존하지 않음). */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "배너 응답")
public class BannerResponse {

    /** 노출 상태 — HIDDEN(노출 여부 꺼짐) > SCHEDULED(시작 전) > EXPIRED(종료됨) > ACTIVE(노출 중). */
    public enum Status { ACTIVE, SCHEDULED, EXPIRED, HIDDEN }

    private Long id;
    private String title;
    private String linkUrl;
    private String contentType;
    private Long fileSize;
    private LocalDateTime displayStart;
    private LocalDateTime displayEnd;
    private Boolean useYn;
    private Integer ord;
    private Status status;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static BannerResponse from(Banner banner, LocalDateTime now) {
        return BannerResponse.builder()
                .id(banner.getId())
                .title(banner.getTitle())
                .linkUrl(banner.getLinkUrl())
                .contentType(banner.getContentType())
                .fileSize(banner.getFileSize())
                .displayStart(banner.getDisplayStart())
                .displayEnd(banner.getDisplayEnd())
                .useYn(banner.getUseYn())
                .ord(banner.getOrd())
                .status(statusOf(banner, now))
                .createDate(banner.getCreateDate())
                .updateDate(banner.getUpdateDate())
                .build();
    }

    static Status statusOf(Banner banner, LocalDateTime now) {
        if (!Boolean.TRUE.equals(banner.getUseYn())) {
            return Status.HIDDEN;
        }
        if (banner.getDisplayStart() != null && now.isBefore(banner.getDisplayStart())) {
            return Status.SCHEDULED;
        }
        if (banner.getDisplayEnd() != null && !now.isBefore(banner.getDisplayEnd())) {
            return Status.EXPIRED;
        }
        return Status.ACTIVE;
    }
}
