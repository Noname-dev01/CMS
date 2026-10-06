package com.cms.admin.message.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 쪽지함 목록(커서 방식). 다음 페이지는 마지막 항목의 {@code id}를 {@code beforeId}로 보낸다.
 * 목록은 본문을 담지 않는다 — 본문은 단건 조회에서만 나간다. 보낸 쪽지함의 {@code read}·{@code readAt}이 읽음 확인(D10)이다.
 */
@Getter
@Schema(description = "쪽지함 목록")
public class MessageListResponse {

    private final List<Item> content;
    private final boolean hasMore;

    public MessageListResponse(List<Item> content, boolean hasMore) {
        this.content = List.copyOf(content);
        this.hasMore = hasMore;
    }

    /** 목록 한 건. {@code counterpart}는 받은 쪽지함이면 보낸 사람, 보낸 쪽지함이면 받는 사람이다. */
    public record Item(Long id, Counterpart counterpart, String title, boolean read, LocalDateTime readAt, LocalDateTime createDate) {
    }

    /** 상대 회원 — id·아이디·이름만(이메일·역할·상태 비노출). */
    public record Counterpart(Long id, String userId, String userName) {
    }
}
