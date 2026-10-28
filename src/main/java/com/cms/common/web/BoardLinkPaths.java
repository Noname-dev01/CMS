package com.cms.common.web;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 숫자 게시판 경로({@code /{siteKey}/boards/{숫자}(/…)?}) 판정. 공개 게시판 주소는 슬러그만 쓰므로 이 형태의 링크는 언제나 404다 —
 * 본문 sanitizer·배너/팝업 링크 검증·메뉴 링크 정책이 저장(과 출력)에서 거부하고, 링크 치환 마이그레이션(V10)이 남은 행을 이 함수로 찾아
 * 실패시킨다(PLAN-board-definition.md "PR 4a 상세 설계" D5).
 *
 * <p>브라우저가 실제로 이동하는 경로로 판정한다: 세그먼트마다 퍼센트 디코딩({@code %31%32} = {@code 12}, {@code %62oards} = {@code boards})하고
 * 점 세그먼트({@code .}·{@code ..}·{@code %2e})를 URL 표준처럼 정리한다. 디코딩은 {@code /}로 나눈 뒤에 해 {@code %2F}를 구분자로 보지 않는다
 * (브라우저도 그렇다). 사이트 키 자리는 존재 여부와 무관하게 형태만 본다 — 저장 시 판정이 사이트 상태에 따라 흔들리지 않게.
 *
 * <p>V10이 이 함수를 부르므로 규칙을 바꾸면 아직 V10을 적용하지 않은 DB의 결과가 달라진다 — 바꿀 때 {@code BoardSlugLinkUpgradeTest}를 함께 본다.
 */
public final class BoardLinkPaths {

    private BoardLinkPaths() {
    }

    /** 루트 상대 경로(쿼리·프래그먼트 포함 가능)가 숫자 게시판 경로인가. {@code /}로 시작하지 않거나 디코딩할 수 없으면 false. */
    public static boolean isNumericBoardPath(String url) {
        return numericBoardSiteSegment(url) != null;
    }

    /**
     * 숫자 게시판 경로이면 그 사이트 키 자리(디코딩·정리 후)를, 아니면 null. 링크 치환 마이그레이션이 "공개 사이트 키 + 숫자 게시판 경로"
     * (지금 열리는 링크)를 가려낼 때 쓴다.
     */
    public static String numericBoardSiteSegment(String url) {
        if (url == null || !url.startsWith("/")) {
            return null;
        }
        List<String> segments = normalizedSegments(pathPart(url));
        boolean numeric = segments != null && segments.size() >= 3 && !segments.get(0).isEmpty()
                && "boards".equals(segments.get(1)) && PathIds.parse(segments.get(2)) != null;
        return numeric ? segments.get(0) : null;
    }

    /** 첫 {@code ?}·{@code #} 앞. */
    static String pathPart(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                return url.substring(0, i);
            }
        }
        return url;
    }

    /** 앞의 {@code /}를 뗀 경로를 디코딩·점 세그먼트 정리한 세그먼트 목록. 잘못된 퍼센트 인코딩이면 null. */
    private static List<String> normalizedSegments(String path) {
        String[] raw = path.substring(1).split("/", -1);
        List<String> out = new ArrayList<>();
        for (String segment : raw) {
            String decoded;
            try {
                decoded = UriUtils.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (".".equals(decoded)) {
                continue;
            }
            if ("..".equals(decoded)) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(decoded);
        }
        return out;
    }
}
