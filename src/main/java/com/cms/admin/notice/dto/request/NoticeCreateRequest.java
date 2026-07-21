package com.cms.admin.notice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "공지사항 생성 요청")
public class NoticeCreateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목", example = "시스템 점검 안내")
    private String title;

    @NotBlank
    @Size(max = 10000)
    @Schema(description = "본문", example = "2026-07-21 02:00~04:00 시스템 점검이 진행됩니다.")
    private String content;

    @Schema(description = "노출 여부. 누락 시 true로 기본화", example = "true")
    private Boolean useYn;
}
