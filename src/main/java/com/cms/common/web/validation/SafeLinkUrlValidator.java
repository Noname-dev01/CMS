package com.cms.common.web.validation;

import com.cms.common.web.BoardLinkPaths;
import com.cms.common.web.SafeUrls;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link SafeLinkUrl}의 검증 로직 — null·공백만 있는 값은 통과시키고 길이는 {@code @Size}가 담당한다. */
public class SafeLinkUrlValidator implements ConstraintValidator<SafeLinkUrl, String> {

    /** 숫자 게시판 경로 거부 문구 — 공개 게시판 주소는 슬러그만 쓴다(PLAN-board-definition.md PR 4a D5). */
    static final String NUMERIC_BOARD_PATH_MESSAGE = "게시판 주소는 슬러그 주소(예: /main/boards/board-12)를 쓰세요.";

    /** 숫자 공지 경로 거부 문구 — 공지 상세는 공지 게시판의 게시글 주소로 열린다(PLAN-board-definition.md PR 4b D6). */
    static final String NUMERIC_NOTICE_PATH_MESSAGE = "공지 주소는 게시판 주소(예: /main/boards/board-1/posts/5)를 쓰세요.";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!SafeUrls.isSafeLinkUrl(value)) {
            return false;
        }
        if (BoardLinkPaths.isNumericBoardPath(value)) {
            return reject(context, NUMERIC_BOARD_PATH_MESSAGE);
        }
        if (BoardLinkPaths.isNumericNoticePath(value)) {
            return reject(context, NUMERIC_NOTICE_PATH_MESSAGE);
        }
        return true;
    }

    private static boolean reject(ConstraintValidatorContext context, String message) {
        if (context != null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
        return false;
    }
}
