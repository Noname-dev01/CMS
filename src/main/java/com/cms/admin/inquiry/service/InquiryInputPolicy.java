package com.cms.admin.inquiry.service;

import com.cms.admin.message.service.MessageTextPolicy;
import com.cms.common.exception.InvalidRequestException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 공개 문의 폼의 최종 입력 정책(PLAN-inquiry.md 쟁점 8). DB를 쓰지 않으며 <b>캡차 검증보다 먼저</b> 실행된다 — 어떤 형식 오류도 외부 호출을 일으키지 않는다.
 * 제목·본문은 쪽지와 같은 {@link MessageTextPolicy}(서로게이트·제어문자·양방향 제어문자·길이)를 그대로 쓰고, 이름은 한 줄 텍스트 규칙에 상한 50자를 더한다.
 * 이메일은 trim 후 형식·길이만 확인하고 <b>정규화(소문자화 등)하지 않는다</b> — 관리자가 회신 주소로 그대로 쓴다.
 * 오류 문구는 고정 한국어이며 입력을 되돌려주지 않는다.
 */
public final class InquiryInputPolicy {

    public static final int NAME_MAX = 50;
    public static final int EMAIL_MAX = 100;

    /** 원문 크기 방어(폼 바인딩의 {@code @Size}와 같은 값) — 거대한 입력이 정책 검사를 거치기 전에 걸러진다. */
    public static final int NAME_RAW_MAX = 100;
    public static final int EMAIL_RAW_MAX = 200;
    public static final int TITLE_RAW_MAX = MessageTextPolicy.TITLE_RAW_MAX;
    public static final int BODY_RAW_MAX = MessageTextPolicy.BODY_RAW_MAX;

    public static final String NAME_MESSAGE = "이름은 1~50자의 한 줄 텍스트여야 합니다.";
    public static final String EMAIL_MESSAGE = "올바른 이메일 주소를 입력해 주세요(100자 이내).";
    public static final String CONSENT_MESSAGE = "개인정보 수집·이용에 동의해야 문의를 보낼 수 있습니다.";

    /** 공백·따옴표·꺾쇠·쉼표·세미콜론 없이 {@code local@domain.tld} 모양. 엄밀한 RFC 검증이 아니라 헤더 주입·표시 오염을 막는 실용 검사다. */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s<>\",;]+@[^@\\s<>\",;]+\\.[^@\\s<>\",;]+");

    private InquiryInputPolicy() {
    }

    /** 검증 결과 — 오류가 없으면 {@code value}가 채워지고, 있으면 필드 이름 → 고정 문구 맵이다(입력 순서 유지). */
    public record Result(NormalizedInquiry value, Map<String, String> fieldErrors) {
        public boolean valid() {
            return fieldErrors.isEmpty();
        }
    }

    public static Result check(String rawName, String rawEmail, String rawTitle, String rawBody, boolean consent) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = normalizeName(rawName, errors);
        String email = normalizeEmail(rawEmail, errors);
        String title = null;
        try {
            title = MessageTextPolicy.normalizeTitle(rawTitle);
        } catch (InvalidRequestException e) {
            errors.put("title", e.getMessage());
        }
        String body = null;
        try {
            body = MessageTextPolicy.normalizeBody(rawBody);
        } catch (InvalidRequestException e) {
            errors.put("body", e.getMessage());
        }
        if (!consent) {
            errors.put("consent", CONSENT_MESSAGE);
        }

        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        return new Result(new NormalizedInquiry(name, email, title, body), errors);
    }

    private static String normalizeName(String raw, Map<String, String> errors) {
        if (raw == null || raw.length() > NAME_RAW_MAX) {
            errors.put("name", NAME_MESSAGE);
            return null;
        }
        try {
            // 한 줄 텍스트 규칙(제어문자·양방향 제어문자·고립 서로게이트 거부, trim)은 제목 정책을 재사용한다 — 상한만 이름에 맞게 더 조인다.
            String name = MessageTextPolicy.normalizeTitle(raw);
            if (name.length() > NAME_MAX) {
                errors.put("name", NAME_MESSAGE);
                return null;
            }
            return name;
        } catch (InvalidRequestException e) {
            errors.put("name", NAME_MESSAGE);
            return null;
        }
    }

    private static String normalizeEmail(String raw, Map<String, String> errors) {
        if (raw == null || raw.length() > EMAIL_RAW_MAX) {
            errors.put("email", EMAIL_MESSAGE);
            return null;
        }
        try {
            String email = MessageTextPolicy.normalizeTitle(raw);   // trim + 제어문자·서로게이트 거부
            if (email.length() > EMAIL_MAX || !EMAIL_SHAPE.matcher(email).matches()) {
                errors.put("email", EMAIL_MESSAGE);
                return null;
            }
            return email;
        } catch (InvalidRequestException e) {
            errors.put("email", EMAIL_MESSAGE);
            return null;
        }
    }
}
