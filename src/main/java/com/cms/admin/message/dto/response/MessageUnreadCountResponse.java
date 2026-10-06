package com.cms.admin.message.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "내 미읽음 쪽지 수")
public class MessageUnreadCountResponse {

    private final long unreadCount;

    public MessageUnreadCountResponse(long unreadCount) {
        this.unreadCount = unreadCount;
    }
}
