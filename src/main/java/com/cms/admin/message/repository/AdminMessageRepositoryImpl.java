package com.cms.admin.message.repository;

import com.cms.admin.member.domain.QMember;
import com.cms.admin.message.domain.MessageBox;
import com.cms.admin.message.domain.QAdminMessage;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AdminMessageRepositoryImpl implements AdminMessageRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<MessageRow> findPage(Long memberId, MessageBox box, Long beforeId, int limit) {
        QAdminMessage message = QAdminMessage.adminMessage;
        QMember counterpart = new QMember("counterpart");

        BooleanExpression where = box == MessageBox.INBOX
                ? message.recipientId.eq(memberId).and(message.recipientDeletedAt.isNull())
                : message.senderId.eq(memberId).and(message.senderDeletedAt.isNull());
        if (beforeId != null) {
            where = where.and(message.id.lt(beforeId));
        }

        // 상대 회원은 현재 member 행에서 조인한다 — 이름·아이디만 읽는다(이메일·역할·상태 비노출)
        return queryFactory
                .select(message.id, message.senderId, message.recipientId, message.title, message.readAt, message.createDate,
                        counterpart.id, counterpart.userId, counterpart.userName)
                .from(message)
                .join(counterpart).on(counterpart.id.eq(box == MessageBox.INBOX ? message.senderId : message.recipientId))
                .where(where)
                .orderBy(message.id.desc())
                .limit(limit)
                .fetch()
                .stream()
                .map(tuple -> toRow(tuple, message, counterpart, false))
                .toList();
    }

    @Override
    public Optional<MessageRow> findVisible(Long memberId, Long id) {
        QAdminMessage message = QAdminMessage.adminMessage;
        QMember counterpart = new QMember("counterpart");

        // 괄호까지 고정한 소유 조건 — (보낸 쪽 AND 보낸 쪽 미삭제) OR (받은 쪽 AND 받은 쪽 미삭제)
        BooleanExpression visible = message.senderId.eq(memberId).and(message.senderDeletedAt.isNull())
                .or(message.recipientId.eq(memberId).and(message.recipientDeletedAt.isNull()));

        Tuple tuple = queryFactory
                .select(message.id, message.senderId, message.recipientId, message.title, message.body, message.readAt,
                        message.createDate, counterpart.id, counterpart.userId, counterpart.userName)
                .from(message)
                // 상대 = 내가 보낸 쪽지면 받는 사람, 내가 받은 쪽지면 보낸 사람
                .join(counterpart).on(counterpart.id.eq(
                        com.querydsl.core.types.dsl.Expressions.cases()
                                .when(message.senderId.eq(memberId)).then(message.recipientId)
                                .otherwise(message.senderId)))
                .where(message.id.eq(id), visible)
                .fetchOne();
        return Optional.ofNullable(tuple).map(row -> toRow(row, message, counterpart, true));
    }

    @Override
    public long countUnread(Long memberId) {
        QAdminMessage message = QAdminMessage.adminMessage;
        Long count = queryFactory
                .select(message.count())
                .from(message)
                .where(message.recipientId.eq(memberId), message.recipientDeletedAt.isNull(), message.readAt.isNull())
                .fetchOne();
        return count == null ? 0 : count;
    }

    private static MessageRow toRow(Tuple tuple, QAdminMessage message, QMember counterpart, boolean withBody) {
        return new MessageRow(
                tuple.get(message.id),
                tuple.get(message.senderId),
                tuple.get(message.recipientId),
                tuple.get(message.title),
                withBody ? tuple.get(message.body) : null,
                tuple.get(message.readAt),
                tuple.get(message.createDate),
                tuple.get(counterpart.id),
                tuple.get(counterpart.userId),
                tuple.get(counterpart.userName));
    }
}
