package com.cms.admin.message.dto.response;

import com.cms.admin.message.domain.AdminMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 쪽지 발송 결과. 감사 Aspect가 {@code targetIdExpression = "recipientId"}로 {@link #getRecipientId()}를 리플렉션 호출한다 —
 * 실제 {@code Long getRecipientId()}여야 한다(record 접근자·중첩 getter는 Aspect 추출 규칙에 맞지 않는다, PLAN §5-G).
 * 본문은 담지 않는다.
 */
@Getter
@Schema(description = "쪽지 발송 결과")
public class MessageSendResponse {

    private final Long id;
    private final Long recipientId;
    private final String title;
    private final LocalDateTime createDate;

    private MessageSendResponse(Long id, Long recipientId, String title, LocalDateTime createDate) {
        this.id = id;
        this.recipientId = recipientId;
        this.title = title;
        this.createDate = createDate;
    }

    public static MessageSendResponse from(AdminMessage message) {
        return new MessageSendResponse(message.getId(), message.getRecipientId(), message.getTitle(), message.getCreateDate());
    }
}
