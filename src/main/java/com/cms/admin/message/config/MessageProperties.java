package com.cms.admin.message.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code cms.message.*} 설정 — 쪽지의 발송 빈도 한도(DB 이력 기준)와 검색·시도 남용 제한(인메모리 토큰 버킷).
 * 공개 경로 제한({@code cms.rate-limit.*})과 독립이다 — 그 {@code enabled=false}가 이 제한을 끄지 않는다(PLAN-admin-message.md R2-6).
 * 0 이하 값은 기동 시 바인딩 검증으로 실패한다(fail-fast). 기본값이 있어 설정을 생략해도 기동한다.
 */
@ConfigurationProperties(prefix = "cms.message")
@Validated
public class MessageProperties {

    /** 회원당 검색 요청 버스트 상한이자 분당 평균 허용량(토큰 버킷). */
    @Positive
    @Max(10_000)
    private int searchPerMinute = 30;

    /** 회원당 발송 시도(수신자 확인 포함) 버스트 상한이자 분당 평균 허용량(토큰 버킷). */
    @Positive
    @Max(10_000)
    private int attemptPerMinute = 30;

    /** 발송 이력 기준 최근 1분 최대 발송 건수(롤링 창). */
    @Positive
    @Max(10_000)
    private int sendPerMinute = 10;

    /** 발송 이력 기준 최근 24시간 최대 발송 건수(롤링 창). */
    @Positive
    @Max(100_000)
    private int sendPerDay = 300;

    /** 인메모리 토큰 버킷 캐시의 회원 키 상한(포화 시 fail-open). */
    @Positive
    @Max(1_000_000)
    private int maxKeys = 10_000;

    public int getSearchPerMinute() {
        return searchPerMinute;
    }

    public void setSearchPerMinute(int searchPerMinute) {
        this.searchPerMinute = searchPerMinute;
    }

    public int getAttemptPerMinute() {
        return attemptPerMinute;
    }

    public void setAttemptPerMinute(int attemptPerMinute) {
        this.attemptPerMinute = attemptPerMinute;
    }

    public int getSendPerMinute() {
        return sendPerMinute;
    }

    public void setSendPerMinute(int sendPerMinute) {
        this.sendPerMinute = sendPerMinute;
    }

    public int getSendPerDay() {
        return sendPerDay;
    }

    public void setSendPerDay(int sendPerDay) {
        this.sendPerDay = sendPerDay;
    }

    public int getMaxKeys() {
        return maxKeys;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }
}
