package com.cms.common.html;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 공지 서비스에서 옮긴 본문 검증 규칙(동작 불변 추출 — PLAN-board.md 쟁점 7). */
class ContentBodyPolicyTest {

    @Test
    @DisplayName("형식 표식이 HTML이 아니면(null 포함) 오래된 편집 화면으로 보고 거부한다")
    void rejectsNonHtmlFormat() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> ContentBodyPolicy.sanitize("<p>x</p>", "PLAIN"));
        assertTrue(e.getMessage().contains("편집 화면이 오래되었습니다"));
        assertThrows(InvalidRequestException.class, () -> ContentBodyPolicy.sanitize("<p>x</p>", null));
    }

    @Test
    @DisplayName("정리된 HTML과 참조 이미지 ID를 돌려주고 위험한 태그는 제거한다")
    void sanitizesAndReturnsImageIds() {
        SanitizedHtml result = ContentBodyPolicy.sanitize(
                "<p>본문<script>alert(1)</script><img src=\"/content-images/7\"></p>", "HTML");

        assertFalse(result.html().contains("script"));
        assertTrue(result.html().contains("/content-images/7"));
        assertEquals(1, result.imageIds().size());
        assertTrue(result.imageIds().contains(7L));
    }

    @Test
    @DisplayName("보이는 글자도 이미지도 없는 본문은 공백으로 거부, 이미지만 있는 본문은 허용")
    void blankBody() {
        assertThrows(InvalidRequestException.class, () -> ContentBodyPolicy.sanitize("<p><br></p>", "HTML"));
        assertEquals(1, ContentBodyPolicy.sanitize("<p><img src=\"/content-images/3\"></p>", "HTML").imageIds().size());
    }

    @Test
    @DisplayName("보이는 글자 10,001자는 허용, 10,002자는 거부")
    void textLengthBoundary() {
        // 문단 하나 = 글자 수 + 문단 종료 1자
        ContentBodyPolicy.sanitize("<p>" + "가".repeat(10_000) + "</p>", "HTML");
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> ContentBodyPolicy.sanitize("<p>" + "가".repeat(10_001) + "</p>", "HTML"));
        assertTrue(e.getMessage().contains("10,000자"));
    }

    @Test
    @DisplayName("정리된 HTML이 200,000바이트를 넘으면 거부")
    void byteLimit() {
        // 글자 수 제한(10,001)에는 걸리지 않으면서 서식 태그로 바이트만 키운다 — 문단 3,000개 × 약 70바이트
        String body = "<p><strong>가</strong></p>".repeat(8_000);
        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> ContentBodyPolicy.sanitize(body, "HTML"));
        assertTrue(e.getMessage().contains("서식이 너무 많습니다") || e.getMessage().contains("10,000자"));
    }
}
