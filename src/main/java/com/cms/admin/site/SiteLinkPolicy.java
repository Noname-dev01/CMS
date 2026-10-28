package com.cms.admin.site;

import com.cms.common.exception.InvalidRequestException;
import com.cms.common.web.BoardLinkPaths;
import com.cms.common.web.PrivatePaths;
import com.cms.common.web.SafeUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
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
        String violation = violation(site, url);
        if (violation != null) {
            throw new InvalidRequestException(violation);
        }
    }

    /**
     * 출력 시 재검증용 — 예외 없이 "이 URL을 이 사이트 메뉴 링크로 그려도 되는가"를 돌려준다. {@link #validate}와 같은 규칙 한 벌을 쓰지만
     * 의미가 다르다: {@code validate}는 빈 값을 "URL 없음"으로 통과시키고, 이쪽은 링크로 쓸 수 없으므로 {@code false}다.
     */
    public boolean permits(Site site, String url) {
        return url != null && !url.isBlank() && violation(site, url) == null;
    }

    /** 위반 문구(입력 원문 비반영) 또는 통과면 null. */
    private String violation(Site site, String url) {
        if (!SafeUrls.isSafeLinkUrl(url)) {
            return "메뉴 URL 형식이 올바르지 않습니다.";
        }
        if (SafeUrls.isSameOriginPath(url)) {
            return internalPathViolation(site, url);
        }
        if (!site.permitsExternalLink()) {
            return "이 사이트의 메뉴에는 외부 링크를 사용할 수 없습니다.";
        }
        if (!hasParsableHost(url)) {
            return "메뉴 URL 형식이 올바르지 않습니다.";
        }
        String ownPath = ownPathOf(url);
        return ownPath == null ? null : internalPathViolation(site, ownPath);
    }

    private String internalPathViolation(Site site, String path) {
        if (site.getSiteType() == SiteType.PUBLIC && isPrivatePath(path)) {
            return "공개 사이트 메뉴에는 관리·내부 경로를 사용할 수 없습니다.";
        }
        // 숫자 게시판 경로는 언제나 404다 — 출력 재검증(permits)도 같은 규칙이라 남은 행은 링크 없는 라벨이 된다(PLAN-board-definition.md PR 4a D5)
        if (BoardLinkPaths.isNumericBoardPath(path)) {
            return "게시판 주소는 슬러그 주소(예: /main/boards/board-12)를 쓰세요.";
        }
        // 숫자 공지 경로(상세·첨부)도 사라졌다 — 목록 /{key}/notices는 302로 유효해 허용한다(PLAN-board-definition.md PR 4b D6)
        if (BoardLinkPaths.isNumericNoticePath(path)) {
            return "공지 주소는 게시판 주소(예: /main/boards/board-1/posts/5)를 쓰세요.";
        }
        return null;
    }

    /**
     * Java가 호스트를 읽을 수 있는 절대 URL인가. 퍼센트 인코딩된 호스트({@code https://%63ms.example/admin})는 {@code URI.getHost()}가 null이라
     * 같은 출처 판정이 건너뛰어지지만 브라우저는 {@code cms.example}로 정규화해 이동한다 — 두 해석이 갈라지는 입력은 거부한다(리뷰 지적).
     */
    private static boolean hasParsableHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.indexOf('%') < 0;
        } catch (RuntimeException e) {
            return false;
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

    /** 공개 사이트에서 거부할 비공개 경로인가 — 규칙은 {@link PrivatePaths}에 한 벌만 둔다(본문 내부 링크와 공유). */
    static boolean isPrivatePath(String path) {
        return PrivatePaths.isPrivate(path);
    }
}
