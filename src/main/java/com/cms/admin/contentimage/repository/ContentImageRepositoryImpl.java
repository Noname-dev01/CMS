package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.QContentImage;
import com.cms.admin.contentimage.domain.QContentImageRef;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ContentImageRepositoryImpl implements ContentImageRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ContentImage> searchLibrary(Collection<String> scopeTypes, String keyword, boolean unusedOnly, Pageable pageable) {
        QContentImage image = QContentImage.contentImage;
        QContentImageRef ref = QContentImageRef.contentImageRef;

        BooleanBuilder condition = new BooleanBuilder().and(image.scopeType.in(scopeTypes));
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            BooleanBuilder keywordCondition = new BooleanBuilder().or(image.originalName.contains(trimmed));
            if (trimmed.chars().allMatch(Character::isDigit) && trimmed.length() <= 18) {
                keywordCondition.or(image.id.eq(Long.parseLong(trimmed)));
            }
            condition.and(keywordCondition);
        }
        if (unusedOnly) {
            condition.and(JPAExpressions.selectOne().from(ref).where(ref.imageId.eq(image.id)).notExists());
        }

        List<ContentImage> content = queryFactory
                .selectFrom(image)
                .where(condition)
                .orderBy(image.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(image.count()).from(image).where(condition).fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
