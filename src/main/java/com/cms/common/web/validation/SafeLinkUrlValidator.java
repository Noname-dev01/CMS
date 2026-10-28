package com.cms.common.web.validation;

import com.cms.common.web.BoardLinkPaths;
import com.cms.common.web.SafeUrls;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link SafeLinkUrl}의 검증 로직 — null·공백만 있는 값은 통과시키고 길이는 {@code @Size}가 담당한다. */
public class SafeLinkUrlValidator implements ConstraintValidator<SafeLinkUrl, String> {

    /** 숫자 게시판 경로 거부 문구 — 공개 게시판 주소는 슬러그만 쓴다(PLAN-board-definition.md PR 4a D5). */
    static final String NUMERIC_BOARD_PATH_MESSAGE = "게시판 주소는 슬러그 주소(예: /main/boards/board-12)를 쓰세요.";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!SafeUrls.isSafeLinkUrl(value)) {
            return false;
        }
        if (BoardLinkPaths.isNumericBoardPath(value)) {
            if (context != null) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(NUMERIC_BOARD_PATH_MESSAGE).addConstraintViolation();
            }
            return false;
        }
        return true;
    }
}
