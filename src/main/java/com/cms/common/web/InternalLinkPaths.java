package com.cms.common.web;

/**
 * 편집기 본문(HTML)의 내부 링크 {@code href} 허용 규칙. 루트 상대 경로({@code /…})만 허용하고, 위반은 호출자가 {@code href}만 지운다.
 * 이 CMS의 공개 경로(사이트 키·슬러그·숫자 id)는 전부 ASCII라 경로의 퍼센트 인코딩은 쓸 일이 없다 — 인코딩 변형이
 * 원문 기준 판정(링크 치환, 숫자 경로 거부)과 갈리지 않도록 정규형만 허용한다.
 */
public final class InternalLinkPaths {

    private InternalLinkPaths() {
    }

    public static boolean isAllowedBodyLink(String href) {
        if (!SafeUrls.isSameOriginPath(href)) {
            return false;
        }
        int cut = href.length();
        for (int i = 0; i < href.length(); i++) {
            char c = href.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String path = href.substring(0, cut);
        // 쿼리·프래그먼트의 인코딩은 허용한다. 경로의 '%'(숨은 구분자·숫자 변형)와 ';'(경로 매개변수 /admin;v=1)는 거부한다.
        if (path.indexOf('%') >= 0 || path.indexOf(';') >= 0) {
            return false;
        }
        // 숫자 게시판 경로는 언제나 404다(공개 게시판 주소는 슬러그만 — PLAN-board-definition.md PR 4a D5)
        return !PrivatePaths.isPrivate(path) && !BoardLinkPaths.isNumericBoardPath(path);
    }
}
