package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.TotpCredentialChecker;
import com.cms.admin.member.service.TotpCredentialChecker.Outcome;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 로그인 2단계 판정(PLAN-totp-two-factor.md 결정 2-3). 회원 행 잠금 아래 한 트랜잭션에서 재확인 → 코드 판정·소비 →
 * 성공 리셋 또는 실패 기록·잠금을 하고 <b>커밋된 결과만</b> 돌려준다 — 완전 권한 컨텍스트 저장은 반환 뒤 호출자가 한다.
 * 실패도 기록이므로 예외로 롤백하지 않고 결과로 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class TotpLoginService {

    public enum Status {
        SUCCESS,
        WRONG_CODE,
        /** 이미 쓴 코드 — 실패로 세지 않고 다음 코드를 기다리라고 안내한다 */
        REUSED_CODE,
        /** 이번 실패로 계정이 잠겼다 */
        LOCKED,
        /** 상태·역할·비밀번호·등록이 로그인 때와 다르거나 비밀값을 읽을 수 없다 — 판정·카운트 없음 */
        REJECTED
    }

    public record Result(Status status, long remainingRecoveryCodes, boolean usedRecoveryCode) {
        static Result of(Status status) {
            return new Result(status, 0, false);
        }
    }

    private final MemberRepository memberRepository;
    private final MemberTotpRepository memberTotpRepository;
    private final MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    private final TotpCredentialChecker checker;
    private final LoginFailureService loginFailureService;
    private final Clock clock;

    @Transactional
    public Result verify(CustomUserDetails principal, String code, String requestIp, String requestUri) {
        Optional<Member> locked = memberRepository.findByIdForUpdate(principal.getId());
        if (locked.isEmpty() || !matchesLogin(locked.get(), principal)) {
            return Result.of(Status.REJECTED);
        }
        Member member = locked.get();
        Optional<MemberTotp> found = memberTotpRepository.findById(member.getId());
        if (found.isEmpty()) {
            return Result.of(Status.REJECTED);
        }
        MemberTotp totp = found.get();
        LocalDateTime now = LocalDateTime.now(clock);

        Outcome outcome = checker.checkAndConsume(totp, code, now);
        switch (outcome) {
            case UNREADABLE -> {
                return Result.of(Status.REJECTED);
            }
            case REUSED -> {
                return Result.of(Status.REUSED_CODE);
            }
            case WRONG -> {
                if (totp.recordFailureAndCheckThreshold()) {
                    // 잠금 쿼리가 대기 중인 변경(실패 횟수 0)을 먼저 flush한다
                    loginFailureService.lockForSecondFactorFailures(member, requestIp, requestUri);
                    return Result.of(Status.LOCKED);
                }
                return Result.of(Status.WRONG_CODE);
            }
            default -> {
                totp.resetFailures();
                memberTotpRepository.flush();
                // 두 요소를 모두 통과했을 때만 비밀번호 쪽 실패 횟수도 리셋한다(결정 1 — 중간 상태의 재확인은 리셋하지 않는다)
                memberRepository.resetFailedLoginCountIfActive(member.getUserId());
                boolean usedRecovery = outcome == Outcome.RECOVERY_CODE_MATCHED;
                return new Result(Status.SUCCESS, recoveryCodeRepository.countByMemberIdAndUsedAtIsNull(member.getId()), usedRecovery);
            }
        }
    }

    /** 로그인(비밀번호 단계) 때의 principal과 지금 행이 같은 상태인가 — ACTIVE·같은 역할·같은 비밀번호 해시. */
    private static boolean matchesLogin(Member member, CustomUserDetails principal) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        String principalRole = principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).findFirst().orElse(null);
        return member.getUserType().name().equals(principalRole)
                && Objects.equals(member.getPwd(), principal.getPassword());
    }
}
