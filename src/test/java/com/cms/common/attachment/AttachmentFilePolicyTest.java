package com.cms.common.attachment;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ===================== 게시판 작성 정책 (PLAN-board-definition.md PR 2) =====================

    @Test
    @DisplayName("전역 허용 확장자 목록은 정렬된 불변 목록이고 최대 크기 상수(MB)와 바이트 상수가 같은 값이다")
    void allowedExtensions_sortedAndConstantsAgree() {
        java.util.List<String> all = AttachmentFilePolicy.allowedExtensions();
        assertEquals(all.stream().sorted().toList(), all);
        assertTrue(all.contains("pdf") && all.contains("hwp"));
        assertThrows(UnsupportedOperationException.class, () -> all.add("exe"));
        assertEquals(AttachmentFilePolicy.MAX_FILE_SIZE_MB * 1024L * 1024, AttachmentFilePolicy.MAX_FILE_SIZE);
    }

    @Test
    @DisplayName("확장자 정규화: 공백·대소문자·중복을 정리해 정렬된 집합으로 돌려준다")
    void normalizeExtensions_cleansAndSorts() {
        assertEquals(java.util.List.of("hwp", "pdf"),
                java.util.List.copyOf(AttachmentFilePolicy.normalizeExtensions(java.util.List.of("PDF", " pdf ", "hwp"))));
    }

    @Test
    @DisplayName("확장자 정규화: 전역 전체를 고르면 null(= 전체 허용), 순서와 무관하다")
    void normalizeExtensions_allSelectedIsNull() {
        java.util.List<String> reversed = new java.util.ArrayList<>(AttachmentFilePolicy.allowedExtensions());
        java.util.Collections.reverse(reversed);
        assertNull(AttachmentFilePolicy.normalizeExtensions(reversed));
    }

    @Test
    @DisplayName("확장자 정규화: 빈 집합·null 원소·전역 밖 값은 400이고 메시지에 입력 원문을 싣지 않는다")
    void normalizeExtensions_rejectsInvalid() {
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.normalizeExtensions(java.util.List.of()));
        assertThrows(InvalidRequestException.class, () -> AttachmentFilePolicy.normalizeExtensions(java.util.Arrays.asList("pdf", null)));
        InvalidRequestException outside = assertThrows(InvalidRequestException.class,
                () -> AttachmentFilePolicy.normalizeExtensions(java.util.List.of("pdf", "exe-secret-token")));
        assertFalse(outside.getMessage().contains("exe-secret-token"));
    }

    @Test
    @DisplayName("게시판 확장자 검사: 전역 밖(exe)은 게시판 집합에 들어 있어도 전역 메시지로 400 — 전역이 천장이다")
    void requireAllowedExtension_globalIsCeiling() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> AttachmentFilePolicy.requireAllowedExtension("run.exe", java.util.Set.of("exe", "pdf")));
        assertTrue(e.getMessage().startsWith("허용되지 않는 파일 형식입니다"));
    }

    @Test
    @DisplayName("게시판 확장자 검사: 전역 안이지만 게시판 집합 밖이면 게시판 메시지로 400, 집합 안·null 집합은 통과")
    void requireAllowedExtension_boardRestricts() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> AttachmentFilePolicy.requireAllowedExtension("a.png", java.util.Set.of("pdf")));
        assertTrue(e.getMessage().startsWith("이 게시판에서 허용하지 않는 파일 형식입니다"));
        assertEquals("pdf", AttachmentFilePolicy.requireAllowedExtension("A.PDF", java.util.Set.of("pdf")));
        assertEquals("png", AttachmentFilePolicy.requireAllowedExtension("a.png", (java.util.Set<String>) null));
    }
}
