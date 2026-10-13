package com.cms.admin.site;

import com.cms.common.exception.InvalidRequestException;
import com.cms.common.web.SafeUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 사이트별 메뉴 링크 정책 판정(PLAN-site-menu.md D3). DTO의 {@code @SafeMenuUrl}은 링크 <b>형식</b>(내부 경로 또는 외부 http(s))만 보고,
 * 이 클래스가 <b>사이트 정책</b>을 서비스 계층에서 판정한다.
 *
 * <ul>
 *   <li>관리자 사이트 — 내부 경로만. 외부(절대) URL은 같은 출처여도 거부한다.</li>
 *   <li>공개 사이트 — 내부 경로, 그리고 {@code allow_external_link}이면 외부 http(s). 공개 사이트의 <b>내부 경로</b>는 비공개 접두어
 *       ({@code /admin}·{@code /actuator}·{@code /swagger-ui*}·{@code /v3}·{@code /error})를 가질 수 없다. CMS 자신의 출처
 *       ({@code app.base-url})와 같은 절대 URL은 출처를 떼어낸 경로로 보고 같은 규칙을 적용한다.</li>
 * </ul>
 *
 * 이 규칙은 인가가 아니라 "공개 메뉴에 관리 경로가 실수로 들어가지 않게" 하는 심층 방어다(관리 경로는 어차피 {@code /admin/**} 게이트가 막는다).
 * 별칭 호스트(다른 도메인)는 알 수 없어 탐지하지 않는다(수용한 한계).
 */
@Component
public class SiteLinkPolicy {

    private final String ownScheme;
    private final String ownHost;
    private final int ownPort;

    public SiteLinkPolicy(@Value("${app.base-url}") String baseUrl) {
        String scheme = null;
        String host = null;
        int port = -1;
        try {
            URI uri = URI.create(baseUrl.trim());
            scheme = lower(uri.getScheme());
            host = lower(uri.getHost());
            port = effectivePort(scheme, uri.getPort());
        } catch (RuntimeException e) {
            // base-url 형식은 PasswordResetService가 기동 시 검증한다 — 여기서는 같은 출처 탐지만 포기한다.
        }
        this.ownScheme = scheme;
        this.ownHost = host;
        this.ownPort = port;
    }

    /** 사이트 정책을 어기면 {@link InvalidRequestException}(400, 입력 원문 비반영). null·빈 값은 "URL 없음"이라 통과한다. */
    public void validate(Site site, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!SafeUrls.isSafeLinkUrl(url)) {
            throw new InvalidRequestException("메뉴 URL 형식이 올바르지 않습니다.");
        }
        if (SafeUrls.isSameOriginPath(url)) {
            checkInternalPath(site, url);
            return;
        }
        if (!site.permitsExternalLink()) {
            throw new InvalidRequestException("이 사이트의 메뉴에는 외부 링크를 사용할 수 없습니다.");
        }
        String ownPath = ownPathOf(url);
        if (ownPath != null) {
            checkInternalPath(site, ownPath);
        }
    }

    private void checkInternalPath(Site site, String path) {
        if (site.getSiteType() == SiteType.PUBLIC && isPrivatePath(path)) {
            throw new InvalidRequestException("공개 사이트 메뉴에는 관리·내부 경로를 사용할 수 없습니다.");
        }
    }

    /** CMS 자신의 출처와 같은 절대 URL이면 그 경로(없으면 "/")를, 아니면 null을 돌려준다. */
    private String ownPathOf(String url) {
        if (ownHost == null || ownScheme == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            if (!ownHost.equals(host) || !ownScheme.equals(scheme) || ownPort != effectivePort(scheme, uri.getPort())) {
                return null;
            }
            String rawPath = uri.getRawPath();
            return rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(String scheme, int port) {
        if (port != -1) {
            return port;
        }
        return "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
    }

    /**
     * 공개 사이트에서 거부할 비공개 경로인가. {@code ?}·{@code #} 앞에서 자르고 한 번 퍼센트 디코딩(실패·잔여 {@code %}는 거부)·소문자화한 뒤,
     * {@code .}/{@code ..} 세그먼트가 있거나 첫 세그먼트가 {@code admin}·{@code actuator}·{@code error}·{@code v3}이거나
     * {@code swagger-ui}로 시작하면 거부한다.
     */
    static boolean isPrivatePath(String path) {
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
