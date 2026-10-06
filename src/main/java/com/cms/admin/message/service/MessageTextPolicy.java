package com.cms.admin.message.service;

import com.cms.common.exception.InvalidRequestException;

/**
 * 쪽지 제목·본문의 검증·정규화 파이프라인(PLAN-admin-message.md §5-B, R1-9). 순서는 고정이다:
 * <ol>
 *   <li>원문 크기 방어 — 요청 DTO의 {@code @Size}(제목 raw ≤ {@value #TITLE_RAW_MAX}, 본문 raw ≤ {@value #BODY_RAW_MAX} UTF-16 단위, 서비스 진입 전).
 *       개행 정규화는 길이를 줄이기만 하므로 정상 입력을 거부하지 않는다</li>
 *   <li>잘못된 UTF-16(고립 서로게이트) 거부 — 기본 인코딩은 이를 조용히 {@code ?}로 바꿔 서로 다른 입력이 같은 값이 된다</li>
 *   <li>개행 정규화 — 본문의 {@code \r\n}·{@code \r} → {@code \n}(제목은 개행 자체를 거부)</li>
 *   <li>허용 문자 검사 — 제목: 제어문자(Cc, 개행·탭 포함)·줄/문단 구분자(Zl·Zp)·양방향 제어문자 거부.
 *       본문: Cc 중 {@code \n}·{@code \t} 외, Zl·Zp, 양방향 제어문자 거부. ZWJ(U+200D) 등 일반 서식문자(Cf)는 허용한다 — 이모지 합성 시퀀스가 깨지지 않게</li>
 *   <li>최종 길이 — 제목 ≤ {@value #TITLE_MAX}(trim 후), 본문 ≤ {@value #BODY_MAX} UTF-16 단위, 공백만이면 거부</li>
 * </ol>
 * 서버는 HTML을 이스케이프하거나 걸러내지 않고 <b>원문 그대로</b> 저장한다(표시 계층이 {@code textContent}로만 그린다).
 * 실패 메시지는 <b>고정 한국어 문구</b>다 — 사용자 입력을 담지 않아 응답·감사 {@code errorMessage}가 입력을 되돌려주지 않는다.
 */
public final class MessageTextPolicy {

    public static final int TITLE_MAX = 100;
    public static final int BODY_MAX = 2000;
    public static final int TITLE_RAW_MAX = 200;
    public static final int BODY_RAW_MAX = 4000;

    static final String INVALID_TITLE_MESSAGE = "제목은 1~100자의 한 줄 텍스트여야 하며 제어문자를 포함할 수 없습니다.";
    static final String INVALID_BODY_MESSAGE = "본문은 1~2000자여야 하며 허용되지 않는 제어문자를 포함할 수 없습니다.";

    private MessageTextPolicy() {
    }

    /** 검증·trim을 거친 제목을 돌려준다. 위반이면 400({@link InvalidRequestException}, 고정 문구). */
    public static String normalizeTitle(String raw) {
        if (raw == null || raw.length() > TITLE_RAW_MAX || !isWellFormedUtf16(raw)) {
            throw new InvalidRequestException(INVALID_TITLE_MESSAGE);
        }
        if (containsForbidden(raw, false)) {
            throw new InvalidRequestException(INVALID_TITLE_MESSAGE);
        }
        String title = raw.strip();
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw new InvalidRequestException(INVALID_TITLE_MESSAGE);
        }
        return title;
    }

    /** 검증·개행 정규화를 거친 본문을 돌려준다. 위반이면 400({@link InvalidRequestException}, 고정 문구). */
    public static String normalizeBody(String raw) {
        if (raw == null || raw.length() > BODY_RAW_MAX || !isWellFormedUtf16(raw)) {
            throw new InvalidRequestException(INVALID_BODY_MESSAGE);
        }
        String body = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (containsForbidden(body, true)) {
            throw new InvalidRequestException(INVALID_BODY_MESSAGE);
        }
        if (body.isBlank() || body.length() > BODY_MAX) {
            throw new InvalidRequestException(INVALID_BODY_MESSAGE);
        }
        return body;
    }

    /** 쌍을 이루지 않은 고립 서로게이트가 없는가. */
    static boolean isWellFormedUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 허용되지 않는 문자가 있는가. {@code allowNewlineAndTab}가 true(본문)면 Cc 중 {@code \n}·{@code \t}만 허용한다.
     * 줄/문단 구분자(Zl·Zp)와 양방향 제어문자(U+202A~202E, U+2066~2069 — 표시 순서 위조 방지)는 항상 거부한다.
     */
    private static boolean containsForbidden(String value, boolean allowNewlineAndTab) {
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);

            int type = Character.getType(codePoint);
            if (type == Character.CONTROL) {
                if (allowNewlineAndTab && (codePoint == '\n' || codePoint == '\t')) {
                    continue;
                }
                return true;
            }
            if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return true;
            }
            if ((codePoint >= 0x202A && codePoint <= 0x202E) || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
                return true;
            }
        }
        return false;
    }
}
