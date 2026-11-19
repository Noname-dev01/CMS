package com.cms.admin.member.service;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberPasskey;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.admin.member.passkey.PasskeyRelyingParty;
import com.cms.admin.member.repository.MemberPasskeyRepository;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.CustomUserDetails;
import com.cms.config.auth.LoginFailureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 패스키 등록·목록·삭제(PLAN-passkey-second-factor.md 결정 1·3·5).
 *
 * <ul>
 *   <li><b>불변식</b>: 패스키는 TOTP를 켠 회원의 대체 2단계 수단이다. 유효한 패스키 = 현재 TOTP 등록 이후에 만든 것.</li>
 *   <li><b>잠금</b>: 저장소 어댑터를 거치는 모든 경로(옵션 발급의 핸들 생성 포함)는 {@code member} 행 잠금 트랜잭션 안이다.
 *       세션 상태 교체의 직렬화는 호출자(컨트롤러)가 세션 뮤텍스로 한다 — 순서는 세션 뮤텍스 → 회원 행.</li>
 *   <li>재인증 실패는 카운터가 커밋된 <b>뒤</b> 400을 던진다({@link TransactionTemplate}, {@code MemberTotpService}와 같은 이유).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberPasskeyService {

    private static final String STATE_CHANGED = "계정 상태가 바뀌어 요청을 처리할 수 없습니다. 다시 로그인해 주세요.";
    private static final String TOTP_REQUIRED = "패스키를 추가하려면 먼저 2단계 인증(인증 앱)을 켜야 합니다.";
    private static final String LIMIT_REACHED = "패스키는 " + MemberPasskey.MAX_PER_MEMBER + "개까지 등록할 수 있습니다.";
    private static final String REGISTRATION_EXPIRED = "패스키 등록 요청이 만료됐거나 다른 화면에서 새로 시작됐습니다. 다시 시도해 주세요.";
    private static final String BAD_PASSWORD = "현재 비밀번호가 올바르지 않습니다.";
    private static final String VERIFICATION_FAILED = "패스키를 확인하지 못했습니다. 다시 시도해 주세요.";

    private final MemberRepository memberRepository;
    private final MemberTotpRepository memberTotpRepository;
    private final MemberPasskeyRepository passkeyRepository;
    private final PasskeyRelyingParty relyingParty;
    private final PasskeyJson passkeyJson;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureService loginFailureService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public record PasskeySummary(Long id, String label, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
        static PasskeySummary of(MemberPasskey passkey) {
            return new PasskeySummary(passkey.getId(), passkey.getLabel(), passkey.getCreatedAt(), passkey.getLastUsedAt());
        }
    }

    /** @param enabled 기능 켜짐 여부 @param totpEnabled 2단계(TOTP) 사용 여부 — 꺼져 있으면 등록할 수 없다 */
    public record PasskeyList(boolean enabled, boolean totpEnabled, List<PasskeySummary> items) {
    }

    private enum Verdict { OK, STATE_CHANGED, TOTP_REQUIRED, LIMIT_REACHED, EXPIRED, BAD_PASSWORD, VERIFICATION_FAILED }

    public boolean isEnabled() {
        return relyingParty.isEnabled();
    }

    @Transactional(readOnly = true)
    public PasskeyList list(Long memberId) {
        boolean totpEnabled = memberTotpRepository.existsById(memberId);
        List<PasskeySummary> items = passkeyRepository.findValidByMemberId(memberId).stream().map(PasskeySummary::of).toList();
        return new PasskeyList(relyingParty.isEnabled(), totpEnabled, items);
    }

    /** 유효한 패스키 개수(ADMIN의 타인 상태 조회용). */
    @Transactional(readOnly = true)
    public long countValid(Long memberId) {
        return passkeyRepository.countValidByMemberId(memberId);
    }

    /**
     * 등록 대기를 만든다 — 회원 행 잠금 아래에서 상태·TOTP·상한을 확인하고 옵션을 만든다(첫 등록이면 라이브러리가 핸들을 만들어 저장 —
     * 잠금이 다른 세션의 동시 첫 발급과 직렬화한다, 리뷰 1-2). 호출자가 세션에 둔다(부를 때마다 대체).
     */
    public PasskeyRegistration startRegistration(CustomUserDetails principal) {
        requireEnabled();
        LocalDateTime now = LocalDateTime.now(clock);
        PasskeyRegistration[] result = new PasskeyRegistration[1];
        Verdict verdict = transactionTemplate.execute(status -> {
            Member member = memberRepository.findByIdForUpdate(principal.getId()).orElse(null);
            if (member == null || !stillAuthorized(member, principal)) {
                return Verdict.STATE_CHANGED;
            }
            MemberTotp totp = memberTotpRepository.findById(member.getId()).orElse(null);
            if (totp == null) {
                return Verdict.TOTP_REQUIRED;
            }
            if (passkeyRepository.countValidByMemberId(member.getId()) >= MemberPasskey.MAX_PER_MEMBER) {
                return Verdict.LIMIT_REACHED;
            }
            PublicKeyCredentialCreationOptions options = relyingParty.creationOptions(member.getUserId());
            result[0] = new PasskeyRegistration(UUID.randomUUID().toString(), member.getId(), totp.getEnabledAt(), options, now);
            return Verdict.OK;
        });
        throwUnlessOk(verdict);
        return result[0];
    }

    /**
     * 등록을 확정한다. 순서: 회원 행 잠금 → 상태 재확인(409) → 대기 대조(회원·ID·5분, 409) → TOTP 세대 대조(409) → 현재 비밀번호
     * (틀리면 {@code member_totp.failed_attempts}, 5회 잠금, 커밋 뒤 400) → 상한 → 공개키 검증·저장(실패 400, 카운트 없음 — 인증기 오류이지 대입이 아니다).
     *
     * @param pending 세션의 대기(없으면 null) — 성공·실패와 무관하게 호출자가 세션에서 지운다
     */
    public PasskeySummary confirmRegistration(CustomUserDetails principal, PasskeyRegistration pending, String registrationId,
                                              String credentialJson, String label, String currentPassword,
                                              String requestIp, String requestUri) {
        requireEnabled();
        String normalizedLabel = normalizeLabel(label);
        LocalDateTime now = LocalDateTime.now(clock);
        if (pending == null || !pending.matches(principal.getId(), registrationId, now)) {
            throw new ConflictException(REGISTRATION_EXPIRED);
        }
        PublicKeyCredential<AuthenticatorAttestationResponse> credential;
        try {
            credential = passkeyJson.readAttestation(credentialJson);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(VERIFICATION_FAILED);
        }
        PasskeySummary[] result = new PasskeySummary[1];
        Verdict verdict = transactionTemplate.execute(status -> {
            Member member = memberRepository.findByIdForUpdate(principal.getId()).orElse(null);
            if (member == null || !stillAuthorized(member, principal)) {
                return Verdict.STATE_CHANGED;
            }
            MemberTotp totp = memberTotpRepository.findById(member.getId()).orElse(null);
            if (totp == null) {
                return Verdict.TOTP_REQUIRED;
            }
            if (!Objects.equals(totp.getEnabledAt(), pending.totpEnabledAt())) {
                return Verdict.EXPIRED;
            }
            if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, member.getPwd())) {
                // 2단계·본인 재인증 계열 — member_totp.failed_attempts에 센다(MemberTotpService.disable과 같다)
                if (totp.recordFailureAndCheckThreshold()) {
                    loginFailureService.lockForSecondFactorFailures(member, requestIp, requestUri);
                }
                return Verdict.BAD_PASSWORD;
            }
            if (passkeyRepository.countValidByMemberId(member.getId()) >= MemberPasskey.MAX_PER_MEMBER) {
                return Verdict.LIMIT_REACHED;
            }
            CredentialRecord record;
            try {
                record = relyingParty.register(pending.options(), credential, normalizedLabel);
            } catch (RuntimeException e) {
                // 서명·origin·챌린지 불일치, 중복 자격 등 — 세지 않는다. 지금까지의 쓰기는 없다(비밀번호가 맞았으므로 카운터 무변경)
                log.info("패스키 등록 검증 실패 (memberId={}, type={})", member.getId(), e.getClass().getName());
                status.setRollbackOnly();
                return Verdict.VERIFICATION_FAILED;
            }
            passkeyRepository.flush();
            MemberPasskey saved = passkeyRepository.findByCredentialId(record.getCredentialId().getBytes())
                    .orElseThrow(() -> new IllegalStateException("등록한 패스키를 다시 읽지 못했다"));
            result[0] = PasskeySummary.of(saved);
            eventPublisher.publishEvent(new MemberTotpAuditEvent(AdminActionTypes.PASSKEY_REGISTER, member.getId(), member.getUserId(),
                    member.getId(), requestIp, requestUri, "POST"));
            return Verdict.OK;
        });
        throwUnlessOk(verdict);
        return result[0];
    }

    /** 본인 패스키 하나를 지운다(재인증 없음 — 지워도 TOTP 기준선 아래로 약해지지 않는다). 내 것이 아니면 404. */
    public void delete(CustomUserDetails principal, Long passkeyId, String requestIp, String requestUri) {
        Verdict verdict = transactionTemplate.execute(status -> {
            Member member = memberRepository.findByIdForUpdate(principal.getId()).orElse(null);
            if (member == null || !stillAuthorized(member, principal)) {
                return Verdict.STATE_CHANGED;
            }
            MemberPasskey passkey = passkeyRepository.findById(passkeyId)
                    .filter(found -> Objects.equals(found.getMemberId(), member.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("패스키를 찾을 수 없습니다."));
            passkeyRepository.delete(passkey);
            eventPublisher.publishEvent(new MemberTotpAuditEvent(AdminActionTypes.PASSKEY_DELETE, member.getId(), member.getUserId(),
                    member.getId(), requestIp, requestUri, "DELETE"));
            return Verdict.OK;
        });
        throwUnlessOk(verdict);
    }

    /**
     * 기동 정리 — 유효 조건을 어긴 행(현재 TOTP가 없거나 그 등록보다 먼저 만든 것)을 회원마다 행 잠금 트랜잭션에서 지운다.
     * 이전 앱으로 롤백한 동안 앱 밖에서 일어난 TOTP 해제·재등록의 찌꺼기다(결정 1). 지운 행 수를 돌려준다.
     */
    public int deleteStalePasskeys() {
        int deleted = 0;
        for (Long memberId : passkeyRepository.findMemberIdsWithStalePasskeys()) {
            Integer count = transactionTemplate.execute(status -> {
                memberRepository.findByIdForUpdate(memberId);
                return passkeyRepository.deleteStaleByMemberId(memberId);
            });
            deleted += count == null ? 0 : count;
        }
        return deleted;
    }

    static String normalizeLabel(String label) {
        String trimmed = label == null ? "" : label.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints == 0 || codePoints > MemberPasskey.LABEL_MAX_CODE_POINTS
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidRequestException("패스키 이름은 1~" + MemberPasskey.LABEL_MAX_CODE_POINTS + "자로 입력해 주세요.");
        }
        return trimmed;
    }

    private void requireEnabled() {
        if (!relyingParty.isEnabled()) {
            throw new ResourceNotFoundException("패스키 기능이 꺼져 있습니다.");
        }
    }

    /** 잠금 직후 재확인 — ACTIVE이고 관리자 역할이며 요청 주체의 역할과 같은가({@code MemberTotpService}와 같은 판정). */
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
            case STATE_CHANGED -> throw new ConflictException(STATE_CHANGED);
            case TOTP_REQUIRED -> throw new ConflictException(TOTP_REQUIRED);
            case LIMIT_REACHED -> throw new ConflictException(LIMIT_REACHED);
            case EXPIRED -> throw new ConflictException(REGISTRATION_EXPIRED);
            case BAD_PASSWORD -> throw new InvalidRequestException(BAD_PASSWORD);
            case VERIFICATION_FAILED -> throw new InvalidRequestException(VERIFICATION_FAILED);
        }
    }
}
