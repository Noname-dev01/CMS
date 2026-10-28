package com.cms.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 본문 내부 링크 허용 규칙(PLAN-board-definition.md PR L). */
class InternalLinkPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/main/faq", "/main/boards/b1?page=2", "/main/pages/privacy-a#x", "/main/boards/b1?keyword=%ED%95%9C",
            "/content-images/7", "/main/x#%ED%95%9C",
    })
    @DisplayName("루트 상대 공개 경로는 허용된다(쿼리·프래그먼트의 인코딩 포함)")
    void allows(String href) {
        assertThat(InternalLinkPaths.isAllowedBodyLink(href)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//evil.test/x", "/\\evil.test", "boards/12", "../x", "?a=1", "#a", "", "https://example.com/x", "javascript:alert(1)",
            "/a%31", "/%2F", "/%5C", "/main/%2e%2e/admin", "/admin", "/ADMIN/x", "/Admin?x=1", "/admin;v=1", "/main;jsessionid=1",
            "/actuator/health", "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/error", "/main/../admin", "/./x",
            "/a b", "/a\tb", "/a\u0000b", "/a\u007fb",
    })
    @DisplayName("프로토콜 상대·경로 상대·인코딩 변형·비공개 경로·제어 문자는 거부된다")
    void rejects(String href) {
        assertThat(InternalLinkPaths.isAllowedBodyLink(href)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/administrator", "/admin-api", "/errors", "/v30"})
    @DisplayName("비공개 접두어와 이름이 비슷한 공개 경로는 허용된다(접두어 오분류 방지)")
    void allowsLookalikes(String href) {
        assertThat(InternalLinkPaths.isAllowedBodyLink(href)).isTrue();
    }

    @Test
    @DisplayName("null은 거부된다")
    void rejectsNull() {
        assertThat(InternalLinkPaths.isAllowedBodyLink(null)).isFalse();
    }
}
