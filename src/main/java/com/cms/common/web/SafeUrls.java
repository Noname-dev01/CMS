package com.cms.common.web;

import java.util.regex.Pattern;

/**
 * 화면에 링크로 내보낼 URL의 허용 규칙을 한 곳에 둔다(통합 검색·메뉴 저장 검증·사이드바 렌더링이 공유).
 *
 * <p>화면 쪽 정규식(topbar-search.js·topbar-notification.js·menu/manage.html)은 {@link #SAME_ORIGIN_PATH}와
 * 같은 규칙을 직접 복제한다 — 한쪽을 바꾸면 함께 바꾼다.
 */
public final class SafeUrls {

    /** 같은 출처 경로 — {@code /}로 시작하고 {@code //}·{@code \}·공백·제어문자가 없다. */
    public static final String SAME_ORIGIN_PATH_REGEX = "^/(?![/\\\\])[^\\s\\\\\\x00-\\x1f\\x7f]*$";

    /**
     * 외부 http(s) 주소 — 호스트가 비어 있지 않고 userinfo({@code @}, {@code https://admin@evil} 위장)·공백·{@code \}·제어문자가 없다.
     * 스킴은 대소문자를 구분하지 않는다.
     */
    public static final String EXTERNAL_HTTP_URL_REGEX =
            "^(?i:https?)://[^\\s\\\\\\x00-\\x1f\\x7f/?#@]+(?:[/?#][^\\s\\\\\\x00-\\x1f\\x7f]*)?$";

    private static final Pattern SAME_ORIGIN_PATH = Pattern.compile(SAME_ORIGIN_PATH_REGEX);
    private static final Pattern EXTERNAL_HTTP_URL = Pattern.compile(EXTERNAL_HTTP_URL_REGEX);

    private SafeUrls() {
    }

    public static boolean isSameOriginPath(String url) {
        return url != null && SAME_ORIGIN_PATH.matcher(url).matches();
    }

    public static boolean isExternalHttpUrl(String url) {
        return url != null && EXTERNAL_HTTP_URL.matcher(url).matches();
    }

    /** 메뉴 URL로 쓸 수 있는 값인가 — 같은 출처 경로 또는 외부 http(s) 주소. */
    public static boolean isSafeMenuUrl(String url) {
        return isSameOriginPath(url) || isExternalHttpUrl(url);
    }
}
