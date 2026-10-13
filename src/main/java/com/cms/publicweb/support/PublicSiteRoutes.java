package com.cms.publicweb.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 사이트 범위 공개 경로 문법(PLAN-site-menu.md PR ② P2-2). 컨트롤러 매핑·{@code SecurityConfig}·시험이 같은 상수를 쓴다.
 *
 * <p>키 자리는 <b>점이 없는 비어 있지 않은 세그먼트</b>({@code [^.]+})만 제한한다 — 사이트 키 형식(대문자·밑줄·길이)을 여기서 걸면 형식 위반이
 * 보안·MVC 계층에서 먼저 탈락해 404가 아니라 302/403이 된다. 형식·예약어·존재 판정은 전부 {@link PublicSiteInterceptor}가 같은 404로 끝낸다.
 * 점이 있는 {@code favicon.ico} 같은 경로는 이 매핑에 걸리지 않아 정적 리소스 처리를 가리지 않는다. (문자 클래스에 {@code /}를 넣으면
 * {@code PathPattern} 파서가 거부한다 — 세그먼트 경계는 파서가 처리한다.)
 */
public final class PublicSiteRoutes {

    /** 경로 변수 이름 — 인터셉터가 읽는다. */
    public static final String KEY_VARIABLE = "siteKey";

    /** 사이트 키 자리. */
    public static final String KEY = "{" + KEY_VARIABLE + ":[^.]+}";

    public static final String HOME = "/" + KEY;
    public static final String NOTICES = HOME + "/notices";
    public static final String BOARDS = HOME + "/boards";
    public static final String PAGES = HOME + "/pages";
    public static final String FAQ = HOME + "/faq";
    public static final String INQUIRY = HOME + "/inquiry";

    /** 인터셉터가 해석한 {@link PublicSite}를 싣는 요청 속성 이름. */
    public static final String REQUEST_ATTRIBUTE = "com.cms.publicweb.PublicSite";

    private PublicSiteRoutes() {
    }

    /** 현재 요청의 해석된 사이트. 사이트 범위 컨트롤러에서는 인터셉터가 항상 채우므로 null이 아니다. */
    public static PublicSite current(HttpServletRequest request) {
        return (PublicSite) request.getAttribute(REQUEST_ATTRIBUTE);
    }
}
