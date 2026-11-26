package com.cms.common.time;

import java.time.ZoneId;

/**
 * 앱 시간대(KST) 단일 정의. 시각 원천 {@code Clock}은 {@code AppConfig.clock()}이 이 값으로 만들고,
 * Clock을 주입받을 수 없는 static 팩토리({@code ApiErrorResponse})만 이 상수를 직접 쓴다(ClockUsageConventionTest 허용 목록).
 * {@code common}에 두는 이유: {@code common}이 {@code config}에 의존하지 않게 한다.
 */
public final class AppTimeZone {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private AppTimeZone() {
    }
}
