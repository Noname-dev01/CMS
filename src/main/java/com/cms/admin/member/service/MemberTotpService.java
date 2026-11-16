package com.cms.admin.member.service;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.domain.MemberTotpRecoveryCode;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.TotpCredentialChecker.Outcome;
import com.cms.admin.member.totp.RecoveryCodes;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSessionRevokeEvent;
import com.cms.config.auth.CustomUserDetails;
import com.cms.config.auth.LoginFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 2단계 인증 등록·본인 해제·ADMIN 해제(PLAN-totp-two-factor.md 결정 4).
 *
 * <p>모든 변경은 {@code member} 행 잠금 아래에서 하고, 잠금 직후 상태(ACTIVE·관리자 역할·principal과 같은 역할)를 다시 본다 —
 * 세션 만료는 이미 인가를 통과한 진행 중 요청을 취소하지 않으므로 잠금에서 기다리던 요청이 잠긴 계정을 바꾸면 안 된다(409).
 * 재인증(현재 비밀번호·코드) 실패는 카운터에 기록돼야 하므로 판정 트랜잭션을 커밋한 <b>뒤</b> 400을 던진다 —
 * 그래서 {@code @Transactional} 대신 {@link TransactionTemplate}을 쓴다.
 */
@Service
@RequiredArgsConstructor
public class MemberTotpService {

    static final Duration ENROLLMENT_TTL = Duration.ofMinutes(10);
    private static final int SECRET_BYTES = 20;
    private static final String REAUTH_FAILED = "현재 비밀번호 또는 인증 코드가 올바르지 않습니다.";
    private static final String STATE_CHANGED = "계정 상태가 바뀌어 요청을 처리할 수 없습니다. 다시 로그인해 주세요.";

    private final MemberRepository memberRepository;
    private final MemberTotpRepository memberTotpRepository;
    private final MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    private final TotpCredentialChecker checker;
    private final TotpSecretCipher cipher;
    private final TotpCodeVerifier verifier;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureService loginFailureService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record TotpStatus(boolean enabled, LocalDateTime enabledAt, long remainingRecoveryCodes) {
    }

    private enum Verdict { OK, BAD_CREDENTIALS, STATE_CHANGED, NOT_ENABLED, ALREADY_ENABLED }

    @Transactional(readOnly = true)
    public TotpStatus status(Long memberId) {
        return memberTotpRepository.findById(memberId)
                .map(t -> new TotpStatus(true, t.getEnabledAt(), recoveryCodeRepository.countByMemberIdAndUsedAtIsNull(memberId)))
                .orElse(new TotpStatus(false, null, 0));
    }

    /** 새 비밀값으로 등록 대기를 만든다(DB 쓰기 없음 — 호출자가 세션에 둔다). 이미 사용 중이면 409. */
    @Transactional(readOnly = true)
    public TotpEnrollment startEnrollment(CustomUserDetails principal) {
        if (memberTotpRepository.existsById(principal.getId())) {
            throw new ConflictException("이미 2단계 인증을 사용 중입니다.");
        }
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return new TotpEnrollment(UUID.randomUUID().toString(), principal.getId(), secret, LocalDateTime.now(clock));
    }

    /**
     * 등록 대기를 확정한다 — 성공하면 원문 복구 코드 10개를 돌려주고(이 응답 1회뿐), 본인의 모든 세션을 만료한다(현재 세션 포함 —
     * 등록 전에 만들어진 세션을 2단계 없이 남기지 않는다).
     *
     * @param pending 세션의 대기 상태(없으면 null)
     */
    public List<String> confirmEnrollment(CustomUserDetails principal, TotpEnrollment pending, String enrollmentId,
                                          String code, String currentPassword, String requestIp, String requestUri) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (pending == null
                || !Objects.equals(pending.memberId(), principal.getId())
                || !Objects.equals(pending.enrollmentId(), enrollmentId)
                || pending.issuedAt().plus(ENROLLMENT_TTL).isBefore(now)) {
            throw new ConflictException("등록 요청이 만료됐거나 다른 화면에서 새로 시작됐습니다. 처음부터 다시 진행해 주세요.");
        }
        List<String> recoveryCodes = RecoveryCodes.generate(random);
        Verdict verdict = transactionTemplate.execute(status -> {
            Member member = memberRepository.findByIdForUpdate(principal.getId()).orElse(null);
            if (member == null || !stillAuthorized(member, principal)) {
                return Verdict.STATE_CHANGED;
            }
            if (memberTotpRepository.existsById(member.getId())) {
                return Verdict.ALREADY_ENABLED;
            }
            OptionalLong step = passwordEncoder.matches(currentPassword, member.getPwd())
                    ? verifier.verify(pending.secret(), code == null ? "" : code.trim(), -1)
                    : OptionalLong.empty();
            if (step.isEmpty()) {
                // 아직 TOTP 행이 없으므로 로그인 실패 카운터에 기록한다(5회 → 잠금·세션 만료)
                loginFailureService.recordFailure(member.getUserId(), requestIp, requestUri);
                return Verdict.BAD_CREDENTIALS;
            }
            memberTotpRepository.save(MemberTotp.enable(member.getId(), cipher.encrypt(member.getId(), pending.secret()), step.getAsLong(), now));
            for (String recoveryCode : recoveryCodes) {
                recoveryCodeRepository.save(MemberTotpRecoveryCode.issue(member.getId(), RecoveryCodes.hash(recoveryCode), now));
            }
            memberTotpRepository.flush();
            memberRepository.resetFailedLoginCountIfActive(member.getUserId());
            // 발행 순서 = AFTER_COMMIT 재생 순서: 세션 만료(인메모리)가 감사(DB)보다 먼저
            eventPublisher.publishEvent(new AdminSessionRevokeEvent(member.getId()));
            eventPublisher.publishEvent(new MemberTotpAuditEvent(AdminActionTypes.TOTP_ENABLE, member.getId(), member.getUserId(),
                    member.getId(), requestIp, requestUri, "PUT"));
            return Verdict.OK;
        });
        throwUnlessOk(verdict);
        return recoveryCodes;
    }

    /** 본인 해제 — 현재 비밀번호 + TOTP 또는 복구 코드. 비밀번호가 틀리면 코드는 판정·소비하지 않는다. */
    public void disable(CustomUserDetails principal, String code, String currentPassword, String requestIp, String requestUri) {
        Verdict verdict = transactionTemplate.execute(status -> {
            Member member = memberRepository.findByIdForUpdate(principal.getId()).orElse(null);
            if (member == null || !stillAuthorized(member, principal)) {
                return Verdict.STATE_CHANGED;
            }
            MemberTotp totp = memberTotpRepository.findById(member.getId()).orElse(null);
            if (totp == null) {
                return Verdict.NOT_ENABLED;
            }
            Outcome outcome = passwordEncoder.matches(currentPassword, member.getPwd())
                    ? checker.checkAndConsume(totp, code, LocalDateTime.now(clock))
                    : Outcome.WRONG;
            if (outcome == Outcome.UNREADABLE || outcome == Outcome.REUSED) {
                // 서버 쪽 키 문제·이미 쓴 코드 — 세지 않는다(복구 코드·다음 코드로 해제 가능)
                return Verdict.BAD_CREDENTIALS;
            }
            if (outcome == Outcome.WRONG) {
                if (totp.recordFailureAndCheckThreshold()) {
                    loginFailureService.lockForSecondFactorFailures(member, requestIp, requestUri);
                }
                return Verdict.BAD_CREDENTIALS;
            }
            recoveryCodeRepository.deleteAllByMemberId(member.getId());
            memberTotpRepository.delete(totp);
            eventPublisher.publishEvent(new MemberTotpAuditEvent(AdminActionTypes.TOTP_DISABLE, member.getId(), member.getUserId(),
                    member.getId(), requestIp, requestUri, "DELETE"));
            return Verdict.OK;
        });
        throwUnlessOk(verdict);
    }

    /**
     * ADMIN이 다른 회원의 2단계 인증을 해제한다(인증기·복구 코드를 모두 잃은 경우의 복구). 대상 상태와 무관하게 허용하고,
     * 사용 중이 아니면 아무것도 하지 않는다(감사 없음). 대상 세션은 끊지 않는다 — 보안 수준을 낮추는 조작이라 효과가 없다.
     */
    public void resetByAdmin(CustomUserDetails actor, Long targetMemberId, String requestIp, String requestUri) {
        if (Objects.equals(actor.getId(), targetMemberId)) {
            throw new InvalidRequestException("본인의 2단계 인증은 보안 설정에서 해제합니다.");
        }
        transactionTemplate.executeWithoutResult(status -> {
            Member target = memberRepository.findByIdForUpdate(targetMemberId)
                    .orElseThrow(() -> new ResourceNotFoundException("관리자를 찾을 수 없습니다."));
            memberTotpRepository.findById(target.getId()).ifPresent(totp -> {
                recoveryCodeRepository.deleteAllByMemberId(target.getId());
                memberTotpRepository.delete(totp);
                eventPublisher.publishEvent(new MemberTotpAuditEvent(AdminActionTypes.TOTP_RESET, actor.getId(), actor.getUserId(),
                        target.getId(), requestIp, requestUri, "DELETE"));
            });
        });
    }

    /** 잠금 직후 재확인 — ACTIVE이고 관리자 역할이며 요청 주체의 역할과 같은가. */
    private static boolean stillAuthorized(Member member, CustomUserDetails principal) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        Role role = member.getUserType();
        if (role != Role.ROLE_ADMIN && role != Role.ROLE_MANAGER) {
            return false;
        }
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(role.name()::equals);
    }

    private static void throwUnlessOk(Verdict verdict) {
        switch (verdict) {
            case OK -> {
            }
            case BAD_CREDENTIALS -> throw new InvalidRequestException(REAUTH_FAILED);
            case STATE_CHANGED -> throw new ConflictException(STATE_CHANGED);
            case NOT_ENABLED -> throw new ConflictException("2단계 인증을 사용하고 있지 않습니다.");
            case ALREADY_ENABLED -> throw new ConflictException("이미 2단계 인증을 사용 중입니다.");
        }
    }
}
