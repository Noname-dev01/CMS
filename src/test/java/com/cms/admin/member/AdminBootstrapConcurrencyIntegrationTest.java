package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link AdminBootstrapLoader#createOrReconcile}의 TransactionTemplate 기반 저장→재조회
 * 경계가 실제 MariaDB에서 의도대로 동작하는지 검증한다. Mockito 단위 테스트
 * ({@link AdminBootstrapLoaderTest})는 트랜잭션 경계 자체(자기 자신 호출 시 프록시 미적용,
 * rollback-only 등)를 재현하지 못하므로 실제 DB로 검증한다.
 *
 * <p>트리거 검사({@code run()})를 우회해 {@code createOrReconcile}을 직접 두 번 호출한다 —
 * {@code run()} 전체를 두 번 호출하면 두 번째 호출이 트리거 검사(existsByUserTypeAndStatus)에서
 * 이미 첫 번째 호출이 만든 계정을 보고 즉시 반환해버려, 정작 검증하려는 유니크 제약 위반·
 * 재조회 경로가 전혀 실행되지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class AdminBootstrapConcurrencyIntegrationTest extends MariaDbContainerSupport {

    private static final String TEST_USER_ID = "boot-concurrency-test";

    @Autowired
    MemberRepository memberRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    Clock clock;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    Validator validator;
    @Autowired
    Environment environment;

    @AfterEach
    void cleanUp() {
        memberRepository.findByUserId(TEST_USER_ID).ifPresent(member -> memberRepository.deleteById(member.getId()));
    }

    @Test
    @DisplayName("같은 자격증명으로 두 번 호출하면 두 번째는 유니크 제약 위반을 겪고 재조회로 정상 흡수된다")
    void secondCallWithSameCredentials_reconcilesWithoutDuplicating() {
        AdminBootstrapLoader loader = new AdminBootstrapLoader(
                memberRepository, passwordEncoder, clock, transactionTemplate, validator, environment);
        AdminBootstrapCredentials credentials =
                new AdminBootstrapCredentials(TEST_USER_ID, "bootpass1!", "boot-concurrency@example.com");

        loader.createOrReconcile(credentials);

        assertThatCode(() -> loader.createOrReconcile(credentials)).doesNotThrowAnyException();

        Optional<Member> saved = memberRepository.findByUserId(TEST_USER_ID);
        assertThat(saved).isPresent();
        assertThat(saved.get().getUserType()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(saved.get().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
