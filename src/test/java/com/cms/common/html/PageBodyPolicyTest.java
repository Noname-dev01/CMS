package com.cms.common.html;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 정적 페이지 본문 규칙(PLAN-static-page.md 쟁점 7·8): 이미지 불허 정리, 별도 상한, 게시글 규칙 불변. */
class PageBodyPolicyTest {

    private static final String IMG = "<img src=\"/content-images/5\" alt=\"x\">";

    @Test
    @DisplayName("allowImages=false: 허용 목록을 통과하는 /content-images 이미지도 전부 제거되고 imageIds는 비어 있다")
    void removesAllImages() {
        SanitizedHtml result = HtmlContentSanitizer.sanitize("<p>앞</p><p>" + IMG + "</p><p>뒤 " + IMG + "</p>", false);

        assertThat(result.html()).doesNotContain("<img").doesNotContain("content-images");
        assertThat(result.imageIds()).isEmpty();
        assertThat(result.html()).contains("앞").contains("뒤");
    }

    @Test
    @DisplayName("allowImages=true(기존 sanitize(html))는 그대로 이미지를 남긴다 — 게시글 동작 불변")
    void defaultKeepsImages() {
        SanitizedHtml result = HtmlContentSanitizer.sanitize("<p>" + IMG + "</p>");

        assertThat(result.html()).contains("<img");
        assertThat(result.imageIds()).containsExactly(5L);
        assertThat(HtmlContentSanitizer.sanitize("<p>" + IMG + "</p>", true).html()).isEqualTo(result.html());
    }

    @Test
    @DisplayName("이미지 불허 정리는 멱등이다(저장 시와 출력 시 모두 같은 값으로 호출한다)")
    void noImageSanitizeIsIdempotent() {
        String once = HtmlContentSanitizer.sanitize("<h1>제목</h1><p>a   b " + IMG + "</p><ul><li>x</li></ul>", false).html();

        assertThat(HtmlContentSanitizer.sanitize(once, false).html()).isEqualTo(once);
    }

    @Test
    @DisplayName("이미지만 있는 본문은 이미지 불허 규칙에서 공백 본문이라 400이다")
    void imageOnlyBodyIsBlank() {
        assertThatThrownBy(() -> ContentBodyPolicy.sanitizePage("<p>" + IMG + "</p>", "HTML"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("공백");
    }

    @Test
    @DisplayName("형식 표식이 HTML이 아니면 오래된 편집 화면으로 보고 거부한다")
    void rejectsNonHtmlFormat() {
        assertThatThrownBy(() -> ContentBodyPolicy.sanitizePage("<p>a</p>", null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ContentBodyPolicy.sanitizePage("<p>a</p>", "TEXT")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("글자 상한: 페이지는 50,000자(+마지막 문단 종료 1자)까지, 게시글 규칙은 10,000자 그대로")
    void textLengthLimits() {
        String fiftyThousand = "<p>" + "가".repeat(50_000) + "</p>";
        String overPage = "<p>" + "가".repeat(50_001) + "</p>";
        String tenThousand = "<p>" + "가".repeat(10_000) + "</p>";
        String overPost = "<p>" + "가".repeat(10_001) + "</p>";

        assertThat(ContentBodyPolicy.sanitizePage(fiftyThousand, "HTML").textLength()).isEqualTo(50_001);
        assertThatThrownBy(() -> ContentBodyPolicy.sanitizePage(overPage, "HTML"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("50,000");

        assertThat(ContentBodyPolicy.sanitize(tenThousand, "HTML").textLength()).isEqualTo(10_001);
        assertThatThrownBy(() -> ContentBodyPolicy.sanitize(overPost, "HTML"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("10,000");
    }

    @Test
    @DisplayName("정리 후 바이트 상한: 페이지는 500,000바이트 — 글자 수는 상한 안이어도 서식 때문에 넘으면 거부")
    void bytesLimit() {
        // 보이는 글자는 적지만 태그가 많은 본문: 문단 하나에 강조 1글자씩(개당 20바이트) → 정리 후 크기가 500,000바이트를 넘는다
        String heavy = "<p>" + "<strong>가</strong>".repeat(30_000) + "</p>";

        assertThat(HtmlContentSanitizer.sanitize(heavy, false).textLength()).as("글자 수는 상한 안").isLessThan(50_001);
        assertThatThrownBy(() -> ContentBodyPolicy.sanitizePage(heavy, "HTML"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("서식");
    }

    @Test
    @DisplayName("출력 시 정리: DB에 직접 들어온 img·script·이벤트 속성은 렌더 전에 제거된다")
    void outputSanitizeStripsEverythingDangerous() {
        String stored = "<p onclick=\"x()\">본문 " + IMG + "<script>alert(1)</script></p><img src=\"http://evil.example/a.png\">";

        String html = ContentBodyPolicy.sanitizeForPageOutput(stored);

        assertThat(html).contains("본문").doesNotContain("<img").doesNotContain("script").doesNotContain("onclick").doesNotContain("evil.example");
    }
}
