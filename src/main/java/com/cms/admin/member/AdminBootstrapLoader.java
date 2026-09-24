package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.EmailNormalizer;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * prod 프로파일 초기 관리자 계정 부트스트랩. 기동 조건은 D-01 대안 A(감사 H-01,
 * 2026-09-23 사용자 승인 — adversarial-review/remediation-plan.md PR 2 참조)를 따른다.
 *
 * <p>트리거는 "{@link #ELIGIBLE_STATUSES} 중 하나인 ROLE_ADMIN 존재 여부"다 — 단순
 * ACTIVE만 보면 이미 만료된 자동 잠금(LOCKED)이나 비밀번호 만료(PASSWORD_EXPIRED)
 * 상태의 기존 관리자를 "관리자 없음"으로 오판해, 일시적으로 로그인 불가한 것뿐인 정상
 * 설치의 재기동 자체를 막는다(관리자 로그인 차단과 공개 앱 가용성을 분리하는 것이 D-01의
 * 핵심 근거). 적격 상태의 ADMIN이 있으면 환경변수 검사 자체를 하지 않는다(가용성 유지 —
 * 정상 운영 환경에서 부트스트랩 변수를 지워도 계속 기동돼야 한다). 이 부트스트랩은
 * 기존 계정의 상태·비밀번호 해시·{@code lockedAt}·{@code passwordChangedAt}·재설정
 * 토큰 등 어떤 필드도 변경하지 않는다 — 잠금 해제·만료 복귀는 기존 로그인/재설정 요청
 * 경로({@code LoginFailureService}·{@code PasswordExpiryService}·{@code Member#changePassword})가
 * 그대로 담당한다.
 *
 * <p>이 존재 질의와 신규 ADMIN INSERT는 원자적인 한 동작이 아니다 — 보장하는 것은
 * "질의 실행 시점에 적격 ADMIN이 있었는가"이며, 기동 완료 시점까지 전역적으로 신규 ADMIN이
 * 정확히 한 명만 생기는 것을 보장하지 않는다. 서로 다른 {@code ADMIN_BOOTSTRAP_*}
 * 자격증명(다른 userId)을 가진 두 인스턴스가 동시에 존재 확인을 통과하면 서로 다른 ADMIN이
 * 각각 생성될 수 있다 — 동일 설치의 모든 인스턴스가 같은 부트스트랩 자격증명을 쓴다는
 * 운영 전제 위에서만 "신규 ADMIN 한 명"이 보장된다({@code uk_member_user_id} 유니크
 * 제약은 같은 ID 충돌만 직렬화한다).
 *
 * <p>적격 ADMIN이 없는데 {@code ADMIN_BOOTSTRAP_*} 환경변수도 없거나 유효하지 않으면
 * 기동을 실패시킨다 — "관리자를 못 만드는 것"이 아니라 "관리 불가능한 채로 뜨는 것" 자체가
 * 사고이므로 fail-fast가 조용한 오작동보다 안전하다는 사용자 결정(2026-07-29)에 따른다.
 * DISABLED/DELETED ADMIN만 있거나 ADMIN이 전혀 없으면(빈 DB, MANAGER/USER만 존재 포함)
 * 이 분기에 해당한다.
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

    /**
     * D-01 대안 A의 "기존 ADMIN이 구성된 설치" allowlist. ACTIVE뿐 아니라 LOCKED·
     * PASSWORD_EXPIRED도 포함한다 — 셋 다 계정이 "존재는 하되 지금 당장 로그인은
     * 못 할 수 있는" 상태이지 "관리자가 아예 없는" 상태가 아니기 때문이다. DISABLED·
     * DELETED는 의도적으로 제외한다(관리자를 명시적으로 폐기한 상태 — 신규 부트스트랩
     * 자격증명 요구가 맞다).
     */
    private static final Set<MemberStatus> ELIGIBLE_STATUSES =
            EnumSet.of(MemberStatus.ACTIVE, MemberStatus.LOCKED, MemberStatus.PASSWORD_EXPIRED);

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final Validator validator;
    private final Environment environment;

    @Override
    public void run(String... args) {
        if (memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN, ELIGIBLE_STATUSES)) {
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
                    "적격(ACTIVE/LOCKED/PASSWORD_EXPIRED) 상태의 ROLE_ADMIN 계정이 없어 초기 관리자를 생성해야 하지만, "
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
     * 실제로 ROLE_ADMIN이고 {@link #ELIGIBLE_STATUSES}에 속하는지"까지 확인한 뒤에만 정상
     * 진행으로 흡수한다 — {@code run()}의 skip 조건과 같은 역할·상태 기준을 써서, 동시 생성
     * 경합 중 상대가 이미 LOCKED/PASSWORD_EXPIRED로 전이됐어도 정상 흡수되고, DISABLED/DELETED
     * 로 전이됐다면(또는 다른 역할·다른 ID의 충돌이면) 흡수하지 않고 원 예외를 전파한다.
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
                        .email(EmailNormalizer.normalize(credentials.getEmail()))
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
                            .filter(m -> m.getUserType() == Role.ROLE_ADMIN && ELIGIBLE_STATUSES.contains(m.getStatus()))
                            .isPresent()));
            if (!alreadyBootstrapped) {
                throw e;
            }
            log.info("다른 인스턴스가 이미 부트스트랩 관리자 계정을 생성해 건너뜁니다. userId={}",
                    credentials.getUserId());
        }
    }
}
