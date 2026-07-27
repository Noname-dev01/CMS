package com.cms.admin.notice.dto.response;

import com.cms.admin.notice.domain.NoticeAttachment;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NoticeAttachmentResponse {

    private final Long id;
    private final String originalFilename;
    private final String contentType;
    private final Long fileSize;
    private final LocalDateTime createDate;

    public static NoticeAttachmentResponse from(NoticeAttachment attachment) {
        return NoticeAttachmentResponse.builder()
                .id(attachment.getId())
                .originalFilename(attachment.getOriginalFilename())
                .contentType(attachment.getContentType())
                .fileSize(attachment.getFileSize())
                .createDate(attachment.getCreateDate())
                .build();
    }
}
