package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.domain.MemberPasskey;
import com.cms.admin.member.domain.MemberPasskeyUser;
import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.admin.member.passkey.PasskeyCounterRegression;
import com.cms.admin.member.passkey.PasskeyRelyingParty;
import com.cms.admin.member.repository.MemberPasskeyRepository;
import com.cms.admin.member.repository.MemberPasskeyUserRepository;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.service.TotpCredentialChecker;
import com.cms.admin.member.service.TotpCredentialChecker.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 로그인 2단계 판정(PLAN-totp-two-factor.md 결정 2-3). 회원 행 잠금 아래 한 트랜잭션에서 재확인 → 코드 판정·소비 →
 * 성공 리셋 또는 실패 기록·잠금을 하고 <b>커밋된 결과만</b> 돌려준다 — 완전 권한 컨텍스트 저장은 반환 뒤 호출자가 한다.
 * 실패도 기록이므로 예외로 롤백하지 않고 결과로 돌려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TotpLoginService {

    public enum Status {
        SUCCESS,
        WRONG_CODE,
        /** 패스키 검증 실패(서명·챌린지·자격 불일치) — 오답과 같이 센다(PLAN-passkey-second-factor.md 결정 4) */
        WRONG_PASSKEY,
        /** 패스키 서명 횟수 역행(복제 의심) — 거부하되 세지 않는다(정상 어설션의 도착 순서 역전으로도 생긴다, PLAN-passkey-login.md 결정 8) */
        PASSKEY_COUNTER_REJECTED,
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
    private final MemberPasskeyRepository passkeyRepository;
    private final MemberPasskeyUserRepository passkeyUserRepository;
    private final PasskeyRelyingParty relyingParty;
    private final PasskeyJson passkeyJson;
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

    /**
     * 코드 화면 GET의 패스키 어설션 대기 — 기능이 켜져 있고 그 회원에게 유효한 패스키가 있을 때만 준다(없으면 빈 값 — 화면에 버튼 없음).
     * 이 판정이 재사용보다 먼저다. 세션의 현재 대기가 같은 회원이고 남은 수명 하한 이상이면 <b>그 객체를 그대로</b> 돌려주고(같은 회원의 탭들이
     * 나눠 쓴다 — PLAN-passkey-challenge-stale-tab.md 결정 1·4), 아니면 새로 만든다. 호출자는 새로 만든 값(넘긴 값과 다른 객체)일 때만 세션에 쓴다(결정 3).
     * 옵션의 {@code allowCredentials}는 발급 시점 그 회원의 유효한 자격뿐이다(라이브러리 → 저장소 어댑터 — 재사용 대기는 그 뒤 추가된 자격을 담지 않는다).
     * 쓰기가 없어 행 잠금을 잡지 않는다.
     *
     * @param current 세션의 현재 대기(없으면 null)
     */
    @Transactional(readOnly = true)
    public Optional<PasskeyAssertionState> resumeOrStartPasskeyAssertion(CustomUserDetails principal, PasskeyAssertionState current) {
        if (!relyingParty.isEnabled() || passkeyRepository.countValidByMemberId(principal.getId()) == 0) {
            return Optional.empty();
        }
        if (current != null && current.isReusableFor(principal.getId(), LocalDateTime.now(clock))) {
            return Optional.of(current);
        }
        PublicKeyCredentialRequestOptions options = relyingParty.requestOptions(principal.getUsername());
        if (options.getAllowCredentials() == null || options.getAllowCredentials().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PasskeyAssertionState(UUID.randomUUID().toString(), principal.getId(), options, LocalDateTime.now(clock)));
    }

    /**
     * 패스키로 2단계 판정(결정 4) — 코드 판정과 같은 회원 행 잠금·재확인·실패 기록·커밋 뒤 반환 계약이다.
     * 기능 꺼짐·패스키 없음·TOTP 없음은 {@code REJECTED}(카운트 없음). 형식 오류·검증 실패·자격 불일치는 {@code WRONG_PASSKEY}(카운트, 5회 잠금).
     */
    @Transactional
    public Result verifyPasskey(CustomUserDetails principal, PublicKeyCredentialRequestOptions options, String credentialJson,
                                String requestIp, String requestUri) {
        Optional<Member> locked = memberRepository.findByIdForUpdate(principal.getId());
        if (locked.isEmpty() || !matchesLogin(locked.get(), principal)) {
            return Result.of(Status.REJECTED);
        }
        Member member = locked.get();
        Optional<MemberTotp> found = memberTotpRepository.findById(member.getId());
        Optional<MemberPasskeyUser> handle = passkeyUserRepository.findById(member.getId());
        if (found.isEmpty() || handle.isEmpty() || !relyingParty.isEnabled()
                || passkeyRepository.countValidByMemberId(member.getId()) == 0) {
            return Result.of(Status.REJECTED);
        }
        MemberTotp totp = found.get();

        PasskeyCheck check = passkeyMatches(member, handle.get(), totp, options, credentialJson);
        if (check == PasskeyCheck.COUNTER_REGRESSION) {
            return Result.of(Status.PASSKEY_COUNTER_REJECTED);
        }
        if (check == PasskeyCheck.MISMATCH) {
            if (totp.recordFailureAndCheckThreshold()) {
                loginFailureService.lockForSecondFactorFailures(member, requestIp, requestUri);
                return Result.of(Status.LOCKED);
            }
            return Result.of(Status.WRONG_PASSKEY);
        }
        totp.resetFailures();
        // 벌크 리셋(clearAutomatically) 앞에서 반영한다 — totp 실패 횟수 0과 어댑터가 바꾼 서명 횟수·사용 시각(리뷰 2-2)
        memberTotpRepository.flush();
        memberRepository.resetFailedLoginCountIfActive(member.getUserId());
        return new Result(Status.SUCCESS, recoveryCodeRepository.countByMemberIdAndUsedAtIsNull(member.getId()), false);
    }

    private enum PasskeyCheck { MATCH, MISMATCH, COUNTER_REGRESSION }

    /** 어설션 검증 + 돌려받은 핸들과 자격 행이 이 회원의 유효한 것인지 직접 대조(allowCredentials 검사에 더한 이중 방어). */
    private PasskeyCheck passkeyMatches(Member member, MemberPasskeyUser handle, MemberTotp totp,
                                   PublicKeyCredentialRequestOptions options, String credentialJson) {
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> credential = passkeyJson.readAssertion(credentialJson);
            // 응답 userHandle은 서명 대상이 아니고 라이브러리(7.0.7)가 검증기에 넘기지 않는다 — 있으면 본인 핸들과 직접 대조한다
            // (WebAuthn 사양: 응답 userHandle은 자격 소유자의 핸들과 같아야 한다. 코드 리뷰 2라운드)
            if (credential.getResponse() != null && credential.getResponse().getUserHandle() != null
                    && !Arrays.equals(credential.getResponse().getUserHandle().getBytes(), handle.getUserHandle())) {
                return PasskeyCheck.MISMATCH;
            }
            PublicKeyCredentialUserEntity entity = relyingParty.authenticate(options, credential);
            if (entity == null || !Arrays.equals(entity.getId().getBytes(), handle.getUserHandle())) {
                return PasskeyCheck.MISMATCH;
            }
            Optional<MemberPasskey> row = passkeyRepository.findByCredentialId(credential.getRawId().getBytes());
            return row.isPresent()
                    && Objects.equals(row.get().getMemberId(), member.getId())
                    && !row.get().getCreatedAt().isBefore(totp.getEnabledAt()) ? PasskeyCheck.MATCH : PasskeyCheck.MISMATCH;
        } catch (RuntimeException e) {
            if (PasskeyCounterRegression.matches(e)) {
                log.warn("패스키 서명 횟수 역행 — 거부(실패로 세지 않음) (memberId={})", member.getId());
                return PasskeyCheck.COUNTER_REGRESSION;
            }
            log.info("패스키 2단계 검증 실패 (memberId={}, type={})", member.getId(), e.getClass().getName());
            return PasskeyCheck.MISMATCH;
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
