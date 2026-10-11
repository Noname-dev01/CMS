package com.cms.publicweb.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 공개 메인 팝업 조각의 규약(PLAN-popup.md 쟁점 9). 메인 템플릿({@code PublicHomeTemplateConventionTest})은 비이스케이프 출력과 스크립트가 한 곳도
 * 없어야 하므로 팝업을 별도 조각으로 분리했다 — 그 대신 이 조각에는 <b>정해진 한 곳</b>만 허용한다.
 * <ul>
 *   <li>{@code th:utext}는 정확히 1곳이고 {@code popup.bodyHtml}만 받는다(출력 시 한 번 더 정리된 값 — {@code PublicPopup}). 인라인 {@code [(…)]}는 없다.</li>
 *   <li>스크립트는 외부 파일 {@code <script th:src>} 1개뿐이고 인라인 스크립트 본문이 없다.</li>
 *   <li>이미지는 {@code src}가 아니라 {@code data-src}로만 내보낸다(숨겨진 팝업·스크립트 없는 환경이 이미지를 내려받지 않게 — R1-4).</li>
 *   <li>팝업 CSS는 {@code [hidden]} 기본 숨김을 덮어쓰지 않는다({@code display:none !important} + flex는 {@code :not([hidden])}에만 — R2-1).</li>
 * </ul>
 * 메인 템플릿은 조각을 포함만 하고 여전히 비이스케이프 출력·스크립트가 없다.
 */
class PublicHomePopupTemplateConventionTest {

    private static final String FRAGMENT = "templates/public/fragments/home-popups.html";
    private static final String HOME = "templates/public/home.html";
    private static final String CSS = "static/css/public/home.css";
    private static final String SCRIPT = "static/js/public/home-popup.js";

    @Test
    @DisplayName("조각의 th:utext는 정확히 1곳이고 popup.bodyHtml만 받으며, 인라인 비이스케이프 출력([( ... ))은 없다")
    void fragment_hasExactlyOneUtext() throws IOException {
        String content = read(FRAGMENT);

        Matcher utext = Pattern.compile("th:utext=\"([^\"]*)\"").matcher(content);
        int count = 0;
        while (utext.find()) {
            count++;
            assertEquals("${popup.bodyHtml}", utext.group(1), "th:utext는 정리된 본문(popup.bodyHtml)만 받아야 합니다.");
        }
        assertEquals(1, count, "th:utext가 정확히 1곳이어야 합니다.");
        assertFalse(content.contains("[("), "조각에 인라인 비이스케이프 출력이 있습니다.");
        assertFalse(content.contains("storageKey"), "조각에 storageKey(서버 내부 경로)가 있습니다.");
    }

    @Test
    @DisplayName("조각의 스크립트는 외부 파일 1개뿐이고 인라인 스크립트 본문·이벤트 핸들러 속성이 없다")
    void fragment_hasOnlyExternalScript() throws IOException {
        String content = read(FRAGMENT);

        Matcher script = Pattern.compile("<script\\b([^>]*)>(.*?)</script>", Pattern.DOTALL).matcher(content);
        int count = 0;
        while (script.find()) {
            count++;
            assertTrue(script.group(1).contains("th:src=\"@{/js/public/home-popup.js}\""), "외부 스크립트는 home-popup.js 하나여야 합니다.");
            assertEquals("", script.group(2).strip(), "인라인 스크립트 본문이 있습니다.");
        }
        assertEquals(1, count, "<script>가 정확히 1개여야 합니다.");
        assertFalse(Pattern.compile("\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(content).find(), "인라인 이벤트 핸들러 속성(onclick 등)이 있습니다.");
    }

    @Test
    @DisplayName("이미지는 src가 아니라 data-src로만 내보내고, 팝업은 hidden으로 시작한다")
    void fragment_imagesUseDataSrc_andPopupsStartHidden() throws IOException {
        String content = read(FRAGMENT);

        assertFalse(Pattern.compile("<img\\b[^>]*\\s(th:)?src=", Pattern.CASE_INSENSITIVE).matcher(content).find(),
                "img에 src(th:src 포함)가 있습니다 — 보이기로 한 팝업의 이미지만 스크립트가 data-src로 채웁니다.");
        assertTrue(content.contains("data-src=@{/popups/{id}/image(id=${popup.id})}"), "이미지는 공개 경로(/popups/{id}/image)의 data-src여야 합니다.");
        assertTrue(Pattern.compile("<section\\b[^>]*\\shidden\\b").matcher(content).find(), "팝업 요소는 hidden으로 시작해야 합니다.");
    }

    @Test
    @DisplayName("메인 템플릿은 조각을 포함만 하고 비이스케이프 출력·스크립트가 여전히 없다")
    void home_includesFragmentOnly() throws IOException {
        String home = read(HOME);

        assertTrue(home.contains("public/fragments/home-popups :: popups("), "메인이 팝업 조각을 포함해야 합니다.");
        assertFalse(home.contains("th:utext"), "home.html에 th:utext가 있습니다.");
        assertFalse(home.contains("<script"), "home.html에 스크립트가 있습니다.");
    }

    @Test
    @DisplayName("팝업 CSS는 [hidden]을 !important로 지키고 flex 레이아웃은 :not([hidden])에만 적용한다")
    void css_doesNotOverrideHidden() throws IOException {
        String css = read(CSS);

        assertTrue(Pattern.compile("\\.home-popup\\[hidden\\]\\s*\\{\\s*display:\\s*none\\s*!important;?\\s*\\}").matcher(css).find(),
                ".home-popup[hidden] { display: none !important; } 규칙이 필요합니다.");
        assertTrue(css.contains(".home-popup:not([hidden])"), "flex 레이아웃은 .home-popup:not([hidden])에만 적용해야 합니다.");
        assertFalse(Pattern.compile("\\.home-popup\\s*\\{[^}]*display:\\s*flex").matcher(css).find(), ".home-popup 본체에 display:flex를 직접 주면 [hidden]이 무력화됩니다.");
        // 머리 40 + 내용 최소 48 + 바닥 48 = 136px — 지정 높이(--popup-h)가 이보다 낮아도 flex:none인 조작 영역이 잘리지 않아야 한다(코드 리뷰 R1)
        Matcher minHeight = Pattern.compile("\\.home-popup:not\\(\\[hidden\\]\\)\\s*\\{[^}]*min-height:\\s*(\\d+)px").matcher(css);
        assertTrue(minHeight.find(), ".home-popup:not([hidden])에 min-height가 필요합니다.");
        assertTrue(Integer.parseInt(minHeight.group(1)) >= 136, "min-height는 머리 40 + 내용 최소 48 + 바닥 48 = 136px 이상이어야 합니다.");
    }

    @Test
    @DisplayName("팝업 스크립트는 localStorage 접근을 모두 try/catch로 감싸고 eval·innerHTML을 쓰지 않는다")
    void script_isDefensive() throws IOException {
        String js = read(SCRIPT);

        assertFalse(js.contains("eval("), "eval을 쓰면 안 됩니다.");
        assertFalse(js.contains("innerHTML"), "innerHTML을 쓰면 안 됩니다.");
        assertFalse(js.contains("document.write"), "document.write를 쓰면 안 됩니다.");
        Matcher storage = Pattern.compile("localStorage\\.(getItem|setItem)").matcher(js);
        int accesses = 0;
        while (storage.find()) {
            accesses++;
            int tryAt = js.lastIndexOf("try {", storage.start());
            int catchAt = js.indexOf("catch (e)", storage.end());
            assertTrue(tryAt >= 0 && catchAt > storage.end(), "localStorage 접근이 try/catch 밖에 있습니다.");
        }
        assertEquals(2, accesses, "localStorage 접근은 읽기 1곳·쓰기 1곳이어야 합니다.");
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
