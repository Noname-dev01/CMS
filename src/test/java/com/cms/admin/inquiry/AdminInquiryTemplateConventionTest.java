package com.cms.admin.inquiry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 문의함 화면 템플릿의 표시 컨벤션(PLAN-inquiry.md 쟁점 7). 이름·이메일·제목·본문은 <b>방문자가 쓴 평문</b>이라 HTML로 해석되면 저장형 XSS가 된다 —
 * 화면은 {@code textContent}로만 삽입하고 HTML 삽입 API·이스케이프 없는 출력·링크 생성이 없어야 한다. 이메일은 {@code mailto:} 링크로 만들지 않는다.
 */
class AdminInquiryTemplateConventionTest {

    private static final String TEMPLATE = "templates/admin/inquiry/manage.html";

    @Test
    @DisplayName("문의함 화면에는 HTML 삽입 API·th:utext·인라인 비이스케이프 출력·링크 생성이 없다")
    void noHtmlInjectionSurfaces() throws IOException {
        String content = read();

        for (String forbidden : new String[]{"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "th:utext", "[(", "mailto:", "createContextualFragment", ".html("}) {
            assertFalse(content.contains(forbidden), "문의함 화면에 금지된 표면이 있습니다: " + forbidden);
        }
        assertFalse(Pattern.compile("\\son[a-z]+\\s*=").matcher(content).find(), "이벤트 핸들러 속성이 있습니다.");
        assertThat(content).contains("textContent");
    }

    @Test
    @DisplayName("방문자 입력 필드(이름·이메일·제목·본문)는 모두 textContent로 채운다")
    void visitorFieldsUseTextContent() throws IOException {
        String content = read();

        for (String assignment : new String[]{
                "getElementById(\"detailSubject\").textContent = detail.title",
                "getElementById(\"detailName\").textContent = detail.name",
                "getElementById(\"detailEmail\").textContent = detail.email",
                "getElementById(\"detailBody\").textContent = detail.body"}) {
            assertThat(content).contains(assignment);
        }
        assertThat(content).contains("const cells = [String(item.id), item.title, item.name];").contains("td.textContent = text;");
        assertThat(content).as("본문은 줄바꿈만 보존한다").contains("white-space: pre-wrap");
    }

    private String read() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                fail("리소스를 찾을 수 없습니다: " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
