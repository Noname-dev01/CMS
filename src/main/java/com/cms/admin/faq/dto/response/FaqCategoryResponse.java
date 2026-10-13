package com.cms.admin.faq.dto.response;

import com.cms.admin.faq.domain.FaqCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 카테고리 한 건 응답(등록·수정·삭제). 감사 로그가 최상위 {@code getId()}로 대상 ID를 뽑는다. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "FAQ 카테고리 응답")
public class FaqCategoryResponse {

    private Long id;
    private String name;
    private Integer ord;
    private Boolean useYn;
    /** 소속 사이트 ID(null = 모든 공개 사이트 공통). */
    private Long siteId;
    /** 수정 요청의 낡은 화면 검사 값. */
    private Integer version;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static FaqCategoryResponse from(FaqCategory category) {
        return FaqCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .ord(category.getOrd())
                .useYn(category.getUseYn())
                .siteId(category.getSiteId())
                .version(category.getVersion())
                .createDate(category.getCreateDate())
                .updateDate(category.getUpdateDate())
                .build();
    }
}
