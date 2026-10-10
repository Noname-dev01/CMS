package com.cms.admin.banner.dto.request;

import com.cms.common.web.validation.SafeLinkUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 배너 메타데이터 <b>전체 교체</b> 요청(PUT). 링크·시작·종료의 {@code null}은 "해제"이고 이미지는 바꾸지 않는다
 * (PLAN-public-home-banner.md 쟁점 4·R2-2).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "배너 수정 요청 (메타데이터 전체 교체)")
public class BannerUpdateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목", example = "가을 맞이 행사")
    private String title;

    @Size(max = 500)
    @SafeLinkUrl
    @Schema(description = "링크. null/빈 값이면 링크 해제")
    private String linkUrl;

    @Schema(description = "노출 시작 시각(포함). null이면 시작 제한 해제", example = "2026-10-10T09:00")
    private LocalDateTime displayStart;

    @Schema(description = "노출 종료 시각(미포함). null이면 종료 제한 해제", example = "2026-10-20T00:00")
    private LocalDateTime displayEnd;

    @NotNull
    @Schema(description = "노출 여부", example = "true")
    private Boolean useYn;
}
