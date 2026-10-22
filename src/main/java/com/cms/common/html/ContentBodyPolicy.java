package com.cms.common.html;

import com.cms.common.exception.InvalidRequestException;

import java.nio.charset.StandardCharsets;

/**
 * 편집기 본문(HTML) 정리·검증 규칙 — 공지와 게시글이 같은 상한·같은 메시지를 쓴다(PLAN-board.md 쟁점 7).
 * 공지 서비스({@code NoticeService})에서 동작 변경 없이 옮겼다.
 */
public final class ContentBodyPolicy {

    public static final String CONTENT_FORMAT_HTML = "HTML";

    /**
     * 보이는 텍스트 상한. 기존 "본문 10,000자"에 마지막 문단 종료 1자를 더한 값 — 기존 평문 N자는 HTML 변환 후 N+1자 이하가
     * 되므로(평문 공지의 HTML 변환 규칙), 경계의 기존 공지도 다시 저장할 수 있다(PLAN-html-editor.md 쟁점 5·R3-1).
     */
    public static final int MAX_CONTENT_TEXT_LENGTH = 10_001;

    /** 정리된 HTML 저장 상한(컬럼은 MEDIUMTEXT). 기존 평문 변환본의 최악값 109,983바이트를 넉넉히 넘는다. */
    public static final int MAX_CONTENT_BYTES = 200_000;

    /**
     * 정적 페이지 본문 상한(PLAN-static-page.md 쟁점 8) — 보이는 글자 50,000자(+ 마지막 문단 종료 1자), 정리 후 500,000바이트.
     * 요청 DTO의 원문 상한은 {@link #PAGE_MAX_RAW_BYTES}로 sanitizer가 원문을 파싱하기 전에 거른다.
     */
    public static final int PAGE_MAX_TEXT_LENGTH = 50_001;
    public static final int PAGE_MAX_CONTENT_BYTES = 500_000;
    public static final int PAGE_MAX_RAW_BYTES = 600_000;

    /**
     * 팝업 HTML 본문 상한(PLAN-popup.md 쟁점 5) — 보이는 글자 2,000자(+ 마지막 문단 종료 1자), 정리 후 50,000바이트.
     * 요청 DTO의 원문 상한은 {@link #POPUP_MAX_RAW_BYTES}로 sanitizer가 원문을 파싱하기 전에 거른다.
     */
    public static final int POPUP_MAX_TEXT_LENGTH = 2_001;
    public static final int POPUP_MAX_CONTENT_BYTES = 50_000;
    public static final int POPUP_MAX_RAW_BYTES = 100_000;

    /**
     * FAQ 답변 상한(PLAN-faq.md 쟁점 7) — 보이는 글자 5,000자(+ 마지막 문단 종료 1자), 정리 후 30,000바이트. 공개 페이지가 노출 중 FAQ를 한 번에
     * 내려주므로 총량을 묶는다. 요청 DTO의 원문 상한은 {@link #FAQ_MAX_RAW_BYTES}로 sanitizer가 원문을 파싱하기 전에 거른다.
     */
    public static final int FAQ_MAX_TEXT_LENGTH = 5_001;
    public static final int FAQ_MAX_CONTENT_BYTES = 30_000;
    public static final int FAQ_MAX_RAW_BYTES = 60_000;

    private ContentBodyPolicy() {
    }

    /**
     * 본문 HTML 정리·검증(PLAN-html-editor.md 쟁점 5). 형식 표식이 "HTML"이 아니면 배포 전에 열어 둔 평문 편집 화면의 요청이라
     * 거부한다(R2-2) — 평문을 HTML로 해석하면 문자 그대로 쓴 태그가 서식으로 바뀐다.
     */
    public static SanitizedHtml sanitize(String rawContent, String contentFormat) {
        return sanitize(rawContent, contentFormat, true, MAX_CONTENT_TEXT_LENGTH, "10,000", MAX_CONTENT_BYTES);
    }

    /**
     * 정적 페이지 본문 규칙(PLAN-static-page.md 쟁점 7·8) — <b>이미지를 허용하지 않고</b>({@code img} 전부 제거) 상한이 게시글보다 크다.
     * 출력 시 정리는 {@link #sanitizeForPageOutput}로 같은 이미지 불허 규칙을 쓴다.
     */
    public static SanitizedHtml sanitizePage(String rawContent, String contentFormat) {
        return sanitize(rawContent, contentFormat, false, PAGE_MAX_TEXT_LENGTH, "50,000", PAGE_MAX_CONTENT_BYTES);
    }

    /** 정적 페이지 본문의 출력 시 정리 — 저장 시와 같은 이미지 불허 규칙(DB 직접 수정으로 들어온 {@code img}도 렌더하지 않는다). */
    public static String sanitizeForPageOutput(String storedContent) {
        return HtmlContentSanitizer.sanitize(storedContent, false).html();
    }

    /**
     * 팝업 HTML형 본문 규칙(PLAN-popup.md 쟁점 5) — 정적 페이지처럼 <b>이미지를 허용하지 않고</b>(이미지가 필요하면 이미지형 팝업을 쓴다)
     * 상한이 작다. 출력 시 정리는 {@link #sanitizeForPopupOutput}가 같은 규칙으로 한다.
     */
    public static SanitizedHtml sanitizePopup(String rawContent, String contentFormat) {
        return sanitize(rawContent, contentFormat, false, POPUP_MAX_TEXT_LENGTH, "2,000", POPUP_MAX_CONTENT_BYTES);
    }

    /** 팝업 본문의 출력 시 정리 — 저장 시와 같은 이미지 불허 규칙(DB 직접 수정으로 들어온 {@code script}·{@code img}도 렌더하지 않는다). */
    public static String sanitizeForPopupOutput(String storedContent) {
        return HtmlContentSanitizer.sanitize(storedContent, false).html();
    }

    /**
     * FAQ 답변 규칙(PLAN-faq.md 쟁점 7) — 정적 페이지·팝업처럼 <b>이미지를 허용하지 않는다</b>(본문 이미지 공개 판정에 FAQ를 끌어들이지 않기 위해).
     * 출력 시 정리는 {@link #sanitizeForFaqOutput}가 같은 규칙으로 한다.
     */
    public static SanitizedHtml sanitizeFaq(String rawContent, String contentFormat) {
        return sanitize(rawContent, contentFormat, false, FAQ_MAX_TEXT_LENGTH, "5,000", FAQ_MAX_CONTENT_BYTES);
    }

    /**
     * FAQ 답변의 출력 시 정리 — 저장 시와 같은 이미지 불허 규칙이다(DB 직접 수정으로 들어온 {@code script}·{@code img}도 렌더하지 않는다).
     * 결과의 {@link SanitizedHtml#blank()}가 참이면 보이는 내용이 없으니 호출자가 그 FAQ를 건너뛴다(문자열 {@code isBlank}는 {@code <p><br></p>}를 못 잡는다).
     */
    public static SanitizedHtml sanitizeForFaqOutput(String storedContent) {
        return HtmlContentSanitizer.sanitize(storedContent, false);
    }

    private static SanitizedHtml sanitize(String rawContent, String contentFormat, boolean allowImages,
                                          int maxTextLength, String maxTextLabel, int maxBytes) {
        if (!CONTENT_FORMAT_HTML.equals(contentFormat)) {
            throw new InvalidRequestException("편집 화면이 오래되었습니다. 새로고침 후 다시 저장해 주세요.");
        }
        SanitizedHtml sanitized = HtmlContentSanitizer.sanitize(rawContent, allowImages);
        if (sanitized.blank()) {
            throw new InvalidRequestException("본문은 공백일 수 없습니다.");
        }
        if (sanitized.textLength() > maxTextLength) {
            throw new InvalidRequestException("본문은 " + maxTextLabel + "자 이하로 입력해주세요.");
        }
        if (sanitized.html().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new InvalidRequestException("본문 서식이 너무 많습니다. 내용을 줄여주세요.");
        }
        return sanitized;
    }
}
