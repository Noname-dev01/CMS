package com.cms.admin.message.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/** 읽음 처리 결과 — 서버가 센 현재 미읽음 수를 함께 돌려준다(배지는 항상 서버 값으로 확정한다). */
@Getter
@Schema(description = "쪽지 읽음 처리 결과")
public class MessageReadResponse {

    private final Long id;
    private final long unreadCount;

    public MessageReadResponse(Long id, long unreadCount) {
        this.id = id;
        this.unreadCount = unreadCount;
    }
}
