package com.cms.admin.message.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

/** 쪽지 단건(제목·본문 전체). 조회는 읽음을 일으키지 않는다 — 화면이 별도로 읽음 PATCH를 보낸다. */
@Getter
@Schema(description = "쪽지 단건")
public class MessageDetailResponse {

    /** 내 기준 방향 — 내가 받은 쪽지({@code RECEIVED})인지 보낸 쪽지({@code SENT})인지. */
    public enum Direction { RECEIVED, SENT }

    private final Long id;
    private final Direction direction;
    private final MessageListResponse.Counterpart counterpart;
    private final String title;
    private final String body;
    private final boolean read;
    private final LocalDateTime readAt;
    private final LocalDateTime createDate;

    public MessageDetailResponse(Long id, Direction direction, MessageListResponse.Counterpart counterpart, String title,
                                 String body, LocalDateTime readAt, LocalDateTime createDate) {
        this.id = id;
        this.direction = direction;
        this.counterpart = counterpart;
        this.title = title;
        this.body = body;
        this.read = readAt != null;
        this.readAt = readAt;
        this.createDate = createDate;
    }
}
