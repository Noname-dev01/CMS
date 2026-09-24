package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBootstrapLoaderTest {

    /** {@link AdminBootstrapLoader}의 private ELIGIBLE_STATUSES와 동일한 D-01 allowlist. */
    private static final Set<MemberStatus> ELIGIBLE_STATUSES =
            EnumSet.of(MemberStatus.ACTIVE, MemberStatus.LOCKED, MemberStatus.PASSWORD_EXPIRED);

    @Mock
    MemberRepository memberRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    TransactionTemplate transactionTemplate;
    @Mock
    Environment environment;

    private final Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 7, 29, 0, 0).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());
    private final jakarta.validation.Validator validator = jakarta.validation.Validation
            .buildDefaultValidatorFactory().getValidator();

    private AdminBootstrapLoader loader;

    @BeforeEach
    void setUp() {
        loader = new AdminBootstrapLoader(memberRepository, passwordEncoder, clock, transactionTemplate,
                validator, environment);

        // TransactionTemplate 목이 실제로 콜백을 실행하도록 스텁 (트랜잭션 경계 자체는 목이므로
        // 여기서는 "콜백이 호출된다"만 재현한다 — 실제 rollback-only 동작은
        // AdminBootstrapConcurrencyIntegrationTest가 실제 DB로 검증한다).
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    @DisplayName("ACTIVE ROLE_ADMIN이 없고 필수 3변수가 유효하면 관리자 계정을 생성한다")
    void noActiveAdmin_validCredentials_createsAdmin() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(false);
        given(environment.getProperty("ADMIN_BOOTSTRAP_USER_ID")).willReturn("bootadmin");
        given(environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD")).willReturn("BootPass1234567!");
        given(environment.getProperty("ADMIN_BOOTSTRAP_EMAIL")).willReturn("boot@example.com");
        given(passwordEncoder.encode("BootPass1234567!")).willReturn("{bcrypt}encoded");

        loader.run();

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("bootadmin");
        assertThat(saved.getUserName()).isEqualTo("bootadmin");
        assertThat(saved.getEmail()).isEqualTo("boot@example.com");
        assertThat(saved.getPwd()).isEqualTo("{bcrypt}encoded");
        assertThat(saved.getUserType()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(saved.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE ROLE_ADMIN이 없고 이메일 대소문자가 섞여도 정규화되어 저장된다")
    void noActiveAdmin_mixedCaseEmail_normalizesBeforeSaving() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(false);
        given(environment.getProperty("ADMIN_BOOTSTRAP_USER_ID")).willReturn("bootadmin");
        given(environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD")).willReturn("BootPass1234567!");
        given(environment.getProperty("ADMIN_BOOTSTRAP_EMAIL")).willReturn("BOOT@EXAMPLE.COM");
        given(passwordEncoder.encode("BootPass1234567!")).willReturn("{bcrypt}encoded");

        loader.run();

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("boot@example.com");
    }

    @Test
    @DisplayName("비밀번호가 73바이트를 초과하면 인코더·저장소를 호출하지 않고 기동 실패")
    void noActiveAdmin_passwordOver72Bytes_throwsWithoutEncodingOrSaving() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(false);
        given(environment.getProperty("ADMIN_BOOTSTRAP_USER_ID")).willReturn("bootadmin");
        given(environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD")).willReturn("a".repeat(73));
        given(environment.getProperty("ADMIN_BOOTSTRAP_EMAIL")).willReturn("boot@example.com");

        assertThatIllegalStateException().isThrownBy(() -> loader.run());

        verify(memberRepository, never()).saveAndFlush(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("적격 상태(ACTIVE/LOCKED/PASSWORD_EXPIRED) ROLE_ADMIN이 이미 있으면 환경변수 유무와 무관하게 아무것도 하지 않는다")
    void eligibleAdminExists_doesNothing() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(true);

        loader.run();

        verify(memberRepository).existsByUserTypeAndStatusIn(eq(Role.ROLE_ADMIN), eq(ELIGIBLE_STATUSES));
        verify(memberRepository, never()).saveAndFlush(any());
        verify(environment, never()).getProperty(any());
    }

    @Test
    @DisplayName("존재 질의는 ACTIVE 단일 상태가 아니라 D-01 allowlist(ACTIVE/LOCKED/PASSWORD_EXPIRED) 전체로 호출된다")
    void existenceQuery_usesFullEligibleStatusAllowlist() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(false);
        given(environment.getProperty("ADMIN_BOOTSTRAP_USER_ID")).willReturn("bootadmin");
        given(environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD")).willReturn("BootPass1234567!");
        given(environment.getProperty("ADMIN_BOOTSTRAP_EMAIL")).willReturn("boot@example.com");
        given(passwordEncoder.encode("BootPass1234567!")).willReturn("{bcrypt}encoded");

        loader.run();

        ArgumentCaptor<Set<MemberStatus>> statusesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(memberRepository).existsByUserTypeAndStatusIn(eq(Role.ROLE_ADMIN), statusesCaptor.capture());
        assertThat(statusesCaptor.getValue())
                .containsExactlyInAnyOrder(MemberStatus.ACTIVE, MemberStatus.LOCKED, MemberStatus.PASSWORD_EXPIRED)
                .doesNotContain(MemberStatus.DISABLED, MemberStatus.DELETED);
    }

    @Test
    @DisplayName("ACTIVE ROLE_ADMIN이 없고 환경변수가 하나라도 없으면 기동 실패(값은 노출 안 함)")
    void noActiveAdmin_missingVariable_throwsWithoutLeakingValue() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(false);
        given(environment.getProperty("ADMIN_BOOTSTRAP_USER_ID")).willReturn("bootadmin");
        given(environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD")).willReturn(null);
        given(environment.getProperty("ADMIN_BOOTSTRAP_EMAIL")).willReturn("boot@example.com");

        assertThatIllegalStateException()
                .isThrownBy(() -> loader.run())
                .withMessageContaining("ADMIN_BOOTSTRAP_PASSWORD")
                .withMessageNotContaining("bootadmin");

        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("ACTIVE ROLE_ADMIN이 없고 자격증명 검증에 실패하면 기동 실패(값은 노출 안 함)")
    void noActiveAdmin_invalidCredentials_throwsWithoutLeakingValue() {
        given(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)).willReturn(false);
        given(environment.getProperty("ADMIN_BOOTSTRAP_USER_ID")).willReturn("bootadmin");
        given(environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD")).willReturn("abc"); // 14자 이하(최소 15코드포인트 미달)
        given(environment.getProperty("ADMIN_BOOTSTRAP_EMAIL")).willReturn("not-an-email");

        assertThatIllegalStateException()
                .isThrownBy(() -> loader.run())
                .withMessageNotContaining("abc")
                .withMessageNotContaining("not-an-email");

        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("저장 중 유니크 제약 위반이 나도 재조회 결과가 ROLE_ADMIN·ACTIVE면 예외를 삼키고 정상 진행한다")
    void createOrReconcile_conflictButReconciled_swallowsException() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        Member existing = Member.builder()
                .userId("bootadmin")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .build();
        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.of(existing));

        loader.createOrReconcile(credentials);

        // 예외가 밖으로 전파되지 않으면 이 지점에 도달한다.
    }

    @Test
    @DisplayName("저장 중 유니크 제약 위반이 나고 재조회해도 ROLE_ADMIN·적격 상태가 아니면 원 예외를 전파한다")
    void createOrReconcile_conflictNotReconciled_rethrows() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> loader.createOrReconcile(credentials));
    }

    @Test
    @DisplayName("동시 생성 경합 중 같은 ID가 LOCKED로 전이됐어도(적격 상태) 재조회 기준으로 흡수한다")
    void createOrReconcile_conflictReconciledAsLocked_swallowsException() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        Member existing = Member.builder()
                .userId("bootadmin")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.LOCKED)
                .build();
        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.of(existing));

        loader.createOrReconcile(credentials);

        // 예외가 밖으로 전파되지 않으면 이 지점에 도달한다.
    }

    @Test
    @DisplayName("동시 생성 경합 중 같은 ID가 PASSWORD_EXPIRED로 전이됐어도(적격 상태) 재조회 기준으로 흡수한다")
    void createOrReconcile_conflictReconciledAsPasswordExpired_swallowsException() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        Member existing = Member.builder()
                .userId("bootadmin")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.PASSWORD_EXPIRED)
                .build();
        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.of(existing));

        loader.createOrReconcile(credentials);

        // 예외가 밖으로 전파되지 않으면 이 지점에 도달한다.
    }

    @Test
    @DisplayName("동시 생성 경합 중 같은 ID가 DISABLED로 전이됐으면(비적격 상태) 부활시키지 않고 원 예외를 전파한다")
    void createOrReconcile_conflictWithDisabledSameId_rethrowsWithoutReviving() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        Member existing = Member.builder()
                .userId("bootadmin")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.DISABLED)
                .build();
        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> loader.createOrReconcile(credentials));
    }

    @Test
    @DisplayName("동시 생성 경합 중 같은 ID가 DELETED로 전이됐으면(비적격 상태) 부활시키지 않고 원 예외를 전파한다")
    void createOrReconcile_conflictWithDeletedSameId_rethrowsWithoutReviving() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        Member existing = Member.builder()
                .userId("bootadmin")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.DELETED)
                .build();
        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> loader.createOrReconcile(credentials));
    }

    @Test
    @DisplayName("동시 생성 경합 중 같은 ID가 다른 역할(ROLE_MANAGER)이면 흡수하지 않고 원 예외를 전파한다")
    void createOrReconcile_conflictWithDifferentRoleSameId_rethrowsWithoutAbsorbing() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "bootpass1!", "boot@example.com");
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key");
        }).when(transactionTemplate).executeWithoutResult(any());

        Member existing = Member.builder()
                .userId("bootadmin")
                .userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE)
                .build();
        given(memberRepository.findByUserId("bootadmin")).willReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> loader.createOrReconcile(credentials));
    }

    @Test
    @DisplayName("toString()에는 userId만 노출되고 비밀번호·이메일 원문은 없다")
    void credentials_toString_doesNotLeakSecrets() {
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials("bootadmin", "s3cr3t-pass", "boot@example.com");

        String result = credentials.toString();

        assertThat(result).contains("bootadmin");
        assertThat(result).doesNotContain("s3cr3t-pass");
        assertThat(result).doesNotContain("boot@example.com");
    }
}
