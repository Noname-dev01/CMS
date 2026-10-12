package com.cms.admin.inquiry.domain;

/** 문의 처리 상태(PLAN-inquiry.md 쟁점 6). 전이는 제한하지 않는다 — 관리자가 실수를 되돌릴 수 있게 한다. */
public enum InquiryStatus {

    /** 접수 — 공개 폼으로 막 들어온 상태. */
    RECEIVED,

    /** 처리 중. */
    IN_PROGRESS,

    /** 답변 완료 — 미처리 건수 상한(소프트)에서 빠진다. */
    ANSWERED
}
