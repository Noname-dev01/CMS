package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.QMember;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Member> searchAdminMembers(AdminMemberSearchRequest request, Pageable pageable) {

        QMember member = QMember.member;

        BooleanBuilder builder = new BooleanBuilder();

        // 관리자만 조회
        builder.and(member.userType.in(Role.ROLE_ADMIN, Role.ROLE_MANAGER));

        // 삭제 제외
        builder.and(member.status.ne(MemberStatus.DELETED));

        if (hasText(request.getUserId())) {
            builder.and(member.userId.contains(request.getUserId().trim()));
        }

        if (hasText(request.getUserName())) {
            builder.and(member.userName.contains(request.getUserName().trim()));
        }

        if (request.getUserType() != null) {
            builder.and(member.userType.eq(request.getUserType()));
        }

        if (request.getStatus() != null) {
            builder.and(member.status.eq(request.getStatus()));
        }

        List<Member> content = queryFactory
                .selectFrom(member)
                .where(builder)
                .orderBy(member.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(member.count())
                .from(member)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
