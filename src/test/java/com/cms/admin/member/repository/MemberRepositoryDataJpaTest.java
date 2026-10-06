package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers가 띄우는 일회용 MariaDB로 회원 목록 정렬이 실제 SQL로 이어지는지 검증하는 JPA 슬라이스 테스트.
 * 정렬 변환 로직 자체(순수 단위)는 MemberRepositoryImplSortTest가 담당하며 제거 변이의 주 방어선이다 —
 * 이 테스트는 DB가 동률에서 우연히 안정적일 수 있어 보조 수단이다.
 *
 * <p>@DataJpaTest는 각 테스트를 트랜잭션으로 감싸고 종료 시 롤백하므로 실DB에 데이터가 남지 않는다.
 * 보장 범위는 "조회 사이에 데이터가 변하지 않는 동안"의 동률 순서 결정성이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class MemberRepositoryDataJpaTest extends MariaDbContainerSupport {

    @Autowired
    MemberRepository memberRepository;

    private Member saveMember(String userName, int seq) {
        LocalDateTime now = LocalDateTime.now();
        return memberRepository.saveAndFlush(Member.builder()
                .userId(userName + "-u" + seq)
                .pwd("encoded")
                .userName(userName)
                .email(userName + "-" + seq + "@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
    }

    @Test
    @DisplayName("동률 userName 오름차순 페이징 — 페이지 합이 정확히 전체이고 중복·누락이 없으며 동률 내 순서는 id 내림차순")
    void searchAdminMembers_tiedSortKey_pagesAreDeterministicAndTieBrokenByIdDesc() {
        // 검색 격리용 마커 — 이름이 전부 동일(동률)하다.
        String tiedName = "동률정렬" + System.nanoTime();
        List<Long> savedIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            savedIds.add(saveMember(tiedName, i).getId());
        }
        List<Long> expectedOrder = savedIds.stream().sorted(Comparator.reverseOrder()).toList();

        AdminMemberSearchRequest request = AdminMemberSearchRequest.builder().userName(tiedName).build();
        Sort sort = Sort.by(Sort.Direction.ASC, "userName");

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Page<Member> result = memberRepository.searchAdminMembers(request, PageRequest.of(page, 2, sort));
            assertThat(result.getTotalElements()).isEqualTo(5);
            result.getContent().forEach(m -> collected.add(m.getId()));
        }

        assertThat(collected).hasSize(5).doesNotHaveDuplicates();
        assertThat(collected).containsExactlyElementsOf(expectedOrder);
    }

    @Test
    @DisplayName("정렬 미지정은 기존 기본값(id 내림차순)을 유지한다")
    void searchAdminMembers_unsorted_keepsIdDescDefault() {
        String name = "기본정렬" + System.nanoTime();
        List<Long> savedIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            savedIds.add(saveMember(name, i).getId());
        }

        Page<Member> result = memberRepository.searchAdminMembers(
                AdminMemberSearchRequest.builder().userName(name).build(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Member::getId)
                .containsExactlyElementsOf(savedIds.stream().sorted(Comparator.reverseOrder()).toList());
    }
}
