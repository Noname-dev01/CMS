package com.cms.admin.board.repository;

import com.cms.admin.board.domain.Post;
import com.cms.admin.board.domain.QBoard;
import com.cms.admin.board.domain.QPost;
import com.cms.admin.board.dto.request.PostSearchRequest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /** 정렬 허용 필드 화이트리스트 — 목록 외 속성은 무시해 임의 필드 정렬을 차단한다. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "title", "useYn", "createDate", "updateDate", "viewCount");

    @Override
    public Page<Post> searchPosts(Long boardId, PostSearchRequest request, Pageable pageable) {
        QPost post = QPost.post;

        // 게시판 소속과 삭제 제외는 항상 붙는다 — 경로의 게시판과 다른 게시판의 글이 섞이지 않는다
        BooleanBuilder builder = new BooleanBuilder()
                .and(post.boardId.eq(boardId))
                .and(post.deleted.isFalse());
        if (hasText(request.getKeyword())) {
            builder.and(post.title.contains(request.getKeyword().trim()));
        }
        if (request.getUseYn() != null) {
            builder.and(post.useYn.eq(request.getUseYn()));
        }
        return page(builder, pageable);
    }

    @Override
    public Page<Post> searchPublished(Long boardId, String keyword, LocalDateTime now, Pageable pageable) {
        QPost post = QPost.post;

        BooleanBuilder condition = new BooleanBuilder()
                .and(post.boardId.eq(boardId))
                .and(post.deleted.isFalse())
                .and(isPublished(post, now));
        if (keyword != null) {
            condition.and(post.title.contains(keyword));
        }
        return page(condition, pageable);
    }

    @Override
    public Page<PostSearchRow> searchForAdminSearch(Collection<Long> boardIds, String keyword, Pageable pageable) {
        QPost post = QPost.post;
        QBoard board = QBoard.board;

        // 게시글에 연관관계 매핑이 없어 엔티티 조인(on)으로 게시판 이름을 함께 읽는다. 목록과 COUNT가 같은 조건 객체를 쓴다.
        BooleanBuilder condition = new BooleanBuilder()
                .and(post.deleted.isFalse())
                .and(board.deleted.isFalse())
                .and(post.title.contains(keyword));
        if (boardIds != null) {
            condition.and(post.boardId.in(boardIds));
        }

        List<PostSearchRow> content = queryFactory
                .select(Projections.constructor(PostSearchRow.class,
                        post.id, post.boardId, board.name, post.title, post.useYn, post.createDate))
                .from(post)
                .join(board).on(board.id.eq(post.boardId))
                .where(condition)
                .orderBy(post.createDate.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(post.count())
                .from(post)
                .join(board).on(board.id.eq(post.boardId))
                .where(condition)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public List<PublishedPostRow> findLatestPublished(Long boardId, Long siteId, boolean excludeSystemBoards, LocalDateTime now, int limit) {
        QPost post = QPost.post;
        QBoard board = QBoard.board;

        // 공통이거나 현재 사이트 전용인 게시판만(PLAN-site-menu.md PR ④ T2) — 공개 조건과 함께 이 메서드 안에 고정돼 있다
        BooleanBuilder condition = new BooleanBuilder()
                .and(board.publicYn.isTrue())
                .and(board.deleted.isFalse())
                .and(board.siteId.isNull().or(board.siteId.eq(siteId)))
                .and(post.deleted.isFalse())
                .and(isPublished(post, now));
        if (boardId != null) {
            condition.and(post.boardId.eq(boardId));
        }
        if (excludeSystemBoards) {
            // 시스템 게시판(공통·사이트 전용 공지)은 새 글에서 뺀다 — 같은 글이 공지 섹션과 새 글 섹션에 함께 나오지 않게
            condition.and(board.boardKey.isNull());
        }

        return queryFactory
                .select(Projections.constructor(PublishedPostRow.class,
                        post.id, post.boardId, board.name, post.title, post.createDate))
                .from(post)
                .join(board).on(board.id.eq(post.boardId))
                .where(condition)
                .orderBy(post.createDate.desc(), post.id.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 게시글 자체의 공개 조건 — 노출 여부 ∧ 노출 기간 {@code [publishStart, publishEnd)}(시작 포함·종료 미포함, 한쪽이 null이면 그쪽은 열림).
     * 목록·COUNT·메인이 이 한 조건을 공유한다. {@code PostRepository.findPublishedById}(JPQL)와 {@code ContentImageRefRepository.existsPublishedPostRef}
     * (JPQL)가 같은 의미를 따로 표현하므로 한 곳을 바꾸면 모두 바꾼다 — {@code PostRepositoryDataJpaTest}가 경계값으로 대조한다.
     */
    private BooleanExpression isPublished(QPost post, LocalDateTime now) {
        return post.useYn.isTrue()
                .and(post.publishStart.isNull().or(post.publishStart.loe(now)))
                .and(post.publishEnd.isNull().or(post.publishEnd.gt(now)));
    }

    private Page<Post> page(BooleanBuilder condition, Pageable pageable) {
        QPost post = QPost.post;

        List<Post> content = queryFactory
                .selectFrom(post)
                .where(condition)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(post.count())
                .from(post)
                .where(condition)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /**
     * Pageable의 Sort를 QueryDSL OrderSpecifier 배열로 변환한다. 화이트리스트에 없는 속성은 무시하며, 마지막에 항상 id 보조 정렬을 추가한다 —
     * title·날짜만으로 정렬하면 동률 행 사이 순서가 매 쿼리마다 달라져 페이지 이동 시 항목이 누락·중복될 수 있다.
     */
    OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        QPost p = QPost.post;
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();
        boolean idRequested = sort.getOrderFor("id") != null;

        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                continue;
            }
            OrderSpecifier<?> specifier = buildOrderSpecifier(p, order.getProperty(), order.isAscending());
            if (specifier != null) {
                specifiers.add(specifier);
            }
        }
        if (!idRequested) {
            specifiers.add(p.id.desc());
        }
        return specifiers.toArray(new OrderSpecifier[0]);
    }

    private OrderSpecifier<?> buildOrderSpecifier(QPost p, String property, boolean asc) {
        return switch (property) {
            case "id"         -> asc ? p.id.asc()         : p.id.desc();
            case "title"      -> asc ? p.title.asc()      : p.title.desc();
            case "useYn"      -> asc ? p.useYn.asc()      : p.useYn.desc();
            case "createDate" -> asc ? p.createDate.asc() : p.createDate.desc();
            case "updateDate" -> asc ? p.updateDate.asc() : p.updateDate.desc();
            case "viewCount"  -> asc ? p.viewCount.asc()  : p.viewCount.desc();
            default           -> null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
