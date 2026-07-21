package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.QNotice;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
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
public class NoticeRepositoryImpl implements NoticeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /** 정렬 허용 필드 화이트리스트 — 목록 외 속성은 무시해 임의 필드 정렬을 차단한다. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "title", "useYn", "createDate", "updateDate"
    );

    @Override
    public Page<Notice> searchNotices(NoticeSearchRequest request, Pageable pageable) {

        QNotice notice = QNotice.notice;

        BooleanBuilder builder = new BooleanBuilder();

        // 삭제된 공지는 항상 제외
        builder.and(notice.deleted.isFalse());

        if (hasText(request.getKeyword())) {
            builder.and(notice.title.contains(request.getKeyword().trim()));
        }

        if (request.getUseYn() != null) {
            builder.and(notice.useYn.eq(request.getUseYn()));
        }

        List<Notice> content = queryFactory
                .selectFrom(notice)
                .where(builder)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(notice.count())
                .from(notice)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /**
     * Pageable의 Sort를 QueryDSL OrderSpecifier 배열로 변환한다.
     * 화이트리스트에 없는 속성은 무시하며, 유효한 정렬이 있든 없든 마지막에
     * 항상 id 보조 정렬을 추가한다 — title·날짜만으로 정렬하면 동률 행 사이
     * 순서가 매 쿼리마다 달라져 페이지 이동 시 항목이 누락·중복될 수 있다.
     */
    OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        QNotice n = QNotice.notice;
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();
        boolean idRequested = sort.getOrderFor("id") != null;

        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                continue;
            }
            OrderSpecifier<?> specifier = buildOrderSpecifier(n, order.getProperty(), order.isAscending());
            if (specifier != null) {
                specifiers.add(specifier);
            }
        }

        // 요청 정렬에 id가 없으면 마지막에 보조 정렬로 추가(동률 tie-breaker).
        // 유효한 정렬이 하나도 없었다면 이 한 줄이 기본값(id desc) 역할도 겸한다.
        if (!idRequested) {
            specifiers.add(n.id.desc());
        }

        return specifiers.toArray(new OrderSpecifier[0]);
    }

    OrderSpecifier<?> buildOrderSpecifier(QNotice n, String property, boolean asc) {
        return switch (property) {
            case "id"         -> asc ? n.id.asc()         : n.id.desc();
            case "title"      -> asc ? n.title.asc()      : n.title.desc();
            case "useYn"      -> asc ? n.useYn.asc()      : n.useYn.desc();
            case "createDate" -> asc ? n.createDate.asc() : n.createDate.desc();
            case "updateDate" -> asc ? n.updateDate.asc() : n.updateDate.desc();
            default           -> null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
