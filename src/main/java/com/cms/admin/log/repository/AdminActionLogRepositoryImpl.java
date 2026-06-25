package com.cms.admin.log.repository;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.QAdminActionLog;
import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
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
public class AdminActionLogRepositoryImpl implements AdminActionLogRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /** 정렬 허용 필드 화이트리스트 — 임의 필드 정렬(경로 주입)을 차단한다. */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "actionType", "actionUserId", "createAt"
    );

    @Override
    public Page<AdminActionLog> searchActionLogs(AdminActionLogSearchRequest req, Pageable pageable) {
        QAdminActionLog log = QAdminActionLog.adminActionLog;

        BooleanBuilder builder = new BooleanBuilder();

        // 수행자 아이디 contains — 빈 문자열 가드 필수(드롭다운 "전체"가 "" 로 바인딩될 수 있음)
        if (hasText(req.getActionUserId())) {
            builder.and(log.actionUserId.contains(req.getActionUserId().trim()));
        }

        // 액션 유형 eq — 빈 문자열 가드 필수(드롭다운 "전체" 옵션 value="")
        if (hasText(req.getActionType())) {
            builder.and(log.actionType.eq(req.getActionType()));
        }

        // 결과 eq — enum은 Spring이 빈 문자열→null로 변환하므로 null 가드로 충분
        if (req.getActionResult() != null) {
            builder.and(log.actionResult.eq(req.getActionResult()));
        }

        // 기간 필터 — Service에서 기본값 주입이 완료된 상태로 들어옴
        if (req.getFrom() != null) {
            builder.and(log.createAt.goe(req.getFrom().atStartOfDay()));
        }
        if (req.getTo() != null) {
            // 종료일 포함: to+1일 자정 미만으로 처리
            builder.and(log.createAt.lt(req.getTo().plusDays(1).atStartOfDay()));
        }

        List<AdminActionLog> content = queryFactory
                .selectFrom(log)
                .where(builder)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(log.count())
                .from(log)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /**
     * Pageable의 Sort를 QueryDSL OrderSpecifier 배열로 변환한다.
     * 화이트리스트에 없는 속성은 무시하며, 항상 마지막에 id desc tie-breaker를 붙인다.
     * 유효한 정렬이 없으면 createAt desc, id desc 기본 정렬을 반환한다.
     */
    OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        QAdminActionLog log = QAdminActionLog.adminActionLog;
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();

        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                continue;
            }
            OrderSpecifier<?> specifier = buildOrderSpecifier(log, order.getProperty(), order.isAscending());
            if (specifier != null) {
                specifiers.add(specifier);
            }
        }

        if (specifiers.isEmpty()) {
            // 기본 정렬: createAt 중복 시 페이징 안정성을 위해 id를 tie-breaker로 사용
            return new OrderSpecifier[]{log.createAt.desc(), log.id.desc()};
        }

        // 항상 id desc tie-breaker 추가
        specifiers.add(log.id.desc());
        return specifiers.toArray(new OrderSpecifier[0]);
    }

    /**
     * 필드명과 방향으로 OrderSpecifier를 생성한다.
     * 열거형(actionResult)은 QueryDSL EnumPath라 asc()/desc() 미지원이므로 null 반환.
     */
    OrderSpecifier<?> buildOrderSpecifier(QAdminActionLog log, String property, boolean asc) {
        return switch (property) {
            case "id"           -> asc ? log.id.asc()           : log.id.desc();
            case "actionType"   -> asc ? log.actionType.asc()   : log.actionType.desc();
            case "actionUserId" -> asc ? log.actionUserId.asc() : log.actionUserId.desc();
            case "createAt"     -> asc ? log.createAt.asc()     : log.createAt.desc();
            default             -> null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
