package com.cms.admin.faq.dto.response;

import com.cms.admin.faq.domain.Faq;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** FAQ 한 건 응답(등록·수정·삭제·단건 조회). 답변 본문을 담는다 — 목록 트리({@link FaqTreeResponse})는 담지 않는다. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "FAQ 응답")
public class FaqResponse {

    private Long id;
    private Long categoryId;
    private String question;
    @Schema(description = "저장된(정리된) 답변 HTML")
    private String answerHtml;
    private Integer ord;
    private Boolean useYn;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static FaqResponse from(Faq faq) {
        return FaqResponse.builder()
                .id(faq.getId())
                .categoryId(faq.getCategoryId())
                .question(faq.getQuestion())
                .answerHtml(faq.getAnswerHtml())
                .ord(faq.getOrd())
                .useYn(faq.getUseYn())
                .createDate(faq.getCreateDate())
                .updateDate(faq.getUpdateDate())
                .build();
    }
}
