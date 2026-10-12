package com.cms.admin.inquiry.service;

/** 미처리 문의가 소프트 상한에 도달해 새 문의를 받지 않는다(PLAN-inquiry.md 쟁점 5). 공개 폼이 503 안내로 바꾼다. */
public class InquiryClosedException extends RuntimeException {

    public InquiryClosedException() {
        super("미처리 문의가 상한에 도달해 새 문의를 받지 않습니다.");
    }
}
