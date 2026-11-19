package com.cms.admin.member.passkey;

import com.cms.admin.member.domain.MemberPasskey;
import com.cms.admin.member.domain.MemberPasskeyUser;
import com.cms.admin.member.repository.MemberPasskeyRepository;
import com.cms.admin.member.repository.MemberPasskeyUserRepository;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring WebAuthn 자격 저장소의 JPA 어댑터(PLAN-passkey-second-factor.md 결정 3).
 *
 * <ul>
 *   <li>{@link #findByUserId}는 <b>유효한</b> 자격(현재 TOTP 등록 이후에 만든 것)만 돌려준다 — 어설션 옵션의 {@code allowCredentials}와
 *       등록 옵션의 {@code excludeCredentials}가 이것을 쓴다(결정 1).</li>
 *   <li>{@link #save}는 같은 자격 ID가 있으면 인증 결과(서명 횟수·백업 상태·사용 시각)만 갱신하고, 없으면 등록으로 넣는다.
 *       시각은 라이브러리의 {@code Instant.now()}가 아니라 KST {@code Clock}이다.</li>
 *   <li>모든 호출은 그 회원의 {@code member} 행 잠금 트랜잭션 안에서 일어난다 — 그래서 인증 중 삭제가 끼어 지운 자격이 다시 들어가지 않는다.</li>
 * </ul>
 */
class PasskeyCredentialRepository implements UserCredentialRepository {

    private final MemberPasskeyUserRepository userRepository;
    private final MemberPasskeyRepository passkeyRepository;
    private final Clock clock;

    PasskeyCredentialRepository(MemberPasskeyUserRepository userRepository, MemberPasskeyRepository passkeyRepository, Clock clock) {
        this.userRepository = userRepository;
        this.passkeyRepository = passkeyRepository;
        this.clock = clock;
    }

    @Override
    public void delete(Bytes credentialId) {
        passkeyRepository.findByCredentialId(credentialId.getBytes()).ifPresent(passkeyRepository::delete);
    }

    @Override
    public void save(CredentialRecord record) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<MemberPasskey> existing = passkeyRepository.findByCredentialId(record.getCredentialId().getBytes());
        if (existing.isPresent()) {
            MemberPasskey passkey = existing.get();
            // 라이브러리(7.0.7)는 등록 당시 횟수와만 비교한다 — 저장된 최신 값으로 다시 본다. 둘 다 0이면 횟수를 구현하지 않는 인증기
            // (WebAuthn L3 §6.1.1). 역행은 복제 의심이지만 정상 어설션의 도착 순서 역전으로도 생기므로 호출자는 실패로 세지 않는다
            // (PLAN-passkey-login.md 결정 8).
            long submitted = record.getSignatureCount();
            long stored = passkey.getSignatureCount();
            if ((submitted != 0 || stored != 0) && submitted <= stored) {
                throw new PasskeySignatureCountException("패스키 서명 횟수 역행 (passkeyId=" + passkey.getId() + ")");
            }
            passkey.recordUse(submitted, record.isBackupState(), now);
            return;
        }
        MemberPasskeyUser user = userRepository.findByUserHandle(record.getUserEntityUserId().getBytes())
                .orElseThrow(() -> new IllegalStateException("패스키 등록 대상 user handle 없음"));
        if (record.getAttestationObject() == null || record.getAttestationClientDataJSON() == null) {
            throw new IllegalStateException("패스키 등록 기록에 attestation 정보가 없다");
        }
        passkeyRepository.save(MemberPasskey.registration()
                .memberId(user.getMemberId())
                .credentialId(record.getCredentialId().getBytes())
                .publicKey(record.getPublicKey().getBytes())
                .signatureCount(record.getSignatureCount())
                .uvInitialized(record.isUvInitialized())
                .backupEligible(record.isBackupEligible())
                .backupState(record.isBackupState())
                .transports(joinTransports(record.getTransports()))
                .attestationObject(record.getAttestationObject().getBytes())
                .attestationClientDataJson(record.getAttestationClientDataJSON().getBytes())
                .label(record.getLabel())
                .createdAt(now)
                .build());
    }

    @Override
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return passkeyRepository.findByCredentialId(credentialId.getBytes())
                .flatMap(passkey -> userRepository.findById(passkey.getMemberId()).map(user -> toRecord(passkey, user)))
                .orElse(null);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return userRepository.findByUserHandle(userId.getBytes())
                .map(user -> passkeyRepository.findValidByMemberId(user.getMemberId()).stream()
                        .map(passkey -> toRecord(passkey, user))
                        .toList())
                .orElse(List.of());
    }

    private CredentialRecord toRecord(MemberPasskey passkey, MemberPasskeyUser user) {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(new Bytes(passkey.getCredentialId()))
                .userEntityUserId(new Bytes(user.getUserHandle()))
                .publicKey(new ImmutablePublicKeyCose(passkey.getPublicKey()))
                .signatureCount(passkey.getSignatureCount())
                .uvInitialized(passkey.isUvInitialized())
                .transports(splitTransports(passkey.getTransports()))
                .backupEligible(passkey.isBackupEligible())
                .backupState(passkey.isBackupState())
                .attestationObject(new Bytes(passkey.getAttestationObject()))
                .attestationClientDataJSON(new Bytes(passkey.getAttestationClientDataJson()))
                .label(passkey.getLabel())
                .created(toInstant(passkey.getCreatedAt()))
                .lastUsed(passkey.getLastUsedAt() == null ? null : toInstant(passkey.getLastUsedAt()))
                .build();
    }

    private Instant toInstant(LocalDateTime time) {
        return time.atZone(clock.getZone()).toInstant();
    }

    private static String joinTransports(Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            return "";
        }
        String joined = transports.stream().map(AuthenticatorTransport::getValue).sorted().collect(Collectors.joining(","));
        return joined.length() > 255 ? "" : joined;
    }

    private static Set<AuthenticatorTransport> splitTransports(String transports) {
        if (transports == null || transports.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(transports.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
