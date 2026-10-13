package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.PostAttachment;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PostAttachmentResponse {

    private final Long id;
    private final String originalFilename;
    private final String contentType;
    private final Long fileSize;
    private final LocalDateTime createDate;
    /** 공개 다운로드 시작 누적 횟수(읽기 전용). */
    private final long downloadCount;

    public static PostAttachmentResponse from(PostAttachment attachment) {
        return PostAttachmentResponse.builder()
                .id(attachment.getId())
                .originalFilename(attachment.getOriginalFilename())
                .contentType(attachment.getContentType())
                .fileSize(attachment.getFileSize())
                .createDate(attachment.getCreateDate())
                .downloadCount(attachment.getDownloadCount())
                .build();
    }
}
