package com.cms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 화면 React 코드({@code frontend/src})의 규약(PLAN-react-admin-faq.md C5·결정 2·F4).
 *
 * <ul>
 *   <li>사용자 입력을 HTML로 해석하는 길을 만들지 않는다 — {@code dangerouslySetInnerHTML}, {@code innerHTML}·{@code outerHTML} 대입,
 *       {@code insertAdjacentHTML}, {@code eval(}, {@code new Function(} 금지. 본문 HTML은 공용 편집기({@code NoticeEditor.setHtml})로만 넘긴다
 *       (sanitizer 허용 목록과 1:1인 기존 경로). <b>예외는 한 곳뿐이다</b>(PLAN-react-admin-posts.md 결정 1): 서버가 출력 시 sanitizer를 거친 게시글 본문을
 *       그리는 {@code ServerHtml.tsx}의 {@code dangerouslySetInnerHTML} 1회이고, 그 컴포넌트는 {@code PostView.tsx}에서 {@code detail.content}에만
 *       쓴다 — 파일·횟수·쓰는 곳을 모두 고정해 다른 값으로 번지지 않게 한다.</li>
 *   <li>CSS·이미지·폰트를 import하지 않는다 — Vite가 별도 산출물을 만들고 템플릿이 그 링크를 따로 출력해야 한다. 스타일은 템플릿이 맡는다.</li>
 *   <li>동적 {@code import()}를 쓰지 않는다 — 진입점이 여럿이라 공유 청크가 생기는데, 정적 import만 쓰면 페이지를 읽을 때 모두 내려받아 떠 있는
 *       화면이 배포 뒤 사라진 청크를 찾는 일이 없다(PLAN-react-admin-menu.md 결정 4).</li>
 * </ul>
 * 한계: 문자열 조합으로 우회한 호출(예: {@code el["inner" + "HTML"]})과 의존 패키지 내부는 검사하지 않는다 — 리뷰에서 확인한다.
 */
class FrontendSourceConventionTest {

    private static final Path FRONTEND_SOURCE = Paths.get("frontend", "src");

    /** HTML 해석이 허용된 유일한 파일과, 그 컴포넌트를 쓰는 유일한 자리. */
    private static final String SERVER_HTML_FILE = "admin/shared/ServerHtml.tsx";
    private static final String SERVER_HTML_USER = "admin/post/PostView.tsx";
    private static final Pattern DANGEROUS_HTML = Pattern.compile("dangerouslySetInnerHTML");
    private static final Pattern SERVER_HTML_NAME = Pattern.compile("\\bServerHtml\\b");
    private static final Pattern SERVER_HTML_USE = Pattern.compile("<ServerHtml\\b[^>]*\\bhtml=\\{detail\\.content\\}[^>]*/>");
    private static final Pattern SERVER_HTML_IMPORT = Pattern.compile("import \\{ ServerHtml } from '\\.\\./shared/ServerHtml'");

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("dangerouslySetInnerHTML"),
            Pattern.compile("\\.\\s*(innerHTML|outerHTML)\\s*\\+?="),
            Pattern.compile("insertAdjacentHTML"),
            Pattern.compile("\\beval\\s*\\("),
            Pattern.compile("\\bnew\\s+Function\\s*\\("),
            Pattern.compile("\\bimport\\s*\\("),
            Pattern.compile("^\\s*import\\s+(?:[^'\"]*\\s+from\\s+)?['\"][^'\"]+\\.(css|scss|sass|less|png|jpe?g|gif|svg|webp|woff2?|ttf)(\\?[^'\"]*)?['\"]",
                    Pattern.MULTILINE));

    @Test
    @DisplayName("frontend/src에는 HTML 해석 경로와 CSS·자산 import가 없다")
    void frontendSourceFollowsConventions() throws IOException {
        assertThat(FRONTEND_SOURCE).as("프런트엔드 소스 위치").isDirectory();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(FRONTEND_SOURCE)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().matches(".*\\.(ts|tsx)$"))::iterator) {
                String name = FRONTEND_SOURCE.relativize(file).toString().replace('\\', '/');
                for (String violation : findViolations(name, Files.readString(file))) {
                    violations.add(file + " → " + violation);
                }
            }
        }
        assertThat(count(DANGEROUS_HTML, Files.readString(FRONTEND_SOURCE.resolve(SERVER_HTML_FILE)))).as("예외 파일의 HTML 해석은 정확히 1회").isEqualTo(1);
        String user = Files.readString(FRONTEND_SOURCE.resolve(SERVER_HTML_USER));
        assertThat(count(SERVER_HTML_USE, user)).as("ServerHtml은 detail.content에 1번만 쓴다").isEqualTo(1);
        assertThat(violations).as("금지 패턴 — 클래스 주석의 규약 참조").isEmpty();
    }

    @Test
    @DisplayName("검사 자체 회귀: 금지 패턴은 잡고, 텍스트 렌더링·textContent·TS import는 통과시킨다")
    void checkerDetectsViolations() {
        assertThat(findViolations("<div dangerouslySetInnerHTML={{ __html: x }} />")).isNotEmpty();
        assertThat(findViolations("el.innerHTML = name")).isNotEmpty();
        assertThat(findViolations("el . outerHTML += name")).isNotEmpty();
        assertThat(findViolations("el.insertAdjacentHTML('beforeend', x)")).isNotEmpty();
        assertThat(findViolations("eval(code)")).isNotEmpty();
        assertThat(findViolations("const f = new Function('a', body)")).isNotEmpty();
        assertThat(findViolations("import './faq.css'")).isNotEmpty();
        assertThat(findViolations("const m = await import('./Lazy')")).isNotEmpty();
        assertThat(findViolations("import logo from '../img/logo.svg?url'")).isNotEmpty();

        assertThat(findViolations("<span>{category.name}</span>")).isEmpty();
        assertThat(findViolations("el.textContent = name")).isEmpty();
        assertThat(findViolations("const html = el.innerHTML")).as("읽기는 해석 경로가 아니다").isEmpty();
        assertThat(findViolations("import { FaqModal } from './FaqModal'")).isEmpty();
        assertThat(findViolations("import type { View } from './FaqManage'")).isEmpty();
    }

    @Test
    @DisplayName("검사 자체 회귀: HTML 해석 예외는 파일·횟수·쓰는 곳이 모두 맞을 때만 통과한다")
    void serverHtmlExceptionIsNarrow() {
        String once = "<div dangerouslySetInnerHTML={{ __html: html }} />";
        assertThat(findViolations(SERVER_HTML_FILE, once)).isEmpty();
        assertThat(findViolations(SERVER_HTML_FILE, once + once)).as("2회").isNotEmpty();
        assertThat(findViolations(SERVER_HTML_FILE, once + "el.innerHTML = x")).as("다른 방식").isNotEmpty();
        assertThat(findViolations("admin/post/PostList.tsx", once)).as("다른 파일").isNotEmpty();

        String use = "import { ServerHtml } from '../shared/ServerHtml'\n<ServerHtml id=\"v\" html={detail.content} />";
        assertThat(findViolations(SERVER_HTML_USER, use)).isEmpty();
        assertThat(findViolations(SERVER_HTML_USER, use.replace("detail.content", "detail.title"))).as("다른 값").isNotEmpty();
        assertThat(findViolations(SERVER_HTML_USER, use + "<ServerHtml html={detail.content} />")).as("두 번째 사용").isNotEmpty();
        assertThat(findViolations(SERVER_HTML_USER, use + "const Alias = ServerHtml")).as("별칭").isNotEmpty();
        assertThat(findViolations("admin/faq/FaqModal.tsx", use)).as("다른 화면").isNotEmpty();
    }

    private static long count(Pattern pattern, String source) {
        return pattern.matcher(source).results().count();
    }

    private static List<String> findViolations(String source) {
        return findViolations("admin/any.tsx", source);
    }

    private static List<String> findViolations(String name, String source) {
        List<String> found = new ArrayList<>();
        if (name.equals(SERVER_HTML_USER)) {
            // import 1 + 사용 1 = 이름이 나오는 횟수 전부. 파일에 이름이 없으면(쓰지 않으면) 위반이 아니다
            long names = count(SERVER_HTML_NAME, source);
            boolean exact = names == 3 && count(SERVER_HTML_IMPORT, source) == 1 && count(SERVER_HTML_USE, source) == 1;   // import 줄에는 이름과 경로로 두 번 나온다
            if (names > 0 && !exact) {
                found.add("ServerHtml은 detail.content에 한 번만 쓴다");
            }
        } else if (!name.equals(SERVER_HTML_FILE) && count(SERVER_HTML_NAME, source) > 0) {
            found.add("ServerHtml은 " + SERVER_HTML_USER + "에서만 쓴다");
        }
        for (Pattern pattern : FORBIDDEN) {
            if (name.equals(SERVER_HTML_FILE) && pattern.pattern().equals(DANGEROUS_HTML.pattern()) && count(pattern, source) == 1) {
                continue;
            }
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                found.add(matcher.group().strip());
            }
        }
        return found;
    }
}
