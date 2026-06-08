package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.QMember;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /** 정렬 허용 필드 화이트리스트 — 목록 외 속성은 무시해 임의 필드 정렬을 차단한다. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "userId", "userName", "email", "userType", "status", "createDate", "updateDate"
    );

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
                .orderBy(toOrderSpecifiers(pageable.getSort()))
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

    /**
     * Pageable의 Sort를 QueryDSL OrderSpecifier 배열로 변환한다.
     * 화이트리스트에 없는 속성과 열거형 필드(userType, status)는 무시하며,
     * 유효한 정렬이 없으면 기본값 id 내림차순을 반환한다.
     */
    OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        QMember m = QMember.member;
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();

        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                continue;
            }
            OrderSpecifier<?> specifier = buildOrderSpecifier(m, order.getProperty(), order.isAscending());
            if (specifier != null) {
                specifiers.add(specifier);
            }
        }

        if (specifiers.isEmpty()) {
            return new OrderSpecifier[]{m.id.desc()};
        }
        return specifiers.toArray(new OrderSpecifier[0]);
    }

    /**
     * 필드명과 방향으로 OrderSpecifier를 생성한다.
     * 열거형(userType, status)은 QueryDSL EnumPath이 ComparableExpressionBase를
     * 상속하지 않아 asc()/desc()를 지원하지 않으므로 null을 반환한다.
     */
    OrderSpecifier<?> buildOrderSpecifier(QMember m, String property, boolean asc) {
        return switch (property) {
            case "id"         -> asc ? m.id.asc()         : m.id.desc();
            case "userId"     -> asc ? m.userId.asc()     : m.userId.desc();
            case "userName"   -> asc ? m.userName.asc()   : m.userName.desc();
            case "email"      -> asc ? m.email.asc()      : m.email.desc();
            case "createDate" -> asc ? m.createDate.asc() : m.createDate.desc();
            case "updateDate" -> asc ? m.updateDate.asc() : m.updateDate.desc();
            // 열거형(userType, status)은 정렬 미지원 — 화이트리스트에는 포함되나 무시됨
            default           -> null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
