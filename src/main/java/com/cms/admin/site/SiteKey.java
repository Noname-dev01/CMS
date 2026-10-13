package com.cms.admin.site;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 사이트 키 규칙(PLAN-site-menu.md D2). 공개 경로 접두어({@code /{siteKey}/…})가 될 값이라 기존 공개·정적 경로와 겹치면 안 된다.
 * 소문자·숫자·하이픈(처음·끝·연속 하이픈 불가), 2~30자. 자동 소문자화는 하지 않는다. 생성 후 불변.
 *
 * <p>{@link #RESERVED}는 <b>새 공개 경로를 추가할 때 함께 갱신</b>해야 한다 — {@code SiteKeyReservedConventionTest}가
 * {@code SecurityConfig}의 정적 경로·공개 컨트롤러 매핑·{@code requestMatchers} 리터럴의 첫 세그먼트가 모두 여기에 있는지 강제한다.
 */
public final class SiteKey {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 30;

    /** Bean Validation {@code @Pattern}과 서비스가 같은 식을 쓴다. 길이는 {@code @Size}·{@link #isValid}가 따로 본다. */
    public static final String PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    /** 이미 쓰이는(또는 쓰일) 최상위 경로 이름. 점이 든 이름(favicon.ico·swagger-ui.html)은 형식에서 먼저 탈락하므로 넣지 않는다. */
    public static final Set<String> RESERVED = Set.of(
            "admin", "actuator", "error", "swagger-ui", "v3", "api",
            "notices", "boards", "pages", "faq", "inquiry", "content-images", "banners", "popups",
            "css", "js", "img", "vendor", "images", "static", "assets", "favicon", "login", "logout");

    private SiteKey() {
    }

    /** 형식·길이만 본다(예약어는 {@link #isReserved}). */
    public static boolean isValid(String key) {
        return key != null
                && key.length() >= MIN_LENGTH
                && key.length() <= MAX_LENGTH
                && COMPILED.matcher(key).matches();
    }

    public static boolean isReserved(String key) {
        return RESERVED.contains(key);
    }
}
