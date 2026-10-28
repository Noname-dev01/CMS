package com.cms.common.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 본문 루트 상대 내부 링크 허용(PLAN-board-definition.md PR L): 허용·거부·속성·멱등·네 본문 도메인 왕복. */
class HtmlContentSanitizerInternalLinkTest {

    private static String clean(String html) {
        return HtmlContentSanitizer.sanitize(html).html();
    }

    private static Element firstLink(String cleanedHtml) {
        return Jsoup.parseBodyFragment(cleanedHtml).selectFirst("a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/main/faq", "/main/boards/b1?page=2&keyword=%ED%95%9C", "/main/pages/privacy-a#x"})
    @DisplayName("루트 상대 링크는 href가 남고 target 없이 rel만 붙는다(같은 창)")
    void keepsInternalLink(String href) {
        String result = clean("<p><a href=\"" + href.replace("&", "&amp;") + "\">x</a></p>");
        Element a = firstLink(result);

        assertThat(a.attr("href")).isEqualTo(href);
        assertThat(a.hasAttr("target")).isFalse();
        assertThat(a.attr("rel")).isEqualTo("noopener noreferrer nofollow");
        assertThat(result).doesNotContain("data-il");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//evil.test/x", "/\\evil.test", "boards/12", "../x", "?a=1", "#a", "/a%31", "/%2F", "/%5C",
            "/admin", "/ADMIN/x", "/admin;v=1", "/actuator/health", "/swagger-ui.html", "/v3/api-docs", "/error",
            "/main/../admin", "/a b", "javascript:alert(1)",
    })
    @DisplayName("거부된 링크는 href만 지워지고 글자와 지금의 rel·target은 남는다")
    void removesHrefOnly(String href) {
        String result = clean("<p><a href=\"" + href + "\">글자</a></p>");
        Element a = firstLink(result);

        assertThat(a.hasAttr("href")).isFalse();
        assertThat(a.text()).isEqualTo("글자");
        assertThat(a.attr("target")).isEqualTo("_blank");
        assertThat(a.attr("rel")).isEqualTo("noopener noreferrer nofollow");
        assertThat(result).doesNotContain("data-il");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<p><a href=\"/admin\" data-il=\"/main/faq\">x</a></p>",
            "<p><a href=\"javascript:alert(1)\" DATA-IL=\"/main/faq\">x</a></p>",
            "<p><a data-il=\"/main/faq\">x</a></p>",
            "<p><a href=\"/main/faq\" data-il=\"/admin\">x</a></p>",
            "<p><a href=\"&#47;&#47;evil.test\">x</a></p>",
            "<p><a href=\"/&#92;evil.test\">x</a></p>",
            "<p><a href=\"/main/&#46;&#46;/admin\">x</a></p>",
    })
    @DisplayName("위조 표지·엔티티 인코딩 우회로는 위험한 href가 되살아나지 않고 표지도 남지 않는다")
    void resistsBypass(String payload) {
        String result = clean(payload);

        assertThat(result).doesNotContain("data-il", "evil.test", "javascript", "/admin");
        Element a = firstLink(result);
        if (a.hasAttr("href")) {
            assertThat(a.attr("href")).isEqualTo("/main/faq");
        }
    }

    @Test
    @DisplayName("외부 링크 출력은 기존과 같고 mailto도 target이 유지된다")
    void externalUnchanged() {
        assertThat(clean("<p><a href=\"https://example.com/a?b=1&amp;c=2\">x</a></p>")).isEqualTo(
                "<p><a href=\"https://example.com/a?b=1&amp;c=2\" rel=\"noopener noreferrer nofollow\" target=\"_blank\">x</a></p>");
        assertThat(firstLink(clean("<p><a href=\"mailto:a@b.test\">m</a></p>")).attr("target")).isEqualTo("_blank");
    }

    @Test
    @DisplayName("중첩된 링크는 각각 독립적으로 판정된다")
    void nestedLinks() {
        String result = clean("<p><a href=\"/main/faq\">a<a href=\"/admin\">b</a></a></p>");

        assertThat(result).contains("href=\"/main/faq\"").doesNotContain("/admin").doesNotContain("data-il");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<p><a href=\"/main/faq?a=1&amp;b=2\">x</a> <a href=\"/admin\">y</a> <a href=\"https://e.test/\">z</a></p>",
            "<p><a href=\"/main/faq\" data-il=\"/admin\">x</a></p>",
            "<h1><a href=\"/main/faq\">제목</a></h1><ul><li><a href=\"/a/b\">i</a></li></ul>",
    })
    @DisplayName("저장 시·출력 시 이중 정리는 멱등이다")
    void idempotent(String html) {
        String once = clean(html);

        assertThat(clean(once)).isEqualTo(once);
        assertThat(HtmlContentSanitizer.sanitize(once, false).html())
                .isEqualTo(HtmlContentSanitizer.sanitize(HtmlContentSanitizer.sanitize(html, false).html(), false).html());
    }

    @Test
    @DisplayName("네 본문 도메인(게시글·정적 페이지·팝업·FAQ) 모두 저장·출력 왕복에서 내부 링크가 보존된다")
    void roundTripsInAllDomains() {
        String raw = "<p><a href=\"/main/faq\">내부</a> <a href=\"/admin\">비공개</a></p>";

        String[] stored = {
                ContentBodyPolicy.sanitize(raw, "HTML").html(),
                ContentBodyPolicy.sanitizePage(raw, "HTML").html(),
                ContentBodyPolicy.sanitizePopup(raw, "HTML").html(),
                ContentBodyPolicy.sanitizeFaq(raw, "HTML").html(),
        };
        String[] output = {
                HtmlContentSanitizer.sanitize(stored[0]).html(),
                ContentBodyPolicy.sanitizeForPageOutput(stored[1]),
                ContentBodyPolicy.sanitizeForPopupOutput(stored[2]),
                ContentBodyPolicy.sanitizeForFaqOutput(stored[3]).html(),
        };

        for (int i = 0; i < stored.length; i++) {
            assertThat(stored[i]).contains("href=\"/main/faq\"").doesNotContain("/admin");
            assertThat(output[i]).isEqualTo(stored[i]);
        }
    }
}
