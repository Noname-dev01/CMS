package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberPasskey;
import com.cms.admin.member.domain.MemberPasskeyUser;
import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.passkey.PasskeyCounterRegression;
import com.cms.admin.member.passkey.PasskeyJson;
import com.cms.admin.member.passkey.PasskeyRelyingParty;
import com.cms.admin.member.repository.MemberPasskeyRepository;
import com.cms.admin.member.repository.MemberPasskeyUserRepository;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 패스키 단독 로그인(비밀번호·2단계 생략)의 판정(PLAN-passkey-login.md 결정 2·3). 조정자는 트랜잭션이 없는 {@link PasskeyLoginFilter}다 —
 * 이 빈의 {@link #identify}·{@link #verify}와 {@code CustomUserDetailsService.loadUserByUsername}을 <b>각각 프록시를 거쳐</b> 부른다.
 * 세 단계를 묶는 메서드를 여기에 두지 않는다(같은 클래스 내부 호출은 {@code @Transactional}이 적용되지 않는다).
 *
 * <p>트랜잭션을 나누는 이유: MariaDB InnoDB {@code REPEATABLE READ}의 읽기 스냅샷은 첫 일반 읽기 때 만들어진다. 자격 조회를 잠금과 같은
 * 트랜잭션에서 먼저 하면 잠금 뒤에도 옛 {@code member_totp}를 읽고, {@code resetFailures()}의 전체 컬럼 UPDATE가 다른 요청이 올린 사용 스텝을
 * 되돌릴 수 있다. 그래서 {@link #verify}의 첫 DB 작업은 회원 행 잠금이다.
 *
 * <p>결과는 예외가 아니라 값이다 — 라이브러리·파싱 예외는 여기서 잡아 거부로 바꾼다. 실패 횟수는 세지 않는다(결정 5 — 서명은 추측할 수 없다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasskeyLoginService {

    /** 식별 결과 — 자격 행의 회원 아이디와 파싱한 어설션 */
    public record Identification(String userId, PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
    }

    private final MemberRepository memberRepository;
    private final MemberTotpRepository memberTotpRepository;
    private final MemberPasskeyRepository passkeyRepository;
    private final MemberPasskeyUserRepository passkeyUserRepository;
    private final PasskeyRelyingParty relyingParty;
    private final PasskeyJson passkeyJson;
    private final Clock clock;

    public boolean isEnabled() {
        return relyingParty.isEnabled();
    }

    /**
     * 로그인 화면 GET의 챌린지 — 기능 꺼짐이면 빈 값(화면에 버튼 없음). 세션의 현재 챌린지가 남은 수명 하한 이상이면 <b>그 객체를 그대로</b>
     * 돌려주고(같은 세션의 탭들이 나눠 쓴다 — PLAN-passkey-challenge-stale-tab.md 결정 1), 아니면 새로 발급한다. DB 접근 없음.
     * 호출자는 새로 발급된 값(넘긴 값과 다른 객체)일 때만 세션에 쓴다(결정 3 — 재사용 값을 다시 쓰면 그사이 소비된 챌린지가 되살아난다).
     *
     * @param current 세션의 현재 챌린지(없으면 null)
     */
    public Optional<PasskeyLoginState> resumeOrStart(PasskeyLoginState current) {
        if (!relyingParty.isEnabled()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (current != null && current.isReusable(now)) {
            return Optional.of(current);
        }
        return Optional.of(new PasskeyLoginState(UUID.randomUUID().toString(), relyingParty.discoverableRequestOptions(), now));
    }

    /**
     * 1단계 식별(잠금 없음): 어설션 파싱 → 응답 {@code userHandle} 필수(discoverable 로그인 — WebAuthn L3 §7.2 단계 6) → rawId의 자격 행 → 그 회원 아이디.
     * 하나라도 없으면 빈 값.
     */
    @Transactional(readOnly = true)
    public Optional<Identification> identify(String credentialJson) {
        PublicKeyCredential<AuthenticatorAssertionResponse> credential;
        try {
            credential = passkeyJson.readAssertion(credentialJson);
        } catch (RuntimeException e) {
            log.info("패스키 로그인 어설션 형식 오류 (type={})", e.getClass().getName());
            return Optional.empty();
        }
        if (credential == null || credential.getRawId() == null || credential.getResponse() == null
                || credential.getResponse().getUserHandle() == null) {
            return Optional.empty();
        }
        return passkeyRepository.findByCredentialId(credential.getRawId().getBytes())
                .flatMap(passkey -> memberRepository.findById(passkey.getMemberId()))
                .map(member -> new Identification(member.getUserId(), credential));
    }

    /**
     * 3단계 검증 — 첫 DB 작업이 회원 행 잠금이다. 성공하면 검증 때 읽은 TOTP 등록 시각(토큰에 실려 성공 처리기가 대조한다), 아니면 빈 값.
     * 성공은 2단계 실패 횟수를 0으로 한다(비밀번호 쪽 횟수 리셋은 성공 처리기의 재확인이 한다 — 폼 로그인과 같은 자리).
     */
    @Transactional
    public Optional<LocalDateTime> verify(CustomUserDetails principal, PublicKeyCredentialRequestOptions options,
                                          PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
        Optional<Member> locked = memberRepository.findByIdForUpdate(principal.getId());
        if (locked.isEmpty() || !principal.matchesCurrent(locked.get())) {
            return Optional.empty();
        }
        Member member = locked.get();
        Optional<MemberTotp> totp = memberTotpRepository.findById(member.getId());
        Optional<MemberPasskeyUser> handle = passkeyUserRepository.findById(member.getId());
        if (totp.isEmpty() || handle.isEmpty() || !relyingParty.isEnabled()
                || !Arrays.equals(credential.getResponse().getUserHandle().getBytes(), handle.get().getUserHandle())
                || !isValidPasskeyOf(credential, member, totp.get())) {
            return Optional.empty();
        }
        try {
            PublicKeyCredentialUserEntity entity = relyingParty.authenticate(options, credential);
            if (entity == null || !Arrays.equals(entity.getId().getBytes(), handle.get().getUserHandle())) {
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            if (PasskeyCounterRegression.matches(e)) {
                log.warn("패스키 로그인 서명 횟수 역행 — 거부 (memberId={})", member.getId());
            } else {
                log.info("패스키 로그인 검증 실패 (memberId={}, type={})", member.getId(), e.getClass().getName());
            }
            return Optional.empty();
        }
        totp.get().resetFailures();
        memberTotpRepository.flush();
        return Optional.of(totp.get().getEnabledAt());
    }

    /** 그 자격이 이 회원의 유효한 패스키(현재 TOTP 등록 이후에 만든 것)인가 — 잠금 뒤 읽기 */
    private boolean isValidPasskeyOf(PublicKeyCredential<AuthenticatorAssertionResponse> credential, Member member, MemberTotp totp) {
        Optional<MemberPasskey> row = passkeyRepository.findByCredentialId(credential.getRawId().getBytes());
        return row.isPresent()
                && Objects.equals(row.get().getMemberId(), member.getId())
                && !row.get().getCreatedAt().isBefore(totp.getEnabledAt());
    }
}
