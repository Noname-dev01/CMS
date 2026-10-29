package com.cms.publicweb.board.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * 웹진형 목록의 요약 — 저장된 본문 HTML 앞부분에서 텍스트만 뽑아 공백을 접고 {@link #MAX_CODE_POINTS} 코드 포인트로 자른다
 * (PLAN-board-definition.md PR 6 D5). 조회 때 계산하고 저장하지 않는다.
 *
 * <p>본문은 저장 시 이미 정리된 HTML이고 출력은 {@code th:text}(이스케이프)라 HTML로 다시 내보내지 않으므로 sanitizer를 다시 거치지 않는다.
 * 목록 50건 × 본문 최대 200,000바이트를 통째로 파싱하지 않도록 앞 {@link #PARSE_LIMIT} UTF-16 코드 유닛만 읽는다 — 그래서 앞부분이
 * 이미지·빈 문단뿐인 글은 요약이 비어 보일 수 있다(수용).
 */
final class PostExcerpt {

    static final int MAX_CODE_POINTS = 120;
    static final int PARSE_LIMIT = 20_000;
    private static final String ELLIPSIS = "…";

    private PostExcerpt() {
    }

    /** 요약 문자열. 본문이 없거나 텍스트가 비면 null(템플릿이 요약 줄을 생략한다). */
    static String of(String storedHtml) {
        if (storedHtml == null || storedHtml.isEmpty()) {
            return null;
        }
        String head = storedHtml;
        if (head.length() > PARSE_LIMIT) {
            int end = PARSE_LIMIT;
            // 서로게이트 쌍의 가운데를 자르지 않는다 — 짝 잃은 하위 서로게이트가 파서에 들어가지 않게 한 칸 앞에서 끊는다
            if (Character.isHighSurrogate(head.charAt(end - 1))) {
                end--;
            }
            head = head.substring(0, end);
        }
        StringBuilder text = new StringBuilder();
        collect(Jsoup.parseBodyFragment(head).body(), text);
        // 저장 형식의 공백은 &nbsp;(U+00A0)가 섞여 있다 — 모두 일반 공백으로 보고 연속을 한 칸으로 접는다
        String folded = text.toString().replace(' ', ' ').replaceAll("\\s+", " ").strip();
        if (folded.isEmpty()) {
            return null;
        }
        if (folded.codePointCount(0, folded.length()) <= MAX_CODE_POINTS) {
            return folded;
        }
        int cut = folded.offsetByCodePoints(0, MAX_CODE_POINTS);
        return folded.substring(0, cut).stripTrailing() + ELLIPSIS;
    }

    /** 텍스트 노드를 순서대로 모으되 블록 요소·{@code br} 경계에는 공백을 넣는다(문단이 붙어 한 단어처럼 읽히지 않게). */
    private static void collect(Node node, StringBuilder out) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                out.append(textNode.getWholeText());
            } else if (child instanceof Element element) {
                boolean boundary = element.isBlock() || "br".equals(element.normalName());
                if (boundary) {
                    out.append(' ');
                }
                collect(element, out);
                if (boundary) {
                    out.append(' ');
                }
            }
        }
    }
}
