package com.cms.admin.menu.dto.request.validation;

import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 메뉴 생성·수정 요청 DTO의 {@code menuUrl} 검증(@SafeMenuUrl + @Size) — 실제 Bean Validation 엔진으로 확인한다. */
class SafeMenuUrlValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private int createViolations(String url) {
        return validator.validate(MenuCreateRequest.builder().menuName("메뉴").menuUrl(url).build()).size();
    }

    private int updateViolations(String url) {
        return validator.validate(MenuUpdateRequest.builder().menuUrl(url).build()).size();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "   ", "/", "/admin/x", "/admin/notice/manage?page=2&sort=id,desc", "https://example.com/x", "http://example.com"
    })
    @DisplayName("허용: null·빈 값(서비스가 null로 정규화)·경로(쿼리스트링 포함)·외부 http(s)")
    void allowed(String url) {
        assertEquals(0, createViolations(url));
        assertEquals(0, updateViolations(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "data:text/html,x", "//evil.example", "https://evil@example.com", "admin/x",
            "/admin\\x", "/admin x", "/admin\u0000x", "/admin\nx"
    })
    @DisplayName("거부: javascript:·data:·//host·userinfo·상대 경로·역슬래시·공백·제어문자")
    void rejected(String url) {
        assertEquals(1, createViolations(url));
        assertEquals(1, updateViolations(url));
    }

    @Test
    @DisplayName("거부: 255자 초과는 길이 제약에 걸린다(형식이 맞아도)")
    void tooLong() {
        String longPath = "/" + "a".repeat(255);
        assertEquals(256, longPath.length());
        assertTrue(createViolations(longPath) >= 1);
        assertTrue(updateViolations(longPath) >= 1);
        assertEquals(0, createViolations("/" + "a".repeat(254)));
    }
}
