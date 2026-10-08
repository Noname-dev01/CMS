package com.cms.common.attachment;

import com.cms.common.exception.InvalidRequestException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 첨부파일 검증 정책(파일명·확장자·Content-Type 화이트리스트·크기·개수 상한) — 공지 첨부와 게시글 첨부가 같은 규칙을 쓴다.
 * 보안 화이트리스트가 두 벌이 되면 한쪽만 고쳐지는 표류가 생기므로 한 곳에 둔다(PLAN-board.md 쟁점 7).
 * 공지 첨부({@code NoticeAttachmentService})에서 동작 변경 없이 옮겼다.
 */
public final class AttachmentFilePolicy {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final int MAX_COUNT_PER_OWNER = 5;
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private static final int MAX_FILENAME_LENGTH = 255;

    /**
     * 확장자 → 허용 Content-Type 집합. {@link #DEFAULT_CONTENT_TYPE}은 모든 확장자에 공통으로 추가 허용된다
     * (비표준 클라이언트의 제네릭 선언 대응, PLAN-notice-attachment.md 쟁점 5).
     */
    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES_BY_EXTENSION = buildAllowedContentTypes();

    private AttachmentFilePolicy() {
    }

    /** 경로 구분자를 떼어 파일명만 남기고 비어 있거나 255자를 넘으면 거부한다. */
    public static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        String normalized = originalFilename.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        String base = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (base.isBlank()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        if (base.length() > MAX_FILENAME_LENGTH) {
            throw new InvalidRequestException("파일명은 255자 이하만 허용됩니다.");
        }
        return base;
    }

    /** 허용 확장자만 통과시키고 소문자 확장자를 돌려준다. */
    public static String requireAllowedExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        String extension = dotIndex >= 0 && dotIndex < filename.length() - 1
                ? filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!ALLOWED_CONTENT_TYPES_BY_EXTENSION.containsKey(extension)) {
            throw new InvalidRequestException("허용되지 않는 파일 형식입니다: ." + extension);
        }
        return extension;
    }

    /** 선언된 Content-Type이 확장자와 맞는지 확인한다. null(레거시 클라이언트)이면 확장자 화이트리스트 통과만으로 허용한다. */
    public static void validateContentType(String extension, String contentType) {
        if (contentType == null) {
            return;
        }
        Set<String> allowed = ALLOWED_CONTENT_TYPES_BY_EXTENSION.get(extension);
        if (allowed.contains(contentType) || DEFAULT_CONTENT_TYPE.equals(contentType)) {
            return;
        }
        throw new InvalidRequestException("파일 형식과 확장자가 일치하지 않습니다.");
    }

    /** NOT NULL 컬럼에 넣기 전 선언 Content-Type이 null이면 기본값으로 정규화한다(검증은 원본 null 기준으로 이미 통과). */
    public static String normalizeContentType(String declared) {
        return declared == null ? DEFAULT_CONTENT_TYPE : declared;
    }

    private static Map<String, Set<String>> buildAllowedContentTypes() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("pdf", Set.of("application/pdf"));
        map.put("doc", Set.of("application/msword"));
        map.put("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        map.put("xls", Set.of("application/vnd.ms-excel"));
        map.put("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        map.put("ppt", Set.of("application/vnd.ms-powerpoint"));
        map.put("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        map.put("hwp", Set.of("application/x-hwp", "application/haansofthwp"));
        map.put("txt", Set.of("text/plain"));
        map.put("csv", Set.of("text/csv", "application/vnd.ms-excel"));
        map.put("zip", Set.of("application/zip", "application/x-zip-compressed"));
        map.put("png", Set.of("image/png"));
        map.put("jpg", Set.of("image/jpeg"));
        map.put("jpeg", Set.of("image/jpeg"));
        map.put("gif", Set.of("image/gif"));
        return Map.copyOf(map);
    }
}
