package com.cms.admin.message.repository;

import com.cms.admin.message.domain.MessageBox;

import java.util.List;
import java.util.Optional;

public interface AdminMessageRepositoryCustom {

    /**
     * 본인 쪽 쪽지함의 한 페이지(id 내림차순 커서) — 본인 쪽에서 삭제한 행은 제외한다. 본문은 읽지 않는다.
     * {@code limit}건까지 읽는다(호출자가 {@code size + 1}을 요청해 {@code hasMore}를 판정한다).
     */
    List<MessageRow> findPage(Long memberId, MessageBox box, Long beforeId, int limit);

    /**
     * 단건(본문 포함). 소유 조건은 {@code (sender_id = :me AND sender_deleted_at IS NULL) OR (recipient_id = :me AND recipient_deleted_at IS NULL)}다.
     * 없거나 남의 것이거나 본인 쪽에서 이미 지운 쪽지는 비어 있다(호출자가 404로 숨긴다).
     */
    Optional<MessageRow> findVisible(Long memberId, Long id);

    /** 받은 쪽지 중 본인 쪽에서 지우지 않은 미읽음 수 — 배지용. */
    long countUnread(Long memberId);
}
