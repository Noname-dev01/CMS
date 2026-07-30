package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * prod 프로파일 초기 관리자 계정 부트스트랩(PLAN-prod-profile.md 결정 4).
 *
 * <p>트리거는 "ACTIVE ROLE_ADMIN 존재 여부"다 — 회원이 있어도 {@code ROLE_USER}뿐이거나
 * 관리자가 전부 비활성 상태면 "관리 불가능한데 정상 기동처럼 보이는" 사고이므로 부트스트랩을
 * 시도한다. ACTIVE ROLE_ADMIN이 있으면 환경변수 검사 자체를 하지 않는다(가용성 유지 —
 * 정상 운영 환경에서 부트스트랩 변수를 지워도 계속 기동돼야 한다).
 *
 * <p>ACTIVE ROLE_ADMIN이 없는데 {@code ADMIN_BOOTSTRAP_*} 환경변수도 없거나 유효하지 않으면
 * 기동을 실패시킨다 — "관리자를 못 만드는 것"이 아니라 "관리 불가능한 채로 뜨는 것" 자체가
 * 사고이므로 fail-fast가 조용한 오작동보다 안전하다는 사용자 결정(2026-07-29)에 따른다.
 *
 * <p>저장·재조회는 {@link TransactionTemplate}으로 트랜잭션 경계를 명시한다 — 같은 클래스
 * 내부 호출은 {@code @Transactional} 프록시를 타지 않으므로({@code PasswordResetService}와
 * 동일한 이유) 프록시에 의존하지 않는 이 방식을 쓴다.
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class AdminBootstrapLoader implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final Validator validator;
    private final Environment environment;

    @Override
    public void run(String... args) {
        if (memberRepository.existsByUserTypeAndStatus(Role.ROLE_ADMIN, MemberStatus.ACTIVE)) {
            return;
        }

        String userId = environment.getProperty("ADMIN_BOOTSTRAP_USER_ID");
        String password = environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD");
        String email = environment.getProperty("ADMIN_BOOTSTRAP_EMAIL");

        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(userId)) {
            missing.add("ADMIN_BOOTSTRAP_USER_ID");
        }
        if (!StringUtils.hasText(password)) {
            missing.add("ADMIN_BOOTSTRAP_PASSWORD");
        }
        if (!StringUtils.hasText(email)) {
            missing.add("ADMIN_BOOTSTRAP_EMAIL");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "ACTIVE 상태의 ROLE_ADMIN 계정이 없어 초기 관리자를 생성해야 하지만, "
                            + "다음 환경변수가 없습니다: " + missing);
        }

        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials(userId, password, email);
        Set<ConstraintViolation<AdminBootstrapCredentials>> violations = validator.validate(credentials);
        if (!violations.isEmpty()) {
            String fields = violations.stream()
                    .map(v -> v.getPropertyPath().toString())
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("부트스트랩 관리자 자격증명이 유효하지 않습니다(필드: " + fields + ")");
        }

        createOrReconcile(credentials);
    }

    /**
     * 저장을 시도하고, 동시 부트스트랩으로 인한 유니크 제약 위반은 "내가 만들려던 그 계정이
     * 실제로 ROLE_ADMIN·ACTIVE로 존재하는지"까지 확인한 뒤에만 정상 진행으로 흡수한다.
     * 트리거 검사(run())를 우회해 테스트에서 직접 호출할 수 있도록 package-private으로 둔다
     * (동시성 통합 테스트 전용 — run() 전체를 두 번 호출하면 두 번째 호출이 트리거 검사에서
     * 즉시 반환돼 이 경로 자체가 실행되지 않는다).
     */
    void createOrReconcile(AdminBootstrapCredentials credentials) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                LocalDateTime now = LocalDateTime.now(clock);
                memberRepository.saveAndFlush(Member.builder()
                        .userId(credentials.getUserId())
                        .userName(credentials.getUserId())
                        .email(credentials.getEmail())
                        .pwd(passwordEncoder.encode(credentials.getPassword()))
                        .userType(Role.ROLE_ADMIN)
                        .status(MemberStatus.ACTIVE)
                        .createDate(now)
                        .passwordChangedAt(now)
                        .build());
            });
        } catch (DataIntegrityViolationException e) {
            boolean alreadyBootstrapped = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                    memberRepository.findByUserId(credentials.getUserId())
                            .filter(m -> m.getUserType() == Role.ROLE_ADMIN && m.getStatus() == MemberStatus.ACTIVE)
                            .isPresent()));
            if (!alreadyBootstrapped) {
                throw e;
            }
            log.info("다른 인스턴스가 이미 부트스트랩 관리자 계정을 생성해 건너뜁니다. userId={}",
                    credentials.getUserId());
        }
    }
}
