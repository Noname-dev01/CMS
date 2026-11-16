package com.cms.support;

import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.domain.MemberTotpRecoveryCode;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.RecoveryCodes;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 2단계 인증 시험 도우미 — 등록 API를 거치지 않고 회원에게 TOTP를 켜 둔다(등록 흐름 자체는 등록 시험이 고정한다).
 * 회원 행이 지워지면 두 테이블 행은 FK CASCADE로 함께 지워진다.
 */
public final class TotpTestSupport {

    private TotpTestSupport() {
    }

    /** 등록된 비밀값과 원문 복구 코드 10개. */
    public record Enrollment(byte[] secret, List<String> recoveryCodes) {
    }

    public static Enrollment enroll(MemberTotpRepository totps, MemberTotpRecoveryCodeRepository codes, TotpSecretCipher cipher,
                                    long memberId, Clock clock) {
        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        LocalDateTime now = LocalDateTime.now(clock);
        // 등록 직전 스텝은 이미 쓴 것으로 둔다 — 시험이 현재 스텝 코드를 바로 쓸 수 있게
        long previousStep = new TotpCodeVerifier(clock).currentStep() - 2;
        totps.saveAndFlush(MemberTotp.enable(memberId, cipher.encrypt(memberId, secret), previousStep, now));
        List<String> recovery = RecoveryCodes.generate(new SecureRandom());
        for (String code : recovery) {
            codes.saveAndFlush(MemberTotpRecoveryCode.issue(memberId, RecoveryCodes.hash(code), now));
        }
        return new Enrollment(secret, recovery);
    }

    /** 지금 시각의 6자리 코드. */
    public static String currentCode(byte[] secret, Clock clock) {
        return TotpCodeVerifier.codeAt(secret, new TotpCodeVerifier(clock).currentStep());
    }

    /** 확실히 틀린 6자리 코드(±1 스텝의 정답과 모두 다름). */
    public static String wrongCode(byte[] secret, Clock clock) {
        long step = new TotpCodeVerifier(clock).currentStep();
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            if (!code.equals(TotpCodeVerifier.codeAt(secret, step - 1))
                    && !code.equals(TotpCodeVerifier.codeAt(secret, step))
                    && !code.equals(TotpCodeVerifier.codeAt(secret, step + 1))) {
                return code;
            }
        }
    }
}
