package com.cms.common.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 공개 화면에 링크로 걸면 안 되는 비공개 경로 판정. 메뉴 링크 정책({@code SiteLinkPolicy})과 본문 내부 링크 허용
 * ({@link InternalLinkPaths})이 같은 규칙 한 벌을 쓴다 — 두 벌이 되면 한쪽만 고쳐지는 표류가 생긴다.
 */
public final class PrivatePaths {

    private PrivatePaths() {
    }

    /**
     * 비공개 경로인가. {@code ?}·{@code #} 앞에서 자르고 한 번 퍼센트 디코딩(실패·잔여 {@code %}는 거부)·소문자화한 뒤,
     * {@code .}/{@code ..} 세그먼트가 있거나 첫 세그먼트가 {@code admin}·{@code actuator}·{@code error}·{@code v3}이거나
     * {@code swagger-ui}로 시작하면 거부한다.
     */
    public static boolean isPrivate(String path) {
        int cut = path.length();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String decoded;
        try {
            // URLDecoder는 '+'를 공백으로 바꾸므로 먼저 보존한다.
            decoded = URLDecoder.decode(path.substring(0, cut).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (decoded.indexOf('%') >= 0) {
            return true;
        }
        String[] segments = decoded.toLowerCase(Locale.ROOT).split("/", -1);
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        String first = segments.length > 1 ? segments[1] : "";
        return first.equals("admin") || first.equals("actuator") || first.equals("error") || first.equals("v3")
                || first.startsWith("swagger-ui");
    }
}
