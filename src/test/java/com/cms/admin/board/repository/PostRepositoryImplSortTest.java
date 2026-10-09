package com.cms.admin.board.repository;

import com.cms.admin.board.domain.QPost;
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
 * PostRepositoryImpl의 정렬 변환 로직(옛 NoticeRepositoryImplSortTest 이식 — 공지가 공지 게시판으로 흡수됨, PLAN-notice-to-board.md)을 검증하는 단위 테스트.
 * DB 없이 toOrderSpecifiers 메서드의 동작만 검증한다.
 * 소프트 삭제 필터·검색 조합 검증(실 DB 필요)은 PostRepositoryDataJpaTest가 담당한다.
 */
class PostRepositoryImplSortTest {

    private PostRepositoryImpl repository;
    private final QPost n = QPost.post;

    @BeforeEach
    void setUp() {
        repository = new PostRepositoryImpl(mock(JPAQueryFactory.class));
    }

    @Test
    @DisplayName("정렬 미지정 시 id 내림차순이 기본값")
    void unsorted_returnsIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(Sort.unsorted());

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.id);
    }

    @Test
    @DisplayName("id 정렬이 요청되면 보조 정렬을 중복 추가하지 않는다")
    void sortById_doesNotDuplicate() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "id"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.ASC);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.id);
    }

    @Test
    @DisplayName("title 오름차순 정렬 시 id 보조 정렬이 마지막에 추가된다")
    void sortByTitleAsc_appendsIdTieBreaker() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "title"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.title);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.ASC);
        assertThat(specifiers[1].getTarget()).isEqualTo(n.id);
        assertThat(specifiers[1].getOrder()).isEqualTo(Order.DESC);
    }

    @Test
    @DisplayName("useYn 정렬 시 id 보조 정렬이 추가된다")
    void sortByUseYn_appendsIdTieBreaker() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.DESC, "useYn"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.useYn);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[1].getTarget()).isEqualTo(n.id);
    }

    @Test
    @DisplayName("createDate 정렬 시 id 보조 정렬이 추가된다")
    void sortByCreateDate_appendsIdTieBreaker() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.DESC, "createDate"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.createDate);
        assertThat(specifiers[1].getTarget()).isEqualTo(n.id);
    }

    @Test
    @DisplayName("updateDate 정렬 시 id 보조 정렬이 추가된다")
    void sortByUpdateDate_appendsIdTieBreaker() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "updateDate"));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.updateDate);
        assertThat(specifiers[1].getTarget()).isEqualTo(n.id);
    }

    @Test
    @DisplayName("화이트리스트에 없는 필드는 무시되고 기본값 id 내림차순 반환")
    void unknownField_fallsBackToIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "unknownField"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.id);
    }

    @Test
    @DisplayName("허용 필드 + 미허용 필드 혼합 시 허용 필드 + id 보조 정렬만 적용")
    void mixedFields_onlyAllowedAppliedPlusTieBreaker() {
        Sort sort = Sort.by(Sort.Direction.ASC, "title")
                .and(Sort.by(Sort.Direction.ASC, "injectedField"));

        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(sort);

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(n.title);
        assertThat(specifiers[1].getTarget()).isEqualTo(n.id);
    }
}
