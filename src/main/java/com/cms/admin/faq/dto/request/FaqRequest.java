package com.cms.admin.faq.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.common.html.ContentBodyPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FAQ 등록·수정(전체 교체) 요청. 수정에서 {@code categoryId}가 현재와 다르면 그 카테고리의 맨 끝으로 이동한다. 답변은 HTML이고 이미지는
 * 허용되지 않는다(PLAN-faq.md 쟁점 7).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 등록/수정 요청")
public class FaqRequest {

    @NotNull
    @Schema(description = "소속 카테고리 ID (수정이면 이동할 카테고리)")
    private Long categoryId;

    @Schema(description = "수정에서만 필수 — 편집을 시작할 때의 소속 카테고리 ID. 현재 소속과 다르면 409(낡은 화면이 다른 관리자가 옮긴 항목을 되돌려 재공개하지 못하게, PLAN-site-menu.md PR ③ S6b). 등록에서는 무시")
    private Long baseCategoryId;

    @NotBlank
    @Size(max = 300)
    @Schema(description = "질문", example = "배송은 얼마나 걸리나요?")
    private String question;

    @NotBlank
    @MaxUtf8Bytes(value = ContentBodyPolicy.FAQ_MAX_RAW_BYTES, message = "본문이 너무 깁니다.")
    @Schema(description = "답변 HTML(저장 시 서버가 정리한다. 이미지는 허용되지 않는다)")
    private String answer;

    @Schema(description = "본문 형식 표식. \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @NotNull
    @Schema(description = "노출 여부")
    private Boolean useYn;
}
