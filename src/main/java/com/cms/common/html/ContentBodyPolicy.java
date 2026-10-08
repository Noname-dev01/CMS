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
     * 되므로(V25), 경계의 기존 공지도 다시 저장할 수 있다(PLAN-html-editor.md 쟁점 5·R3-1).
     */
    public static final int MAX_CONTENT_TEXT_LENGTH = 10_001;

    /** 정리된 HTML 저장 상한(컬럼은 MEDIUMTEXT — V23). 기존 평문 변환본의 최악값 109,983바이트를 넉넉히 넘는다. */
    public static final int MAX_CONTENT_BYTES = 200_000;

    private ContentBodyPolicy() {
    }

    /**
     * 본문 HTML 정리·검증(PLAN-html-editor.md 쟁점 5). 형식 표식이 "HTML"이 아니면 배포 전에 열어 둔 평문 편집 화면의 요청이라
     * 거부한다(R2-2) — 평문을 HTML로 해석하면 문자 그대로 쓴 태그가 서식으로 바뀐다.
     */
    public static SanitizedHtml sanitize(String rawContent, String contentFormat) {
        if (!CONTENT_FORMAT_HTML.equals(contentFormat)) {
            throw new InvalidRequestException("편집 화면이 오래되었습니다. 새로고침 후 다시 저장해 주세요.");
        }
        SanitizedHtml sanitized = HtmlContentSanitizer.sanitize(rawContent);
        if (sanitized.blank()) {
            throw new InvalidRequestException("본문은 공백일 수 없습니다.");
        }
        if (sanitized.textLength() > MAX_CONTENT_TEXT_LENGTH) {
            throw new InvalidRequestException("본문은 10,000자 이하로 입력해주세요.");
        }
        if (sanitized.html().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new InvalidRequestException("본문 서식이 너무 많습니다. 내용을 줄여주세요.");
        }
        return sanitized;
    }
}
