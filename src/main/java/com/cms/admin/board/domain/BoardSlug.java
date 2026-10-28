package com.cms.admin.board.domain;

import com.cms.common.web.PathIds;

import java.util.regex.Pattern;

/**
 * 게시판 슬러그 규칙(PLAN-board-definition.md "PR 4a 상세 설계" D1·D2). 공개 주소는 {@code /{siteKey}/boards/{슬러그}}다.
 *
 * <ul>
 *   <li><b>기본 슬러그</b> {@code board-{id}} — 저장하지 않고 id에서 계산한다({@code board.slug}가 NULL인 게시판).</li>
 *   <li><b>사용자 슬러그</b> — 기본값에서 한 번만 바꿀 수 있다. 형식은 정적 페이지 슬러그와 같고(소문자·숫자·하이픈, 2~50자),
 *       숫자만으로 된 값과 {@code board-{숫자}}(기본 슬러그 이름 공간 — 시스템 전용)는 쓸 수 없다. 그래서 사용자 슬러그는 기본 슬러그와
 *       겹치지 않고, 바꾼 뒤의 옛 슬러그는 언제나 {@code board-{id}}라 별칭을 저장하지 않아도 301 대상을 계산할 수 있다.</li>
 * </ul>
 */
public final class BoardSlug {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 50;

    static final String DEFAULT_PREFIX = "board-";

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");
    private static final Pattern SYSTEM_NAMESPACE = Pattern.compile("^board-[0-9]+$");

    private BoardSlug() {
    }

    /** 현재 공개 슬러그 — 사용자 슬러그가 없으면 기본 슬러그. */
    public static String effective(Long id, String slug) {
        return slug != null ? slug : DEFAULT_PREFIX + id;
    }

    /**
     * 관리자가 지정할 수 있는 슬러그인가. 공개 경로 해석도 DB 조회 전에 이 검사를 먼저 한다 — 컬럼 정렬이 대소문자를 구분하지 않아
     * 형식 검사 없이 조회하면 {@code News}가 {@code news}에 맞는다.
     */
    public static boolean isAssignable(String slug) {
        return slug != null
                && slug.length() >= MIN_LENGTH
                && slug.length() <= MAX_LENGTH
                && FORMAT.matcher(slug).matches()
                && !DIGITS_ONLY.matcher(slug).matches()
                && !SYSTEM_NAMESPACE.matcher(slug).matches();
    }

    /**
     * 정규형 기본 슬러그({@code board-12})가 가리키는 id, 아니면 null. {@code board-012}·{@code board-+12}·전각 숫자처럼
     * id로는 읽혀도 정규형이 아닌 표기는 기본 슬러그가 아니다 — 한 게시판에 열리는 주소를 하나로 둔다.
     */
    public static Long parseDefault(String segment) {
        if (segment == null || !segment.startsWith(DEFAULT_PREFIX)) {
            return null;
        }
        Long id = PathIds.parse(segment.substring(DEFAULT_PREFIX.length()));
        return id != null && segment.equals(DEFAULT_PREFIX + id) ? id : null;
    }
}
