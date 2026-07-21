package com.cms.admin.notice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 부분 수정 요청. null 필드는 기존값 유지 시맨틱이다.
 * 값이 채워진 title/content의 공백 거부, 3필드 모두 null인 경우의 거부는
 * Service 계층에서 처리한다(설계 결정 4 — Notice.update()는 도메인 규칙만 담당).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "공지사항 부분 수정 요청")
public class NoticeUpdateRequest {

    @Size(max = 200)
    @Schema(description = "제목 (null이면 기존값 유지)", example = "시스템 점검 안내(변경)")
    private String title;

    @Size(max = 10000)
    @Schema(description = "본문 (null이면 기존값 유지)")
    private String content;

    @Schema(description = "노출 여부 (null이면 기존값 유지)", example = "false")
    private Boolean useYn;
}
