package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 통합 검색용 회원 키워드 쿼리(MemberRepositoryImpl#searchByKeyword) — 실제 MariaDB로 OR 조건·제외 대상·와일드카드 리터럴 처리를 검증한다.
 * 각 테스트는 트랜잭션 롤백으로 실DB에 데이터를 남기지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class MemberKeywordSearchDataJpaTest extends MariaDbContainerSupport {

    private static final PageRequest FIRST_FIVE = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "id"));

    @Autowired
    MemberRepository memberRepository;

    private Member save(String userId, String userName, Role role, MemberStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return memberRepository.saveAndFlush(Member.builder()
                .userId(userId)
                .pwd("encoded")
                .userName(userName)
                .email(userId + "@test.com")
                .userType(role)
                .status(status)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
    }

    @Test
    @DisplayName("아이디에만 일치해도, 이름에만 일치해도 조회된다(OR)")
    void matchesUserIdOrUserName() {
        String marker = "kw" + System.nanoTime();
        Member byId = save(marker + "-id", "아무개", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        Member byName = save("other-" + System.nanoTime(), marker + "이름", Role.ROLE_MANAGER, MemberStatus.ACTIVE);

        Page<Member> result = memberRepository.searchByKeyword(marker, FIRST_FIVE);

        assertThat(result.getContent()).extracting(Member::getId).containsExactlyInAnyOrder(byId.getId(), byName.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("DELETED 계정과 ROLE_USER 계정은 제외된다")
    void excludesDeletedAndRoleUser() {
        String marker = "ex" + System.nanoTime();
        Member active = save(marker + "-a", "활성", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        save(marker + "-d", "삭제", Role.ROLE_ADMIN, MemberStatus.DELETED);
        save(marker + "-u", "일반", Role.ROLE_USER, MemberStatus.ACTIVE);

        Page<Member> result = memberRepository.searchByKeyword(marker, FIRST_FIVE);

        assertThat(result.getContent()).extracting(Member::getId).containsExactly(active.getId());
    }

    @Test
    @DisplayName("검색어의 %·_ 는 와일드카드가 아니라 리터럴로 처리된다")
    void wildcardsAreLiterals() {
        String marker = "wc" + System.nanoTime();
        Member percent = save(marker + "-100%", "퍼센트", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        save(marker + "-1000", "일반", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        Member underscore = save(marker + "_x", "언더", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        save(marker + "-x", "대시", Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        assertThat(memberRepository.searchByKeyword("100%", FIRST_FIVE).getContent())
                .extracting(Member::getId).containsExactly(percent.getId());
        assertThat(memberRepository.searchByKeyword(marker + "_", FIRST_FIVE).getContent())
                .extracting(Member::getId).containsExactly(underscore.getId());
    }

    @Test
    @DisplayName("빈·공백 검색어는 조회하지 않는다(전체 목록 방지)")
    void blankKeywordReturnsEmpty() {
        save("blank-" + System.nanoTime(), "공백", Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        assertThat(memberRepository.searchByKeyword("   ", FIRST_FIVE).getContent()).isEmpty();
        assertThat(memberRepository.searchByKeyword(null, FIRST_FIVE).getContent()).isEmpty();
    }

    @Test
    @DisplayName("페이지 크기만큼만 반환하고 전체 건수는 total로 알려준다")
    void limitsContentAndReportsTotal() {
        String marker = "lim" + System.nanoTime();
        for (int i = 0; i < 7; i++) {
            save(marker + "-" + i, "이름" + i, Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        }

        Page<Member> result = memberRepository.searchByKeyword(marker, FIRST_FIVE);

        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getTotalElements()).isEqualTo(7);
    }
}
