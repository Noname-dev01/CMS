package com.cms.publicweb.inquiry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 공개 문의 폼 템플릿과 공개 쓰기 경로의 컨벤션(PLAN-inquiry.md 쟁점 12). 방문자 입력은 전부 이스케이프되는 {@code th:value}/{@code th:text}로만 되돌리고,
 * 외부 스크립트는 Turnstile 하나뿐이며 자체 인라인 스크립트·이벤트 핸들러가 없다. 또 {@code publicweb} 아래 쓰기(비-GET) 매핑은 {@code POST /inquiry} 하나로 고정한다 —
 * 새 공개 쓰기 경로가 인가 정책 승인 없이 늘지 않게 한다.
 */
class PublicInquiryTemplateConventionTest {

    private static final String FORM = "templates/public/inquiry/form.html";
    private static final String CLOSED = "templates/public/inquiry/closed.html";
    private static final String TURNSTILE_SCRIPT = "https://challenges.cloudflare.com/turnstile/v0/api.js";

    @Test
    @DisplayName("문의 폼·닫힘 템플릿에는 이스케이프 없는 출력(th:utext, [(…)])이 없다")
    void noUnescapedOutput() throws IOException {
        for (String template : List.of(FORM, CLOSED)) {
            String content = read(template);
            assertFalse(content.contains("th:utext"), template + "에 th:utext가 있습니다.");
            assertFalse(content.contains("[("), template + "에 인라인 비이스케이프 출력이 있습니다.");
            assertFalse(Pattern.compile("\\son[a-z]+\\s*=").matcher(content).find(), template + "에 이벤트 핸들러 속성이 있습니다.");
        }
    }

    @Test
    @DisplayName("스크립트는 Turnstile 외부 스크립트 하나뿐이고 인라인 스크립트가 없다")
    void onlyTurnstileScript() throws IOException {
        String content = read(FORM);

        Matcher scripts = Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL).matcher(content);
        int count = 0;
        while (scripts.find()) {
            count++;
            assertThat(scripts.group(1)).contains("src=\"" + TURNSTILE_SCRIPT + "\"");
            assertThat(scripts.group(2).strip()).as("인라인 스크립트 본문").isEmpty();
        }
        assertThat(count).isEqualTo(1);
        assertFalse(read(CLOSED).contains("<script"), "닫힘 페이지에는 스크립트가 없어야 합니다.");
    }

    @Test
    @DisplayName("폼은 th:action으로 POST한다(CSRF hidden 자동 삽입) — 방문자 입력은 th:value·th:text로만 되돌린다")
    void formShape() throws IOException {
        String content = read(FORM);

        assertThat(content).contains("th:action=\"@{/inquiry}\"").contains("method=\"post\"");
        assertThat(content).contains("th:value=\"${form.name}\"").contains("th:value=\"${form.email}\"").contains("th:value=\"${form.title}\"")
                .contains("th:text=\"${form.body}\"");
        assertThat(content).contains("data-sitekey");
    }

    @Test
    @DisplayName("com.cms.publicweb 아래 쓰기 매핑은 POST /inquiry 하나뿐이다 — 새 공개 쓰기 경로는 인가 정책 승인과 이 시험의 일부러 된 수정이 필요하다")
    void onlyOnePublicWriteMapping() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> writes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.cms.publicweb")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostMapping.class)) {
                    writes.add("POST " + String.join(",", method.getAnnotation(PostMapping.class).value()));
                }
                if (method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)) {
                    writes.add(method.getName() + " (PUT/PATCH/DELETE)");
                }
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null && !method.isAnnotationPresent(PostMapping.class)) {
                    for (RequestMethod requestMethod : mapping.method()) {
                        if (requestMethod != RequestMethod.GET && requestMethod != RequestMethod.HEAD) {
                            writes.add(requestMethod + " " + String.join(",", mapping.value()));
                        }
                    }
                }
            }
        }
        assertThat(writes).containsExactly("POST /inquiry");
    }

    private String read(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                fail("리소스를 찾을 수 없습니다: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
