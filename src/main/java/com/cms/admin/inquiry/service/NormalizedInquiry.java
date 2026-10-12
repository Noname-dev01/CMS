package com.cms.admin.inquiry.service;

/** {@link InquiryInputPolicy}가 검증·정규화를 마친 문의 입력. 서비스는 이 값만 받는다(원문 폼을 받지 않는다). */
public record NormalizedInquiry(String name, String email, String title, String body) {
}
