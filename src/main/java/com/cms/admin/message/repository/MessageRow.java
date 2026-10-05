package com.cms.admin.message.repository;

import java.time.LocalDateTime;

/**
 * 목록·단건 조회가 읽어 오는 한 행 — 쪽지와 <b>상대 회원의 현재 값</b>(id·아이디·이름)을 함께 담는다.
 * 상대 회원이 {@code DELETED}여도 쪽지는 보이고 이름은 현재 값이며, 이메일·역할·상태는 읽지도 노출하지도 않는다.
 * 목록 조회는 본문을 읽지 않아 {@code body}가 null이다(본문은 단건 조회에서만 나간다).
 */
public record MessageRow(
        Long id,
        Long senderId,
        Long recipientId,
        String title,
        String body,
        LocalDateTime readAt,
        LocalDateTime createDate,
        Long counterpartId,
        String counterpartUserId,
        String counterpartUserName
) {
}
