package com.cms.admin.member.service;

import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.domain.MemberTotpRecoveryCode;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.totp.RecoveryCodes;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 2단계 코드(TOTP 6자리 또는 복구 코드) 판정과 소비. 로그인 2단계·본인 해제가 함께 쓴다.
 *
 * <p>호출자는 이미 {@code member} 행을 잠근 트랜잭션 안에 있어야 한다({@link Propagation#MANDATORY}) — 판정과 소비(쓴 스텝·복구 코드 사용 기록)가
 * 같은 잠금 아래에서 일어나야 동시 제출 두 건이 같은 코드로 모두 통과하지 못한다(PLAN-totp-two-factor.md 결정 2-3). 실패 횟수 기록은 호출자 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TotpCredentialChecker {

    public enum Outcome {
        TOTP_MATCHED,
        RECOVERY_CODE_MATCHED,
        /** 형식 오류 포함 — 실패로 센다 */
        WRONG,
        /** 정답이지만 이미 쓴 스텝 — 통과시키지 않되 실패로 세지 않는다(등록 직후 같은 코드로 재로그인) */
        REUSED,
        /** 비밀값을 복호할 수 없다(키 교체·손상) — 서버 쪽 문제라 실패로 세지 않는다 */
        UNREADABLE
    }

    private final TotpSecretCipher cipher;
    private final TotpCodeVerifier verifier;
    private final MemberTotpRecoveryCodeRepository recoveryCodeRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome checkAndConsume(MemberTotp totp, String input, LocalDateTime now) {
        String code = input == null ? "" : input.trim();
        if (TotpCodeVerifier.isSixAsciiDigits(code)) {
            byte[] secret;
            try {
                secret = cipher.decrypt(totp.getMemberId(), totp.getSecret());
            } catch (TotpSecretCipher.DecryptionException e) {
                log.error("TOTP 비밀값 복호 실패 — 암호화 키가 바뀌었거나 행이 손상됐습니다 (memberId={})", totp.getMemberId());
                return Outcome.UNREADABLE;
            }
            OptionalLong step = verifier.verify(secret, code, totp.getLastUsedStep());
            if (step.isEmpty()) {
                return verifier.isReusedCode(secret, code, totp.getLastUsedStep()) ? Outcome.REUSED : Outcome.WRONG;
            }
            totp.markStepUsed(step.getAsLong());
            return Outcome.TOTP_MATCHED;
        }
        if (RecoveryCodes.looksLikeRecoveryCode(code)) {
            Optional<MemberTotpRecoveryCode> found = recoveryCodeRepository
                    .findFirstByMemberIdAndCodeHashAndUsedAtIsNull(totp.getMemberId(), RecoveryCodes.hash(code));
            if (found.isPresent()) {
                found.get().markUsed(now);
                return Outcome.RECOVERY_CODE_MATCHED;
            }
        }
        return Outcome.WRONG;
    }
}
