package com.cms.admin.faq.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 관리 화면용 카테고리·FAQ 트리(답변 본문 제외)와 이 트리를 읽은 시점의 구조 {@code version}. 둘은 한 읽기 트랜잭션(같은 스냅샷)에서 만든다
 * (PLAN-faq.md R1-2) — 옛 순서에 새 version이 붙으면 순서 반영이 다른 관리자의 변경을 덮는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "FAQ 트리 응답")
public class FaqTreeResponse {

    private Long version;
    private List<Category> categories;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Category {
        private Long id;
        private String name;
        private Integer ord;
        private Boolean useYn;
        private Long siteId;
        private Integer version;
        private List<Item> faqs;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private Long categoryId;
        private String question;
        private Integer ord;
        private Boolean useYn;
    }
}
