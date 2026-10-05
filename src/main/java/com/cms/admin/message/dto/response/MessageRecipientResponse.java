package com.cms.admin.message.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.List;

/**
 * 수신자 검색 결과. 노출 필드는 {@code id}·{@code userId}·{@code userName}뿐이다 — 이메일·역할·상태·프로필은 담지 않는다
 * (MANAGER에게 열리는 관리자 명단의 최소 노출, PLAN-admin-message.md D4·§5-E).
 */
@Getter
@Schema(description = "쪽지 수신자 검색 결과")
public class MessageRecipientResponse {

    private final List<Item> content;

    @Schema(description = "결과가 더 있어 일부만 돌려줬는가(검색어를 좁혀야 한다)")
    private final boolean truncated;

    public MessageRecipientResponse(List<Item> content, boolean truncated) {
        this.content = List.copyOf(content);
        this.truncated = truncated;
    }

    public record Item(Long id, String userId, String userName) {
    }
}
