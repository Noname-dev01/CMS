package com.cms;

import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CmsTestApplication.class)
class CmsApplicationTests extends MariaDbContainerSupport {

    @Autowired
    MemberRepository memberRepository;

    @Test
    void contextLoads() {}

    /**
     * TestMemberLoader(dev 프로파일 CommandLineRunner)가 실제로 시드했는지 이 테스트
     * 실행 시점에 직접 확인한다. 이 단언은 "이 테스트가 실행되는 시점에 시드가
     * 존재한다"만 증명하며, JUnit 클래스 실행 순서는 보장되지 않으므로 "첫 컨텍스트
     * 로드 직후"라는 특정 시점을 증명하지는 않는다(PLAN-testcontainers.md 리뷰 5차 #1).
     */
    @Test
    @DisplayName("TestMemberLoader가 시드한 admin 계정이 정확히 1건 존재한다")
    void testMemberLoader_seedsAdminAccount() {
        assertThat(memberRepository.findByUserId("admin")).isPresent();
    }
}
