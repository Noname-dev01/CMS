package com.cms.common.web.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 링크 허용 규칙은 {@code SafeUrls}가 원본이다 — 배너·팝업이 같은 규칙을 쓰는지 대표 값으로 고정한다. */
class SafeLinkUrlValidatorTest {

    private final SafeLinkUrlValidator validator = new SafeLinkUrlValidator();

    @ParameterizedTest
    @DisplayName("같은 출처 경로와 외부 http(s) 주소는 허용한다")
    @ValueSource(strings = {"/", "/boards/2", "/notices/1?x=1#a", "http://example.com", "https://example.com/a?b=c", "HTTPS://Example.com"})
    void allowed(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("null·빈 값·공백은 통과한다(값 없음 = 링크 해제는 서비스가 정규화)")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankPasses(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("허용 외 스킴·프로토콜 상대 경로·위장 URL·제어문자는 거부한다")
    @ValueSource(strings = {
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>1</script>", "vbscript:x", "file:///etc/passwd",
            "ftp://example.com", "//evil.com", "/\\evil.com", "https://admin@evil.com", "https://", "http:///x",
            "example.com", "relative/path", "/a b", "/a\nb", "https://exa mple.com"})
    void rejected(String url) {
        assertThat(validator.isValid(url, null)).isFalse();
    }
}
