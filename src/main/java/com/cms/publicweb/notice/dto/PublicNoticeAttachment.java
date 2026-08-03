package com.cms.publicweb.notice.dto;

import com.cms.admin.notice.domain.NoticeAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 공개 첨부 메타데이터. admin {@code NoticeAttachmentResponse}를 재사용하지 않는다 —
 * {@code storageKey}(서버 내부 경로)·{@code contentType}(응답은 항상 octet-stream 강제라 무의미)·
 * {@code noticeId}(URL에 이미 있음)를 필드 자체에서 제외해 실수로 새어나갈 수 없게 한다
 * (PLAN-public-notice-attachment.md 결정 3).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PublicNoticeAttachment {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;

    private Long id;
    private String originalFilename;
    private Long fileSize;
    private String fileSizeText;

    public static PublicNoticeAttachment from(NoticeAttachment attachment) {
        return PublicNoticeAttachment.builder()
                .id(attachment.getId())
                .originalFilename(attachment.getOriginalFilename())
                .fileSize(attachment.getFileSize())
                .fileSizeText(formatSize(attachment.getFileSize()))
                .build();
    }

    /**
     * 1024 기반, 1024B 미만은 정수 "{n} B", 그 이상은 소수점 1자리 고정("1.0 KB"도 유지).
     * 반올림은 HALF_UP, Locale.ROOT로 고정(서버 기본 Locale에 영향받지 않도록).
     */
    private static String formatSize(long bytes) {
        if (bytes < KB) {
            return bytes + " B";
        }
        if (bytes < MB) {
            return formatUnit(bytes, KB, "KB");
        }
        return formatUnit(bytes, MB, "MB");
    }

    private static String formatUnit(long bytes, long unit, String label) {
        BigDecimal value = BigDecimal.valueOf(bytes)
                .divide(BigDecimal.valueOf(unit), 1, RoundingMode.HALF_UP);
        return String.format(Locale.ROOT, "%.1f %s", value, label);
    }
}
