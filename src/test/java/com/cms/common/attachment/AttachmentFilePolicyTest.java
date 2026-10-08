package com.cms.common.attachment;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 공지 첨부에서 옮긴 검증 규칙이 그대로인지 고정한다(동작 불변 추출 — PLAN-board.md 쟁점 7). */
class AttachmentFilePolicyTest {

    @Test
    @DisplayName("경로 구분자(/, \\)를 떼고 파일명만 남긴다")
    void sanitizeFilename_stripsPath() {
        assertEquals("a.pdf", AttachmentFilePolicy.sanitizeFilename("../../etc/a.pdf"));
        assertEquals("a.pdf", AttachmentFilePolicy.sanitizeFilename("C:\\x\\y\\a.pdf"));
        assertEquals("a.pdf", AttachmentFilePolicy.sanitizeFilename("a.pdf"));
    }

    @Test
    @DisplayName("null·공백·구분자로 끝나는 이름·255자 초과는 거부, 255자는 허용")
    void sanitizeFilename_rejectsInvalid() {
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.sanitizeFilename(null));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.sanitizeFilename("   "));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.sanitizeFilename("dir/"));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.sanitizeFilename("a".repeat(253) + ".pd"));
        assertEquals(255, AttachmentFilePolicy.sanitizeFilename("a".repeat(251) + ".pdf").length());
    }

    @Test
    @DisplayName("확장자는 소문자로 돌려주고 화이트리스트 밖·확장자 없음·점으로 끝나는 이름은 거부")
    void requireAllowedExtension() {
        assertEquals("pdf", AttachmentFilePolicy.requireAllowedExtension("Report.PDF"));
        assertEquals("jpeg", AttachmentFilePolicy.requireAllowedExtension("x.JPEG"));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.requireAllowedExtension("run.exe"));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.requireAllowedExtension("noext"));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.requireAllowedExtension("trailingdot."));
    }

    @Test
    @DisplayName("Content-Type: null·octet-stream·확장자에 맞는 값은 허용, 다른 값은 거부")
    void validateContentType() {
        assertDoesNotThrow(() -> AttachmentFilePolicy.validateContentType("pdf", null));
        assertDoesNotThrow(() -> AttachmentFilePolicy.validateContentType("pdf", "application/octet-stream"));
        assertDoesNotThrow(() -> AttachmentFilePolicy.validateContentType("pdf", "application/pdf"));
        assertDoesNotThrow(() -> AttachmentFilePolicy.validateContentType("csv", "application/vnd.ms-excel"));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.validateContentType("pdf", "image/png"));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.validateContentType("png", "application/pdf"));
    }

    @Test
    @DisplayName("저장용 Content-Type 정규화 — null이면 octet-stream, 아니면 그대로")
    void normalizeContentType() {
        assertEquals("application/octet-stream", AttachmentFilePolicy.normalizeContentType(null));
        assertEquals("application/pdf", AttachmentFilePolicy.normalizeContentType("application/pdf"));
    }

    @Test
    @DisplayName("상한 상수는 공지 첨부와 같다(10MB, 5개)")
    void limits() {
        assertEquals(10L * 1024 * 1024, AttachmentFilePolicy.MAX_FILE_SIZE);
        assertEquals(5, AttachmentFilePolicy.MAX_COUNT_PER_OWNER);
    }
}
