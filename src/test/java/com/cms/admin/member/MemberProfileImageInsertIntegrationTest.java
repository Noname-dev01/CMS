package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2차 리뷰(codex, PLAN-profile-image-storage.md v3)에서 발견된 실제 버그의 재발 방지 테스트다:
 * {@code profile_image_kind}가 새 NOT NULL 컬럼인데 {@code @Builder.Default}가 없으면
 * {@code Member.builder()...build()}를 쓰는 기존 생성 경로
 * (AdminMemberService.createAdmin/AdminBootstrapLoader/TestMemberLoader — 셋 다 이 필드를
 * 전혀 모른 채 빌드한다)가 전부 {@code kind=null} INSERT로 제약 위반이 나며 깨진다.
 * Mockito 목 리포지토리로는(save()가 실제로 검증하지 않으므로) 이 버그를 잡을 수 없어
 * 실제 MariaDB로 검증한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MemberProfileImageInsertIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    MemberRepository memberRepository;

    @Test
    @DisplayName("profileImageKind를 지정하지 않고 저장해도 NOT NULL 위반 없이 NONE으로 저장된다")
    void save_withoutExplicitProfileImageKind_defaultsToNoneWithoutConstraintViolation() {
        long unique = System.nanoTime();
        Member member = Member.builder()
                .userId("regtest-" + unique)
                .pwd("encoded")
                .userName("회귀테스트")
                .email("regtest-" + unique + "@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .passwordChangedAt(LocalDateTime.now())
                .build(); // profileImageKind 미지정 — 기존 3개 생성 경로와 동일한 패턴

        Member saved = assertDoesNotThrow(() -> memberRepository.saveAndFlush(member));

        assertEquals(ProfileImageKind.NONE, saved.getProfileImageKind());

        // 재조회해도(1차 캐시가 아니라 실제 DB 값) 동일해야 한다.
        memberRepository.flush();
        Member reloaded = memberRepository.findById(saved.getId()).orElseThrow();
        assertEquals(ProfileImageKind.NONE, reloaded.getProfileImageKind());
    }
}
