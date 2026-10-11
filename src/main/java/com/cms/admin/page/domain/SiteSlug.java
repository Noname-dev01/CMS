package com.cms.admin.page.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 정적 페이지 슬러그 규칙(PLAN-static-page.md 쟁점 3) — 관리 입력 검증과 공개 경로 검증이 같은 규칙을 쓴다.
 * 소문자·숫자·하이픈(처음·끝·연속 하이픈 불가), 2~50자. 자동 소문자화는 하지 않는다(입력 의도와 저장값이 어긋나지 않게).
 */
public final class SiteSlug {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 50;

    /** Bean Validation {@code @Pattern}과 서비스가 같은 식을 쓴다. 길이는 {@code @Size}·{@link #isValid}가 따로 본다. */
    public static final String PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    /** 앞으로 {@code /pages} 아래에 생길 수 있는 고정 경로 이름 — 로드맵 확정 결정(예약어 차단). 지금은 라우트가 없다. */
    private static final Set<String> RESERVED = Set.of("revisions", "new", "edit", "index");

    private SiteSlug() {
    }

    /** 형식·길이만 본다(예약어는 {@link #isReserved}). */
    public static boolean isValid(String slug) {
        return slug != null
                && slug.length() >= MIN_LENGTH
                && slug.length() <= MAX_LENGTH
                && COMPILED.matcher(slug).matches();
    }

    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }
}
