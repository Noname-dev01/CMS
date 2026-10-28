package com.cms.admin.site;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 사이트별 메뉴 링크 정책(PLAN-site-menu.md D3) — 관리자는 내부 경로만, 공개는 내부 + (허용 시) 외부, 공개의 비공개 접두어 금지. */
class SiteLinkPolicyTest {

    private static final Site ADMIN = Site.builder().id(1L).siteKey("admin").siteName("관리자").siteType(SiteType.ADMIN)
            .allowExternalLink(false).useYn(true).build();
    private static final Site PUBLIC_EXTERNAL = Site.builder().id(2L).siteKey("main").siteName("기본").siteType(SiteType.PUBLIC)
            .allowExternalLink(true).useYn(true).build();
    private static final Site PUBLIC_INTERNAL_ONLY = Site.builder().id(3L).siteKey("docs").siteName("문서").siteType(SiteType.PUBLIC)
            .allowExternalLink(false).useYn(true).build();

    private final SiteLinkPolicy policy = new SiteLinkPolicy("https://cms.example");

    private void assertAllowed(Site site, String url) {
        assertThatCode(() -> policy.validate(site, url)).doesNotThrowAnyException();
    }

    private void assertRejected(Site site, String url) {
        assertThatThrownBy(() -> policy.validate(site, url)).isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("URL 없음(null·빈 값)은 어느 사이트든 통과")
    void blankAllowed(String url) {
        assertAllowed(ADMIN, url);
        assertAllowed(PUBLIC_EXTERNAL, url);
    }

    @Test
    @DisplayName("관리자 사이트: 내부 경로(관리 경로 포함)만 — 외부 http(s)는 같은 출처여도 거부")
    void adminSite_internalOnly() {
        assertAllowed(ADMIN, "/admin/member/manage");
        assertAllowed(ADMIN, "/admin?x=1");
        assertRejected(ADMIN, "https://example.com/x");
        assertRejected(ADMIN, "https://cms.example/admin/x");
    }

    @Test
    @DisplayName("공개 사이트(외부 허용): 내부 경로와 외부 http(s) 허용")
    void publicSite_externalAllowed() {
        assertAllowed(PUBLIC_EXTERNAL, "/faq");
        assertAllowed(PUBLIC_EXTERNAL, "https://example.com/x");
        assertAllowed(PUBLIC_EXTERNAL, "HTTP://example.com");
    }

    @Test
    @DisplayName("공개 사이트(외부 불가): 외부 http(s) 거부, 내부 경로는 허용")
    void publicSite_externalDisabled() {
        assertAllowed(PUBLIC_INTERNAL_ONLY, "/faq");
        assertRejected(PUBLIC_INTERNAL_ONLY, "https://example.com/x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,x", "//evil.example", "https://evil@example.com", "admin/x",
            "/a\\b", "/a b", "ftp://example.com/x"})
    @DisplayName("형식 위반은 어느 사이트든 거부")
    void malformedRejected(String url) {
        assertRejected(ADMIN, url);
        assertRejected(PUBLIC_EXTERNAL, url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin", "/admin/login", "/admin?x=1", "/admin#top", "/ADMIN/x", "/Admin", "/%61dmin", "/%61dmin/x", "/%2561dmin",
            "/actuator/health", "/error", "/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui",
            "/a/../admin", "/./admin", "/x/./y", "/%2e%2e/admin", "/%zz"
    })
    @DisplayName("공개 사이트는 비공개 경로를 거부한다 — 대소문자·퍼센트 인코딩·점 세그먼트·swagger-ui.html 우회 포함")
    void publicSite_privatePathsRejected(String url) {
        assertRejected(PUBLIC_EXTERNAL, url);
        assertRejected(PUBLIC_INTERNAL_ONLY, url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/faq", "/news/1", "/administrator", "/adminx/y", "/pages/privacy", "/notices?page=2", "/x?next=/admin", "/a+b"})
    @DisplayName("공개 사이트: 이름이 비슷할 뿐인 일반 경로는 허용(administrator·adminx 등)")
    void publicSite_ordinaryPathsAllowed(String url) {
        assertAllowed(PUBLIC_EXTERNAL, url);
    }

    @Test
    @DisplayName("공개 사이트(외부 허용): CMS 자신의 출처 절대 URL은 내부 경로로 보고 같은 규칙을 적용한다 — 스킴·호스트 대소문자·기본 포트 차이 포함")
    void publicSite_ownOriginAbsoluteUrlTreatedAsInternal() {
        assertRejected(PUBLIC_EXTERNAL, "https://cms.example/admin/login");
        assertRejected(PUBLIC_EXTERNAL, "https://CMS.example/admin");
        assertRejected(PUBLIC_EXTERNAL, "https://cms.example:443/admin");
        assertRejected(PUBLIC_EXTERNAL, "https://cms.example/swagger-ui.html");
        assertAllowed(PUBLIC_EXTERNAL, "https://cms.example/faq");
        assertAllowed(PUBLIC_EXTERNAL, "https://cms.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/main/boards/12", "/main/boards/12/posts/1", "/main/boards/+12", "/main/boards/%31%32", "/main/boards/./12",
            "https://cms.example/main/boards/12"})
    @DisplayName("숫자 게시판 경로는 모든 사이트에서 거부하고 출력 재검증(permits)도 거짓 — 자기 출처 절대 URL 포함(PLAN-board-definition.md PR 4a D5)")
    void numericBoardPathRejected(String url) {
        assertRejected(PUBLIC_EXTERNAL, url);
        assertThat(policy.permits(PUBLIC_EXTERNAL, url)).isFalse();
        if (url.startsWith("/")) {
            assertRejected(ADMIN, url);
            assertRejected(PUBLIC_INTERNAL_ONLY, url);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/main/boards/board-12", "/main/boards/press", "/main/boards/press/posts/3"})
    @DisplayName("슬러그 게시판 경로는 허용한다")
    void slugBoardPathAllowed(String url) {
        assertAllowed(PUBLIC_EXTERNAL, url);
        assertThat(policy.permits(PUBLIC_EXTERNAL, url)).isTrue();
    }

    @Test
    @DisplayName("다른 출처(스킴·포트·호스트가 다름)는 외부 링크로 취급해 비공개 경로 검사를 하지 않는다 — 별칭 호스트는 수용한 한계")
    void publicSite_otherOriginIsPlainExternal() {
        assertAllowed(PUBLIC_EXTERNAL, "http://cms.example/admin");        // 스킴 다름
        assertAllowed(PUBLIC_EXTERNAL, "https://cms.example:8443/admin");  // 포트 다름
        assertAllowed(PUBLIC_EXTERNAL, "https://other.example/admin");     // 호스트 다름
    }

    @Test
    @DisplayName("base-url을 해석할 수 없어도 생성은 되고 같은 출처 탐지만 포기한다")
    void unparsableBaseUrl_doesNotBreak() {
        SiteLinkPolicy lenient = new SiteLinkPolicy("not a url");
        assertThat(lenient).isNotNull();
        assertThatCode(() -> lenient.validate(PUBLIC_EXTERNAL, "https://cms.example/admin")).doesNotThrowAnyException();
        assertThatThrownBy(() -> lenient.validate(PUBLIC_EXTERNAL, "/admin")).isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://%63ms.example/admin", "https://cms%2eexample/admin", "https://%63ms.example/main/faq",
            "https://exa%6dple.com/x", "https://user@%63ms.example/admin"})
    @DisplayName("호스트를 Java가 읽지 못하는 절대 URL(퍼센트 인코딩 호스트)은 같은 출처 검사를 건너뛰므로 외부 허용 사이트에서도 거부한다(코드 리뷰 지적)")
    void percentEncodedHostIsRejected(String url) {
        assertRejected(PUBLIC_EXTERNAL, url);
        assertThat(policy.permits(PUBLIC_EXTERNAL, url)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("permits: URL 없음(null·빈 값)은 링크로 그릴 수 없으므로 false — validate의 '빈 값 통과'와 의미가 다르다")
    void permits_blankIsNotALink(String url) {
        assertThat(policy.permits(PUBLIC_EXTERNAL, url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/faq", "/main/notices", "https://example.com/x", "HTTP://example.com",
            "/admin", "/admin?x=1", "/ADMIN/x", "/%61dmin", "/a/../admin", "/swagger-ui.html", "/actuator/health",
            "https://cms.example/admin/login", "https://cms.example/main/faq",
            "javascript:alert(1)", "//evil.example/x", "ftp://example.com", "faq", "mailto:a@b.c"})
    @DisplayName("permits ↔ validate 동치: 같은 규칙 한 벌이라 모든 사이트·입력에서 '던지지 않음 ⇔ true'")
    void permits_matchesValidate(String url) {
        for (Site site : new Site[]{ADMIN, PUBLIC_EXTERNAL, PUBLIC_INTERNAL_ONLY}) {
            boolean validates;
            try {
                policy.validate(site, url);
                validates = true;
            } catch (InvalidRequestException e) {
                validates = false;
            }
            assertThat(policy.permits(site, url)).as("%s / %s", site.getSiteKey(), url).isEqualTo(validates);
        }
    }
}
