package com.cms.admin.board.repository;

import com.cms.admin.board.domain.QBoard;
import com.cms.admin.board.domain.QPost;
import com.cms.admin.board.domain.QPostAttachment;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 대시보드 "콘텐츠 현황" 집계(PLAN-view-download-count.md 쟁점 6). 삭제된 게시글·삭제된 게시판은 제외하고 비노출 글은 포함한다(관리자 화면).
 * {@code boardIds}가 null이면 모든 게시판(ADMIN), 아니면 그 게시판들로 한정한다(MANAGER의 READ 게시판 집합) — 빈 컬렉션은 호출자가 거르고 부르지 않는다.
 * 상위 N은 인덱스 없이 정렬한다(규모가 작아 수용, V49 주석).
 */
@Repository
@RequiredArgsConstructor
public class ContentStatsQueryRepository {

    private final JPAQueryFactory queryFactory;

    public long sumViewCount(Collection<Long> boardIds) {
        QPost post = QPost.post;
        QBoard board = QBoard.board;
        Long sum = queryFactory.select(post.viewCount.sum())
                .from(post)
                .join(board).on(board.id.eq(post.boardId))
                .where(postCondition(post, board, boardIds))
                .fetchOne();
        return sum == null ? 0L : sum;
    }

    public long sumDownloadCount(Collection<Long> boardIds) {
        QPostAttachment attachment = QPostAttachment.postAttachment;
        QPost post = QPost.post;
        QBoard board = QBoard.board;
        Long sum = queryFactory.select(attachment.downloadCount.sum())
                .from(attachment)
                .join(post).on(post.id.eq(attachment.postId))
                .join(board).on(board.id.eq(post.boardId))
                .where(postCondition(post, board, boardIds))
                .fetchOne();
        return sum == null ? 0L : sum;
    }

    /** 조회수가 0보다 큰 게시글만, 조회수 내림차순 + id 내림차순. */
    public List<TopPostRow> findTopViewedPosts(Collection<Long> boardIds, int limit) {
        QPost post = QPost.post;
        QBoard board = QBoard.board;
        return queryFactory
                .select(Projections.constructor(TopPostRow.class, post.id, post.boardId, board.name, post.title, post.viewCount))
                .from(post)
                .join(board).on(board.id.eq(post.boardId))
                .where(postCondition(post, board, boardIds).and(post.viewCount.gt(0L)))
                .orderBy(post.viewCount.desc(), post.id.desc())
                .limit(limit)
                .fetch();
    }

    /** 다운로드가 0보다 큰 첨부만, 다운로드 수 내림차순 + id 내림차순. */
    public List<TopAttachmentRow> findTopDownloadedAttachments(Collection<Long> boardIds, int limit) {
        QPostAttachment attachment = QPostAttachment.postAttachment;
        QPost post = QPost.post;
        QBoard board = QBoard.board;
        return queryFactory
                .select(Projections.constructor(TopAttachmentRow.class, attachment.id, post.id, post.boardId, board.name,
                        post.title, attachment.originalFilename, attachment.downloadCount))
                .from(attachment)
                .join(post).on(post.id.eq(attachment.postId))
                .join(board).on(board.id.eq(post.boardId))
                .where(postCondition(post, board, boardIds).and(attachment.downloadCount.gt(0L)))
                .orderBy(attachment.downloadCount.desc(), attachment.id.desc())
                .limit(limit)
                .fetch();
    }

    private BooleanBuilder postCondition(QPost post, QBoard board, Collection<Long> boardIds) {
        BooleanBuilder condition = new BooleanBuilder()
                .and(post.deleted.isFalse())
                .and(board.deleted.isFalse());
        if (boardIds != null) {
            condition.and(post.boardId.in(boardIds));
        }
        return condition;
    }

    public record TopPostRow(Long id, Long boardId, String boardName, String title, long viewCount) {
    }

    public record TopAttachmentRow(Long attachmentId, Long postId, Long boardId, String boardName, String postTitle,
                                   String filename, long downloadCount) {
    }
}
