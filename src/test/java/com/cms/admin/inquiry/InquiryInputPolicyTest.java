package com.cms.admin.inquiry;

import com.cms.admin.inquiry.service.InquiryInputPolicy;
import com.cms.admin.inquiry.service.NormalizedInquiry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 공개 문의 폼의 최종 입력 정책(PLAN-inquiry.md 쟁점 8) — DB 없이 캡차 전에 실행되는 검증. */
class InquiryInputPolicyTest {

    private static InquiryInputPolicy.Result check(String name, String email, String title, String body, boolean consent) {
        return InquiryInputPolicy.check(name, email, title, body, consent);
    }

    private static InquiryInputPolicy.Result valid() {
        return check("홍길동", "visitor@example.com", "배송 문의", "언제 도착하나요?", true);
    }

    @Test
    @DisplayName("정상 입력은 오류 없이 정규화된 값을 돌려준다 — 이름·제목은 trim, 본문 개행은 \\n으로, 이메일은 소문자화하지 않는다")
    void validInput_normalized() {
        InquiryInputPolicy.Result result = check("  홍길동  ", "  Visitor@Example.COM ", "  제목  ", "첫 줄\r\n둘째 줄\r셋째 줄", true);

        assertThat(result.valid()).isTrue();
        NormalizedInquiry value = result.value();
        assertThat(value.name()).isEqualTo("홍길동");
        assertThat(value.email()).isEqualTo("Visitor@Example.COM");
        assertThat(value.title()).isEqualTo("제목");
        assertThat(value.body()).isEqualTo("첫 줄\n둘째 줄\n셋째 줄");
    }

    @Test
    @DisplayName("동의하지 않으면 consent 오류 하나만 난다")
    void noConsent_rejected() {
        InquiryInputPolicy.Result result = check("홍길동", "a@b.co", "제목", "본문", false);

        assertThat(result.valid()).isFalse();
        assertThat(result.value()).isNull();
        assertThat(result.fieldErrors()).containsOnlyKeys("consent");
    }

    @Test
    @DisplayName("모든 필드가 잘못되면 필드별 오류가 모두 모인다(입력 순서 유지)")
    void everythingWrong_allErrorsCollected() {
        InquiryInputPolicy.Result result = check(null, "not-an-email", " ", "\u0000", false);

        assertThat(result.fieldErrors().keySet()).containsExactly("name", "email", "title", "body", "consent");
    }

    @Test
    @DisplayName("이름: 50자 초과·제어문자·양방향 제어문자·줄바꿈은 거부, 정확히 50자는 허용")
    void name_rules() {
        assertThat(check("가".repeat(50), "a@b.co", "제목", "본문", true).valid()).isTrue();
        assertThat(check("가".repeat(51), "a@b.co", "제목", "본문", true).fieldErrors()).containsKey("name");
        assertThat(check("홍\n길동", "a@b.co", "제목", "본문", true).fieldErrors()).containsKey("name");
        assertThat(check("홍‮길동", "a@b.co", "제목", "본문", true).fieldErrors()).containsKey("name");
        assertThat(check("홍\u0007길동", "a@b.co", "제목", "본문", true).fieldErrors()).containsKey("name");
        assertThat(check("a".repeat(101), "a@b.co", "제목", "본문", true).fieldErrors()).containsKey("name");   // 원문 크기 방어
    }

    @Test
    @DisplayName("이메일: 형식·길이·금지 문자 검사 — 공백·따옴표·꺾쇠·쉼표·세미콜론·여러 @는 거부, 정확히 100자는 허용")
    void email_rules() {
        assertThat(check("n", "a@b.co", "t", "b", true).valid()).isTrue();
        String local = "a".repeat(100 - "@b.co".length());
        assertThat(check("n", local + "@b.co", "t", "b", true).valid()).isTrue();
        assertThat(check("n", local + "a@b.co", "t", "b", true).fieldErrors()).containsKey("email");   // 101자
        for (String bad : new String[]{"plain", "a@b", "a @b.co", "a@@b.co", "a@b@c.co", "<a@b.co>", "\"a\"@b.co", "a,b@c.co", "a@b.co;c@d.co",
                "a@b.co\r\nBcc: x@y.co", "@b.co", "a@.co"}) {
            assertThat(check("n", bad, "t", "b", true).fieldErrors()).as(bad).containsKey("email");
        }
        assertThat(check("n", "a".repeat(201), "t", "b", true).fieldErrors()).containsKey("email");   // 원문 크기 방어
    }

    @Test
    @DisplayName("제목·본문은 쪽지와 같은 정책 — 제목 100자·본문 2000자 경계, 제어문자·고립 서로게이트 거부, 본문의 \\n·\\t는 허용")
    void titleAndBody_rules() {
        assertThat(check("n", "a@b.co", "가".repeat(100), "b", true).valid()).isTrue();
        assertThat(check("n", "a@b.co", "가".repeat(101), "b", true).fieldErrors()).containsKey("title");
        assertThat(check("n", "a@b.co", "t", "가".repeat(2000), true).valid()).isTrue();
        assertThat(check("n", "a@b.co", "t", "가".repeat(2001), true).fieldErrors()).containsKey("body");
        assertThat(check("n", "a@b.co", "t", "줄\n바꿈\t탭", true).valid()).isTrue();
        assertThat(check("n", "a@b.co", "t", "x\u0000y", true).fieldErrors()).containsKey("body");
        assertThat(check("n", "a@b.co", "t", "x\uD800y", true).fieldErrors()).containsKey("body");
        assertThat(check("n", "a@b.co", "한 줄\n두 줄", "b", true).fieldErrors()).containsKey("title");
    }

    @Test
    @DisplayName("오류 문구는 고정 한국어이고 입력 원문을 담지 않는다")
    void errorMessages_doNotEchoInput() {
        String marker = "MARKER-<script>alert(1)</script>";
        InquiryInputPolicy.Result result = check(marker + "\n", marker, marker + "\n", "\u0000" + marker, false);

        assertThat(result.fieldErrors().values()).isNotEmpty().allSatisfy(message -> assertThat(message).doesNotContain("MARKER"));
        assertThat(valid().valid()).isTrue();
    }
}
