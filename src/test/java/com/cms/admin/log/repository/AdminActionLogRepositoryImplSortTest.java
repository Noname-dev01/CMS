package com.cms.admin.log.repository;

import com.cms.admin.log.domain.QAdminActionLog;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AdminActionLogRepositoryImpl의 정렬 변환 로직을 검증하는 단위 테스트.
 * DB 없이 toOrderSpecifiers / buildOrderSpecifier 메서드의 동작만 검증한다.
 */
class AdminActionLogRepositoryImplSortTest {

    private AdminActionLogRepositoryImpl repository;
    private final QAdminActionLog log = QAdminActionLog.adminActionLog;

    @BeforeEach
    void setUp() {
        repository = new AdminActionLogRepositoryImpl(mock(JPAQueryFactory.class));
    }

    @Test
    @DisplayName("정렬 미지정 시 createAt desc, id desc 기본 정렬")
    void 유효_정렬_없으면_createAt_desc_id_desc_기본정렬() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(Sort.unsorted());

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(log.createAt);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[1].getTarget()).isEqualTo(log.id);
        assertThat(specifiers[1].getOrder()).isEqualTo(Order.DESC);
    }

    @Test
    @DisplayName("화이트리스트에 없는 필드는 무시되고 기본 정렬 반환")
    void 화이트리스트_없는_필드는_무시된다() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "unknownField"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(log.createAt);
        assertThat(specifiers[1].getTarget()).isEqualTo(log.id);
    }

    @Test
    @DisplayName("createAt 정렬 뒤에 id desc tie-breaker가 붙는다")
    void createAt_정렬_뒤에_id_desc_tiebreaker가_붙는다() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.DESC, "createAt"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(log.createAt);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[1].getTarget()).isEqualTo(log.id);
        assertThat(specifiers[1].getOrder()).isEqualTo(Order.DESC);
    }

    @Test
    @DisplayName("actionResult enum은 화이트리스트에 없어 정렬에서 무시된다")
    void actionResult_enum은_정렬에서_무시된다() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "actionResult"));

        // 화이트리스트에 없으므로 기본 정렬
        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(log.createAt);
        assertThat(specifiers[1].getTarget()).isEqualTo(log.id);
    }

    @Test
    @DisplayName("허용 필드(actionType) 정렬 시 tie-breaker id desc가 뒤에 붙는다")
    void 허용필드_정렬_후_tiebreaker_추가() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "actionType"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(log.actionType);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.ASC);
        assertThat(specifiers[1].getTarget()).isEqualTo(log.id);
        assertThat(specifiers[1].getOrder()).isEqualTo(Order.DESC);
    }

    @Test
    @DisplayName("buildOrderSpecifier — 각 허용 필드에 대해 올바른 경로 반환")
    void buildOrderSpecifier_허용_필드() {
        assertThat(repository.buildOrderSpecifier(log, "id",           true).getTarget()).isEqualTo(log.id);
        assertThat(repository.buildOrderSpecifier(log, "actionType",   true).getTarget()).isEqualTo(log.actionType);
        assertThat(repository.buildOrderSpecifier(log, "actionUserId", false).getTarget()).isEqualTo(log.actionUserId);
        assertThat(repository.buildOrderSpecifier(log, "createAt",     false).getTarget()).isEqualTo(log.createAt);
    }

    @Test
    @DisplayName("buildOrderSpecifier — 미지원 필드는 null 반환")
    void buildOrderSpecifier_미지원_필드는_null() {
        assertThat(repository.buildOrderSpecifier(log, "actionResult", true)).isNull();
        assertThat(repository.buildOrderSpecifier(log, "unknown",      true)).isNull();
    }
}
