package com.cms.publicweb.faq.dto;

import java.util.List;

/** 공개 FAQ 카테고리. 보이는 FAQ가 하나라도 있는 카테고리만 만들어진다(항목 목록이 비지 않는다). */
public record PublicFaqCategory(Long id, String name, List<PublicFaqItem> items) {
}
