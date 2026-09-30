package com.cms.common.api;

import com.cms.config.AppConfig;

import java.time.LocalDateTime;

public record ApiErrorResponse(
        LocalDateTime timestamp,
        String path,
        String code,
        String message
) {

    public static ApiErrorResponse of(String path, String code, String message) {
        return new ApiErrorResponse(
                // static 팩토리(필터·핸들러 다수가 직접 생성해 Clock 주입 불가)라 시간대 상수만 공유한다.
                // 저장되지 않는 응답 전용 값 — 저장·조회 코드는 주입된 Clock을 쓴다(ClockUsageConventionTest 허용 목록).
                LocalDateTime.now(AppConfig.KST),
                path,
                code,
                message
        );
    }
}