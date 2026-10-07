package com.cms.common.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 편집기(Quill) 본문 HTML의 허용 목록 sanitizer. 저장 시와 출력 시 모두 거친다(PLAN-html-editor.md 쟁점 4) —
 * 그래서 결과는 <b>멱등</b>이어야 한다({@code sanitize(sanitize(x).html()).html() == sanitize(x).html()}).
 *
 * <p>허용 서식은 편집기 {@code formats}·툴바와 1:1이다(쟁점 3): {@code p br strong em u s h2 h3 ol ul li blockquote a img}.
 * {@code style}·{@code class}·{@code on*}·{@code data-*}는 전부 버린다. 처리 순서:
 * <ol>
 *   <li>제목 단계 매핑 — {@code h1}→{@code h2}, {@code h4~h6}→{@code h3}(편집기 clipboard matcher와 같은 결과, 쟁점 3·R2-5)</li>
 *   <li>jsoup {@link Cleaner}로 허용 목록 밖 태그·속성 제거(태그만 벗기고 텍스트는 남김), 링크는 http/https/mailto만,
 *       {@code rel}·{@code target} 강제</li>
 *   <li>이미지 src 제한 — {@code /content-images/{id}}({@code Long} 범위)만 남기고 나머지 {@code img}는 제거(쟁점 6)</li>
 *   <li>최상위 인라인 노드를 {@code p}로 감싸고, 블록별 공백 정규화(쟁점 3-1)와 빈 블록의 {@code <br>} 보충</li>
 * </ol>
 *
 * <p>공백 정규화가 필요한 이유: Quill 2.0.3은 HTML을 읽을 때 일반 공백 연속을 하나로 접고 블록 앞뒤 공백을 지우지만
 * U+00A0은 보존한다. 반대로 {@code getSemanticHTML()}은 모든 공백을 {@code &nbsp;}로 내보내 줄바꿈 위치를 없앤다(스파이크 실측).
 * 그래서 서버가 단일 원본으로 "연속 공백의 첫 칸만 일반 공백, 나머지와 블록 앞뒤 공백은 {@code &nbsp;}" 형식으로 맞춘다.
 * 탭·줄바꿈 문자도 공백 1칸으로 본다(글자 수 1:1, R3-2).
 */
public final class HtmlContentSanitizer {

    /** 본문 이미지 공개 경로. 다운로드 컨트롤러 경로와 같아야 한다. */
    public static final String CONTENT_IMAGE_PATH_PREFIX = "/content-images/";

    private static final Pattern CONTENT_IMAGE_SRC = Pattern.compile("^/content-images/([1-9][0-9]{0,18})$");

    /** 길이 계산·공백 정규화의 단위 블록. 허용 목록의 블록 태그와 같다. */
    private static final Set<String> BLOCK_TAGS = Set.of("p", "li", "h2", "h3", "blockquote");

    private static final Set<String> LIST_TAGS = Set.of("ol", "ul");

    private static final char NBSP = ' ';

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "em", "u", "s", "h2", "h3", "ol", "ul", "li", "blockquote", "a", "img")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addEnforcedAttribute("a", "target", "_blank")
            // img src는 프로토콜 검사 대신 아래 후처리에서 경로 형식으로만 허용한다(상대 경로라 프로토콜 검사로는 표현 불가)
            .addAttributes("img", "src", "alt");

    private HtmlContentSanitizer() {
    }

    /** 고정점 반복 상한(무한 반복 방지용). */
    private static final int MAX_PASSES = 5;

    /**
     * 정리 결과를 다시 파싱하면 구조가 바뀌는 형태(인라인 안의 블록, 제목 안의 제목 등)는 하나씩 열거해 막을 수 없다 —
     * 결과를 다시 정리해 더 바뀌지 않을 때(고정점)의 결과를 쓴다. 길이·공백 판정도 그 결과 기준이라 저장 시와 출력 시가 같다.
     */
    public static SanitizedHtml sanitize(String html) {
        SanitizedHtml result = sanitizeOnce(html);
        for (int pass = 1; pass < MAX_PASSES; pass++) {
            SanitizedHtml again = sanitizeOnce(result.html());
            if (again.html().equals(result.html())) {
                return again;
            }
            result = again;
        }
        return result;
    }

    private static SanitizedHtml sanitizeOnce(String html) {
        Document dirty = Jsoup.parseBodyFragment(html == null ? "" : html, "");
        mapHeadings(dirty.body());

        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8)
                .escapeMode(Entities.EscapeMode.base);
        Element body = clean.body();

        Set<Long> imageIds = filterImages(body);
        wrapTopLevelInline(body);
        for (Element block : leafBlocks(body)) {
            normalizeWhitespace(block);
            if (!block.hasText() && block.select("img, br").isEmpty()) {
                block.appendElement("br");
            }
        }

        return new SanitizedHtml(body.html(), visibleLength(body), imageIds, isBlank(body, imageIds));
    }

    /**
     * 이미지 src가 본문 이미지 경로가 아니면(외부 URL·{@code data:}·{@code Long} 범위 밖 ID 등) 그 {@code img}를 지운다.
     *
     * @return 남은 이미지의 ID(등장 순서)
     */
    private static Set<Long> filterImages(Element body) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Element img : body.select("img")) {
            Long id = parseContentImageId(img.attr("src"));
            if (id == null) {
                img.remove();
            } else {
                ids.add(id);
            }
        }
        return ids;
    }

    /** {@code /content-images/{id}} 형식이면 ID, 아니면 null. 19자리 중 {@code Long} 범위 밖은 null(R1-10). */
    public static Long parseContentImageId(String src) {
        if (src == null) {
            return null;
        }
        Matcher matcher = CONTENT_IMAGE_SRC.matcher(src);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void mapHeadings(Element body) {
        body.select("h1").tagName("h2");
        body.select("h4, h5, h6").tagName("h3");
    }

    /**
     * 최상위의 연속된 인라인 노드(텍스트·strong·a·img·br 등)를 하나의 {@code p}로 감싼다.
     * 블록·목록 자손을 가진 인라인 서식은 먼저 벗긴다 — {@code p} 안의 블록은 다시 파싱할 때 구조가 바뀌어 멱등성이 깨진다.
     */
    private static void wrapTopLevelInline(Element body) {
        for (Element inline : body.select("strong, em, u, s, a")) {
            if (!inline.select("p, li, h2, h3, blockquote, ol, ul").isEmpty()) {
                inline.unwrap();
            }
        }
        List<Node> run = new ArrayList<>();
        for (Node child : new ArrayList<>(body.childNodes())) {
            if (child instanceof Element element
                    && (BLOCK_TAGS.contains(element.normalName()) || LIST_TAGS.contains(element.normalName()))) {
                wrap(run);
                run.clear();
            } else {
                run.add(child);
            }
        }
        wrap(run);
    }

    private static void wrap(List<Node> run) {
        boolean meaningful = run.stream().anyMatch(n -> !(n instanceof TextNode t) || !t.isBlank());
        if (!meaningful) {
            run.forEach(Node::remove);
            return;
        }
        Element p = new Element("p");
        run.get(0).before(p);
        run.forEach(p::appendChild);
    }

    /** 블록 태그 중 블록 자식이 없는 것(공백 정규화·빈 블록 보충의 단위). */
    private static List<Element> leafBlocks(Element body) {
        List<Element> leaves = new ArrayList<>();
        for (Element element : body.getAllElements()) {
            if (BLOCK_TAGS.contains(element.normalName()) && element.children().stream()
                    .noneMatch(c -> BLOCK_TAGS.contains(c.normalName()) || LIST_TAGS.contains(c.normalName()))) {
                leaves.add(element);
            }
        }
        return leaves;
    }

    /**
     * 블록의 텍스트를 {@code br}로 나눈 구간마다 공백을 정규화한다. 구간 안의 공백 연속은 블록(구간) 앞뒤에 있으면 전부
     * {@code &nbsp;}, 가운데 있으면 첫 칸만 일반 공백이고 나머지는 {@code &nbsp;}다. 문자 수는 바꾸지 않는다(1:1 치환).
     */
    private static void normalizeWhitespace(Element block) {
        List<TextNode> segment = new ArrayList<>();
        for (Node node : descendants(block)) {
            if (node instanceof TextNode text) {
                segment.add(text);
            } else if (node instanceof Element element && "br".equals(element.normalName())) {
                normalizeSegment(segment);
                segment = new ArrayList<>();
            }
        }
        normalizeSegment(segment);
    }

    private static void normalizeSegment(List<TextNode> segment) {
        if (segment.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (TextNode text : segment) {
            joined.append(text.getWholeText());
        }
        char[] chars = joined.toString().toCharArray();
        int i = 0;
        while (i < chars.length) {
            if (!isSpace(chars[i])) {
                i++;
                continue;
            }
            int start = i;
            while (i < chars.length && isSpace(chars[i])) {
                i++;
            }
            boolean atEdge = start == 0 || i == chars.length;
            for (int k = start; k < i; k++) {
                chars[k] = (k == start && !atEdge) ? ' ' : NBSP;
            }
        }
        int offset = 0;
        for (TextNode text : segment) {
            int length = text.getWholeText().length();
            text.text(new String(chars, offset, length));
            offset += length;
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == NBSP || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     * 보이는 텍스트 길이(쟁점 5, R2-7·R3-1): 텍스트 노드의 코드 포인트 수(정규화 없이) + 블록 종료마다 1 +
     * 속한 블록의 끝이 아닌 {@code br}마다 1. 블록 끝 {@code br}(빈 줄 {@code <p><br></p>})은 블록 종료와 같은 줄바꿈이라 세지 않는다.
     */
    private static int visibleLength(Element body) {
        int length = 0;
        for (Node node : descendants(body)) {
            if (node instanceof TextNode text) {
                String value = text.getWholeText();
                length += value.codePointCount(0, value.length());
            } else if (node instanceof Element element) {
                if (BLOCK_TAGS.contains(element.normalName())) {
                    length++;
                } else if ("br".equals(element.normalName()) && !endsBlock(element)) {
                    length++;
                }
            }
        }
        return length;
    }

    /** {@code br}이 속한 블록의 끝인지 — 인라인 서식의 마지막 자식이어도 그 뒤에 같은 블록의 내용이 있으면 끝이 아니다. */
    private static boolean endsBlock(Element br) {
        Node node = br;
        while (node.nextSibling() == null) {
            Node parent = node.parent();
            if (!(parent instanceof Element element) || BLOCK_TAGS.contains(element.normalName())) {
                return true;
            }
            node = parent;
        }
        return false;
    }

    private static boolean isBlank(Element body, Set<Long> imageIds) {
        if (!imageIds.isEmpty()) {
            return false;
        }
        for (Node node : descendants(body)) {
            if (node instanceof TextNode text) {
                for (char c : text.getWholeText().toCharArray()) {
                    // 공백 정규화(isSpace)보다 넓게 본다 — 전각 공백(U+3000)·EM SPACE 등만 있는 본문도 비어 있는 것으로 거부한다
                    if (!isSpace(c) && !Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 문서 순서(전위 순회)의 자손 노드. 자기 자신은 제외. */
    private static List<Node> descendants(Element root) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(Node parent, List<Node> nodes) {
        for (Node child : parent.childNodes()) {
            nodes.add(child);
            collect(child, nodes);
        }
    }
}
