package com.cms.admin.member.repository;

import com.cms.admin.member.domain.QMember;
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
 * MemberRepositoryImpl의 정렬 변환 로직을 검증하는 단위 테스트.
 * DB 없이 toOrderSpecifiers / buildOrderSpecifier 메서드의 동작만 검증한다.
 */
class MemberRepositoryImplSortTest {

    private MemberRepositoryImpl repository;
    private final QMember m = QMember.member;

    @BeforeEach
    void setUp() {
        repository = new MemberRepositoryImpl(mock(JPAQueryFactory.class));
    }

    @Test
    @DisplayName("정렬 미지정 시 id 내림차순이 기본값")
    void unsorted_returnsIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(Sort.unsorted());

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.id);
    }

    @Test
    @DisplayName("id 내림차순 정렬")
    void sortByIdDesc_returnsIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.DESC, "id"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.id);
    }

    @Test
    @DisplayName("userName 오름차순 정렬")
    void sortByUserNameAsc_returnsUserNameAsc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "userName"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.ASC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.userName);
    }

    @Test
    @DisplayName("email 내림차순 정렬")
    void sortByEmailDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.DESC, "email"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.email);
    }

    @Test
    @DisplayName("createDate 내림차순 정렬")
    void sortByCreateDateDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.DESC, "createDate"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.createDate);
    }

    @Test
    @DisplayName("여러 허용 필드 정렬 — 순서 유지")
    void multipleSortFields_appliedInOrder() {
        Sort sort = Sort.by(Sort.Direction.ASC, "userName")
                .and(Sort.by(Sort.Direction.DESC, "createDate"));

        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(sort);

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.userName);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.ASC);
        assertThat(specifiers[1].getTarget()).isEqualTo(m.createDate);
        assertThat(specifiers[1].getOrder()).isEqualTo(Order.DESC);
    }

    @Test
    @DisplayName("화이트리스트에 없는 필드는 무시되고 기본값 id 내림차순 반환")
    void unknownField_fallsBackToIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "unknownField"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.id);
    }

    @Test
    @DisplayName("열거형 필드(userType)는 정렬 미지원 — 무시되고 기본값 반환")
    void enumField_userType_fallsBackToIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "userType"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.id);
    }

    @Test
    @DisplayName("열거형 필드(status)는 정렬 미지원 — 무시되고 기본값 반환")
    void enumField_status_fallsBackToIdDesc() {
        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(
                Sort.by(Sort.Direction.ASC, "status"));

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getOrder()).isEqualTo(Order.DESC);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.id);
    }

    @Test
    @DisplayName("허용 필드 + 미허용 필드 혼합 시 허용 필드만 적용")
    void mixedFields_onlyAllowedApplied() {
        Sort sort = Sort.by(Sort.Direction.ASC, "userName")
                .and(Sort.by(Sort.Direction.ASC, "injectedField"));

        OrderSpecifier<?>[] specifiers = repository.toOrderSpecifiers(sort);

        assertThat(specifiers).hasSize(1);
        assertThat(specifiers[0].getTarget()).isEqualTo(m.userName);
    }

    @Test
    @DisplayName("buildOrderSpecifier — 각 허용 필드에 대해 올바른 경로 반환")
    void buildOrderSpecifier_allAllowedFields() {
        assertThat(repository.buildOrderSpecifier(m, "id", true).getTarget()).isEqualTo(m.id);
        assertThat(repository.buildOrderSpecifier(m, "userId", true).getTarget()).isEqualTo(m.userId);
        assertThat(repository.buildOrderSpecifier(m, "userName", false).getTarget()).isEqualTo(m.userName);
        assertThat(repository.buildOrderSpecifier(m, "email", true).getTarget()).isEqualTo(m.email);
        assertThat(repository.buildOrderSpecifier(m, "createDate", false).getTarget()).isEqualTo(m.createDate);
        assertThat(repository.buildOrderSpecifier(m, "updateDate", true).getTarget()).isEqualTo(m.updateDate);
    }

    @Test
    @DisplayName("buildOrderSpecifier — 열거형/미지원 필드는 null 반환")
    void buildOrderSpecifier_unsupportedField_returnsNull() {
        assertThat(repository.buildOrderSpecifier(m, "userType", true)).isNull();
        assertThat(repository.buildOrderSpecifier(m, "status", true)).isNull();
        assertThat(repository.buildOrderSpecifier(m, "unknown", true)).isNull();
    }
}
