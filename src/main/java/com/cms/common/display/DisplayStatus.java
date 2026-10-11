package com.cms.common.display;

/**
 * 관리 화면에 보이는 노출 상태. 우선순위는 {@code HIDDEN}(노출 여부 꺼짐) > {@code SCHEDULED}(시작 전) > {@code EXPIRED}(종료됨) >
 * {@code ACTIVE}(노출 중)다 — 계산은 {@link DisplayPeriod#statusOf}가 서버 {@code Clock} 시각으로 한다.
 */
public enum DisplayStatus {
    ACTIVE, SCHEDULED, EXPIRED, HIDDEN
}
