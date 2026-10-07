package com.cms.common.html;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlContentSanitizerTest {

    private static String clean(String html) {
        return HtmlContentSanitizer.sanitize(html).html();
    }

    @Nested
    @DisplayName("XSS 벡터 제거")
    class Xss {

        @ParameterizedTest
        @ValueSource(strings = {
                "<script>alert(1)</script><p>x</p>",
                "<p>x<img src=x onerror=alert(1)></p>",
                "<p><a href=\"javascript:alert(1)\">x</a></p>",
                "<p><a href=\"JaVaScRiPt:alert(1)\">x</a></p>",
                "<p><a href=\"javascript&colon;alert(1)\">x</a></p>",
                "<p><a href=\"&#106;avascript:alert(1)\">x</a></p>",
                "<p><a href=\" javascript:alert(1)\">x</a></p>",
                "<svg onload=alert(1)><p>x</p></svg>",
                "<iframe src=\"https://evil.test\"></iframe><p>x</p>",
                "<p style=\"background:url(javascript:alert(1))\" class=\"ql-align-center\" data-x=\"1\" onclick=\"alert(1)\">x</p>",
                "<p><img src=\"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=\"></p><p>x</p>",
                "<p><img src=\"https://evil.test/pixel.png\"></p><p>x</p>",
                "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
                "<p><a href=\"vbscript:msgbox(1)\">x</a></p>",
                "<form action=\"https://evil.test\"><input name=x></form><p>x</p>",
                "<p><object data=\"x\"></object><embed src=\"x\">x</p>",
        })
        @DisplayName("위험 태그·속성·스킴이 결과에 남지 않는다")
        void removesDangerousMarkup(String payload) {
            String result = clean(payload).toLowerCase(java.util.Locale.ROOT);

            assertThat(result).doesNotContain("<script", "onerror", "onload", "onclick", "javascript", "vbscript",
                    "<svg", "<iframe", "style=", "class=", "data-", "data:", "evil.test", "<form", "<input",
                    "<object", "<embed", "<math", "<style");
        }

        @Test
        @DisplayName("허용 링크는 rel·target이 강제된다")
        void enforcesLinkAttributes() {
            String result = clean("<p><a href=\"https://example.com/a?b=1&amp;c=2\" target=\"_self\" rel=\"opener\">x</a></p>");

            assertThat(result).isEqualTo(
                    "<p><a href=\"https://example.com/a?b=1&amp;c=2\" rel=\"noopener noreferrer nofollow\" target=\"_blank\">x</a></p>");
        }

        @Test
        @DisplayName("mailto 링크는 허용, 상대 경로 링크는 href가 제거된다")
        void linkProtocols() {
            assertThat(clean("<p><a href=\"mailto:a@b.test\">m</a></p>")).contains("href=\"mailto:a@b.test\"");
            assertThat(clean("<p><a href=\"/admin\">r</a></p>")).doesNotContain("href");
        }

        @Test
        @DisplayName("텍스트로 쓴 꺾쇠·앰퍼샌드는 이스케이프된 채 남는다")
        void keepsEscapedText() {
            assertThat(clean("<p>&lt;b&gt;x&lt;/b&gt; &amp; \"q\"</p>"))
                    .isEqualTo("<p>&lt;b&gt;x&lt;/b&gt; &amp; \"q\"</p>");
        }
    }

    @Nested
    @DisplayName("허용 서식")
    class AllowedFormats {

        @Test
        @DisplayName("편집기 서식은 그대로 보존된다")
        void keepsEditorFormats() {
            String html = "<h2>t</h2><h3>s</h3><p><strong>b</strong><em>i</em><u>u</u><s>s</s></p>"
                    + "<ol><li>1</li></ol><ul><li>2</li></ul><blockquote>q</blockquote>";

            assertThat(clean(html)).isEqualTo(html);
        }

        @Test
        @DisplayName("h1은 h2, h4~h6은 h3으로 바뀐다(편집기 matcher와 같은 결과)")
        void mapsHeadings() {
            assertThat(clean("<h1>a</h1><h4>b</h4><h5>c</h5><h6>d</h6>"))
                    .isEqualTo("<h2>a</h2><h3>b</h3><h3>c</h3><h3>d</h3>");
        }

        @Test
        @DisplayName("허용 밖 태그는 벗겨지고 텍스트는 남는다(pre·code·span·b 포함)")
        void unwrapsDisallowedTags() {
            assertThat(clean("<pre>code</pre>")).isEqualTo("<p>code</p>");
            assertThat(clean("<p><span>a</span><code>b</code><b>c</b></p>")).isEqualTo("<p>abc</p>");
        }

        @Test
        @DisplayName("최상위 인라인 콘텐츠는 문단으로 감싼다")
        void wrapsTopLevelInline() {
            assertThat(clean("hello <strong>world</strong>")).isEqualTo("<p>hello <strong>world</strong></p>");
            assertThat(clean("a<p>b</p>c")).isEqualTo("<p>a</p><p>b</p><p>c</p>");
        }
    }

    @Nested
    @DisplayName("본문 이미지 src")
    class Images {

        @Test
        @DisplayName("/content-images/{id}만 남고 ID가 수집된다")
        void keepsContentImages() {
            SanitizedHtml result = HtmlContentSanitizer.sanitize(
                    "<p><img src=\"/content-images/7\" alt=\"a\"><img src=\"/content-images/3\"><img src=\"/content-images/7\"></p>");

            assertThat(result.html()).isEqualTo(
                    "<p><img src=\"/content-images/7\" alt=\"a\"><img src=\"/content-images/3\"><img src=\"/content-images/7\"></p>");
            assertThat(result.imageIds()).containsExactly(7L, 3L);
            assertThat(result.blank()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/content-images/0", "/content-images/007", "/content-images/9999999999999999999",
                "/content-images/1/../2", "/content-images/1?x=1", "//content-images/1", "content-images/1",
                "https://site.test/content-images/1", "/content-images/-1", "/content-images/ 1",
        })
        @DisplayName("형식이 다르거나 Long 범위 밖이면 img가 제거된다")
        void removesInvalidSrc(String src) {
            SanitizedHtml result = HtmlContentSanitizer.sanitize("<p>x<img src=\"" + src + "\"></p>");

            assertThat(result.html()).isEqualTo("<p>x</p>");
            assertThat(result.imageIds()).isEmpty();
        }

        @Test
        @DisplayName("Long 최댓값 ID는 허용된다")
        void allowsLongMax() {
            assertThat(HtmlContentSanitizer.parseContentImageId("/content-images/9223372036854775807"))
                    .isEqualTo(Long.MAX_VALUE);
            assertThat(HtmlContentSanitizer.parseContentImageId("/content-images/9223372036854775808")).isNull();
        }
    }

    @Nested
    @DisplayName("공백 정규화(쟁점 3-1)")
    class Whitespace {

        @Test
        @DisplayName("Quill의 전부-nbsp 출력은 단어 사이 일반 공백으로 돌아온다")
        void normalizesAllNbsp() {
            assertThat(clean("<p>a&nbsp;b&nbsp;&amp;&nbsp;c</p>")).isEqualTo("<p>a b &amp; c</p>");
        }

        @Test
        @DisplayName("연속 공백은 첫 칸만 일반 공백, 블록 앞뒤 공백은 전부 nbsp")
        void preservesRuns() {
            assertThat(clean("<p>A   B</p>")).isEqualTo("<p>A &nbsp;&nbsp;B</p>");
            assertThat(clean("<p>  lead</p>")).isEqualTo("<p>&nbsp;&nbsp;lead</p>");
            assertThat(clean("<p>trail  </p>")).isEqualTo("<p>trail&nbsp;&nbsp;</p>");
        }

        @Test
        @DisplayName("탭·줄바꿈 문자는 공백 1칸으로 취급한다(글자 수 1:1)")
        void tabsAndNewlines() {
            assertThat(clean("<p>a\tb</p>")).isEqualTo("<p>a b</p>");
            assertThat(clean("<p>a\t\tb</p>")).isEqualTo("<p>a &nbsp;b</p>");
            assertThat(clean("<p>a\nb</p>")).isEqualTo("<p>a b</p>");
        }

        @Test
        @DisplayName("인라인 경계를 넘는 공백 연속도 한 구간으로 정규화한다")
        void acrossInlineBoundaries() {
            assertThat(clean("<p>a <strong> b</strong></p>")).isEqualTo("<p>a <strong>&nbsp;b</strong></p>");
        }

        @Test
        @DisplayName("빈 블록에는 br을 보충한다(Quill의 <p></p>는 높이 0)")
        void fillsEmptyBlocks() {
            assertThat(clean("<p>A</p><p></p><p>B</p>")).isEqualTo("<p>A</p><p><br></p><p>B</p>");
            assertThat(clean("<ul><li>x</li><li></li></ul>")).isEqualTo("<ul><li>x</li><li><br></li></ul>");
        }
    }

    @Nested
    @DisplayName("멱등성")
    class Idempotence {

        @ParameterizedTest
        @ValueSource(strings = {
                "<p>A&nbsp;&nbsp;&nbsp;B</p><p>&nbsp;&nbsp;lead</p><p>tab\tX</p><p>trail&nbsp;&nbsp;</p>",
                "<p>A</p><p></p><p></p><p></p>",
                "<h1>a</h1><ul><li>b <em>c</em></li><li></li></ul><blockquote>q</blockquote>",
                "<p><a href=\"https://x.test\">a&nbsp;b</a>&nbsp;<strong>B</strong></p>",
                "text only   with  runs",
                "<p><img src=\"/content-images/1\"> <img src=\"/content-images/2\"></p>",
                "<script>x</script><p onclick=1>y</p>",
                "<p>&lt;b&gt;&amp;</p>",
                "<blockquote><p>nested</p></blockquote>",
                "<strong><blockquote>x</blockquote></strong>",
                "a<em><u><p>x</p></u></em>b<a href=\"https://x.test\"><ul><li>y</li></ul></a>",
                "<h2>a<em><h3>b</h3></em>c</h2>",
                "<h2>a<h3>b</h3>c</h2><li>x<blockquote>y</blockquote></li><p><blockquote>z</blockquote></p>",
                "",
        })
        @DisplayName("두 번 sanitize해도 결과가 같다")
        void idempotent(String input) {
            String once = clean(input);

            assertThat(clean(once)).isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("보이는 텍스트 길이·공백 판정(쟁점 5)")
    class Length {

        @Test
        @DisplayName("텍스트 문자 + 블록 종료 1, 블록 끝 br은 세지 않는다")
        void countsBlocks() {
            assertThat(HtmlContentSanitizer.sanitize("<p>ab</p><p>c</p>").textLength()).isEqualTo(5);
            assertThat(HtmlContentSanitizer.sanitize("<p>A</p><p><br></p><p>B</p>").textLength()).isEqualTo(5);
        }

        @Test
        @DisplayName("공백은 정규화 없이 모두 센다(10,001개 공백 우회 차단)")
        void countsWhitespace() {
            String html = "<p>A" + " ".repeat(10_001) + "B</p>";

            assertThat(HtmlContentSanitizer.sanitize(html).textLength()).isEqualTo(10_004);
        }

        @Test
        @DisplayName("블록 중간 br은 1자로 센다")
        void countsMiddleBr() {
            assertThat(HtmlContentSanitizer.sanitize("<p>a<br>b</p>").textLength()).isEqualTo(4);
        }

        @Test
        @DisplayName("인라인 서식의 마지막 자식인 br도 블록 중간이면 센다(서식으로 감싼 br 우회 차단)")
        void countsBrAtEndOfInlineInsideBlock() {
            assertThat(HtmlContentSanitizer.sanitize("<p><u>x<br></u>b</p>").textLength()).isEqualTo(4);
            assertThat(HtmlContentSanitizer.sanitize("<p><u>x<br></u></p>").textLength()).isEqualTo(2);
        }

        @Test
        @DisplayName("블록 자손을 가진 인라인 서식은 벗겨 저장 시와 출력 시 길이가 같다")
        void unwrapsInlineContainingBlock() {
            SanitizedHtml once = HtmlContentSanitizer.sanitize("<strong><blockquote>x</blockquote></strong>");

            assertThat(once.html()).isEqualTo("<blockquote>x</blockquote>");
            assertThat(HtmlContentSanitizer.sanitize(once.html()).textLength()).isEqualTo(once.textLength());
        }

        @Test
        @DisplayName("다시 파싱하면 바뀌는 구조(제목 안의 제목)도 저장 시와 출력 시 길이가 같다")
        void lengthStableForNestedHeadings() {
            SanitizedHtml once = HtmlContentSanitizer.sanitize("<h2>a<em><h3>b</h3></em>c</h2>");

            assertThat(HtmlContentSanitizer.sanitize(once.html()).textLength()).isEqualTo(once.textLength());
        }

        @Test
        @DisplayName("한글·이모지는 코드 포인트 단위로 센다")
        void countsCodePoints() {
            assertThat(HtmlContentSanitizer.sanitize("<p>가😀</p>").textLength()).isEqualTo(3);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "<p></p>", "<p><br></p><p>&nbsp;</p>", "<p><img src=\"https://x.test/a.png\"></p>",
                "<script>alert(1)</script>", "<p>　 </p>"})
        @DisplayName("보이는 텍스트도 이미지도 없으면 blank")
        void blank(String html) {
            assertThat(HtmlContentSanitizer.sanitize(html).blank()).isTrue();
        }

        @Test
        @DisplayName("이미지만 있는 본문은 blank가 아니다")
        void imageOnlyIsNotBlank() {
            assertThat(HtmlContentSanitizer.sanitize("<p><img src=\"/content-images/1\"></p>").blank()).isFalse();
        }
    }
}
