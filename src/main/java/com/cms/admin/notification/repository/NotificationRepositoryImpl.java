package com.cms.admin.notification.repository;

import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.domain.QNotification;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Notification> findPage(Long memberId, Long beforeId, boolean includeAdminOnly, int limit) {
        QNotification notification = QNotification.notification;

        BooleanBuilder builder = visibleTo(notification, memberId, includeAdminOnly);
        if (beforeId != null) {
            builder.and(notification.id.lt(beforeId));
        }

        return queryFactory
                .selectFrom(notification)
                .where(builder)
                .orderBy(notification.id.desc())
                .limit(limit)
                .fetch();
    }

    @Override
    public long countUnread(Long memberId, boolean includeAdminOnly) {
        QNotification notification = QNotification.notification;

        BooleanBuilder builder = visibleTo(notification, memberId, includeAdminOnly);
        builder.and(notification.readAt.isNull());

        Long count = queryFactory
                .select(notification.count())
                .from(notification)
                .where(builder)
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 본인 것만, 그리고 비ADMIN이면 ADMIN 전용 종류를 뺀다 — 모든 조회·집계가 같은 조건을 쓴다. */
    private BooleanBuilder visibleTo(QNotification notification, Long memberId, boolean includeAdminOnly) {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(notification.memberId.eq(memberId));
        if (!includeAdminOnly) {
            builder.and(notification.type.ne(NotificationType.ADMIN_ACCOUNT_LOCKED));
        }
        return builder;
    }
}
