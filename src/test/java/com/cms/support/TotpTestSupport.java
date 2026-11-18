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

    /**
     * 2단계를 켠 회원(ADMIN은 필수 — PLAN-totp-required-admin.md)의 실제 폼 로그인을 끝까지 마친 세션: 비밀번호 → 코드 화면 302 → 현재 코드 → {@code /admin} 302.
     * 같은 회원이 30초 안에 다시 로그인하면 코드가 "이미 쓴 코드"가 되므로 제출 직전 쓴 스텝을 되돌린다(시험 전용 조작).
     */
    public static org.springframework.mock.web.MockHttpSession loginWithSecondFactor(
            org.springframework.test.web.servlet.MockMvc mockMvc, org.springframework.jdbc.core.JdbcTemplate jdbc, Clock clock,
            String userId, String password, long memberId, byte[] secret) throws Exception {
        org.springframework.mock.web.MockHttpSession session = (org.springframework.mock.web.MockHttpSession) mockMvc.perform(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin("/admin/login")
                                .user(userId).password(password))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/login/totp"))
                .andReturn().getRequest().getSession(false);
        long step = new TotpCodeVerifier(clock).currentStep();
        jdbc.update("UPDATE member_totp SET last_used_step = ? WHERE member_id = ?", step - 2, memberId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/login/totp").session(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("code", TotpCodeVerifier.codeAt(secret, step)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"));
        return session;
    }
}
