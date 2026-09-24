package com.cms.admin.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상세의 평문/내부 마크업 경계를 고정하는 보조 검사.
 * JavaScript 실행·DOM 검증을 대체하지 않는다. 실제 브라우저 절차는
 * docs/verification/admin-detail-rendering.md 참조.
 */
class AdminMemberTemplateConventionTest {

    @Test
    @DisplayName("상세 평문은 조립 경계에서 한 번 escape하고 이메일 마크업과 구분한다")
    void detailText_isEscapedAtRenderingBoundary() throws IOException {
        String template = readTemplate();
        String detail = template.substring(template.indexOf("const detailItems = ["),
                template.indexOf("async function loadAdminDetail"));

        assertThat(detail).contains(
                "{ label: \"아이디\", value: data.userId }",
                "{ label: \"이름\", value: data.userName || \"-\" }",
                "{ label: \"권한\", value: getRoleLabel(data.userType) }",
                "{ label: \"상태\", value: getStatusLabel(data.status) }",
                "{ label: \"생성일\", value: formatDate(data.createDate) }",
                "(item.html ?? escapeHtml(item.value))");
        assertThat(detail).doesNotContain("+ item.value +", "value: escapeHtml(");
    }

    @Test
    @DisplayName("상세 HTML 예외는 내부 이메일 버튼 하나뿐이며 이메일 값은 escape한다")
    void detailMarkup_isLimitedToEscapedEmailButton() throws IOException {
        String template = readTemplate();
        String items = template.substring(template.indexOf("const detailItems = ["),
                template.indexOf("detailContent.innerHTML = detailItems.map"));

        assertThat(items).containsPattern("label: \"이메일\",\\s+html: data.email");
        assertThat(items.split("html:", -1)).hasSize(2);
        assertThat(template).contains("const emailValue = data.email ? escapeHtml(data.email) : \"-\";");
        assertThat(items).contains("copy-email-btn", "data-email=", "copy-email-text");
    }

    @Test
    @DisplayName("이메일 복사는 상세 컨테이너의 위임 이벤트와 원래 이메일 값을 유지한다")
    void emailCopy_keepsDelegatedEventContract() throws IOException {
        assertThat(readTemplate()).contains(
                "detailContent.addEventListener(\"click\"",
                "event.target.closest(\".copy-email-btn\")",
                "const email = button.dataset.email;",
                "navigator.clipboard.writeText(email)");
    }

    private String readTemplate() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("templates/admin/member/admin-manage.html")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
