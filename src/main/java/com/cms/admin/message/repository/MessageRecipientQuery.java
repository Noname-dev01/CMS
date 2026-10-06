package com.cms.admin.message.repository;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.QMember;
import com.cms.admin.member.domain.Role;
import com.cms.admin.message.dto.response.MessageRecipientResponse;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 쪽지 수신자 후보 조회(PLAN-admin-message.md §5-E). 수신 가능 집합은 {@code ADMIN·MANAGER}이면서 {@code ACTIVE·LOCKED·PASSWORD_EXPIRED}이고
 * 자기 자신이 아닌 회원이다(D5). 노출 필드는 id·userId·userName뿐이다. QueryDSL {@code contains}는 {@code %}·{@code _}를 리터럴로 이스케이프한다.
 */
@Repository
@RequiredArgsConstructor
public class MessageRecipientQuery {

    private final JPAQueryFactory queryFactory;

    /** 원문 그대로의 {@code userId} 정확 일치(최대 1건 — {@code uk_member_user_id} 유일 제약, 같은 콜레이션 비교). trim·소문자화 없음. */
    public List<MessageRecipientResponse.Item> findExactUserId(Long senderId, String rawUserId) {
        QMember member = QMember.member;
        return fetch(queryFactory
                .select(member.id, member.userId, member.userName)
                .from(member)
                .where(eligible(member, senderId), member.userId.eq(rawUserId))
                .limit(1));
    }

    /** {@code userId}·{@code userName} 부분 일치 — 이름·아이디 순, {@code limit}건까지(잘림 판정을 위해 호출자가 11건을 요청한다). */
    public List<MessageRecipientResponse.Item> findPartial(Long senderId, String trimmedKeyword, int limit) {
        QMember member = QMember.member;
        return fetch(queryFactory
                .select(member.id, member.userId, member.userName)
                .from(member)
                .where(eligible(member, senderId),
                        member.userId.contains(trimmedKeyword).or(member.userName.contains(trimmedKeyword)))
                .orderBy(member.userName.asc(), member.id.asc())
                .limit(limit));
    }

    private static BooleanExpression eligible(QMember member, Long senderId) {
        return member.id.ne(senderId)
                .and(member.userType.in(Role.ROLE_ADMIN, Role.ROLE_MANAGER))
                .and(member.status.in(MemberStatus.ACTIVE, MemberStatus.LOCKED, MemberStatus.PASSWORD_EXPIRED));
    }

    private static List<MessageRecipientResponse.Item> fetch(com.querydsl.jpa.impl.JPAQuery<Tuple> query) {
        QMember member = QMember.member;
        return query.fetch().stream()
                .map(tuple -> new MessageRecipientResponse.Item(tuple.get(member.id), tuple.get(member.userId), tuple.get(member.userName)))
                .toList();
    }
}
