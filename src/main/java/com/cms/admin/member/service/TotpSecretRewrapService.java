package com.cms.admin.member.service;

import com.cms.admin.member.domain.MemberTotp;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.TotpSecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 2단계 인증 비밀값을 현재 키로 다시 암호화한다(PLAN-totp-key-rotation.md 결정 3) — 기동 때 {@link TotpSecretRewrapRunner}가 부른다.
 *
 * <p>대상은 <b>현재 키만으로 복호되지 않는 행</b>이다({@code v1}에는 키ID가 없어 문자열로 키를 판별할 수 없다). 행마다 독립 트랜잭션에서
 * {@code member} 행을 잠그고 {@code member_totp}를 다시 읽어 바꾼다 — 로그인 2단계와 같은 잠금으로 직렬화되고,
 * {@code MemberTotp}에 {@code @DynamicUpdate}가 없어 잠금 전에 읽은 엔티티를 저장하면 쓴 스텝·실패 횟수가 되돌아가기 때문이다.
 * 어느 키로도 풀리지 않는 행은 바꾸지 않는다. 한 행의 실패(커밋 실패 포함)는 집계만 하고 다음 행으로 간다.
 * 끝에 모든 행을 현재 키만으로 복호해 보는 검증을 한다 — 운영자의 교체 완료 기준이다(결정 5).
 */
@Slf4j
@Service
public class TotpSecretRewrapService {

    /** 점검 결과. {@code readableWithCurrentKey == total}이고 실패가 없으면 옛 키 설정을 지워도 된다. */
    public record RewrapReport(String currentKeyId, Optional<String> previousKeyId, int total, int rewrapped,
                               int unreadable, int errors, int readableWithCurrentKey, List<Long> unreadableWithCurrentKeyIds) {

        public boolean healthy() {
            return unreadable == 0 && errors == 0 && readableWithCurrentKey == total;
        }
    }

    private enum RowResult { REWRAPPED, SKIPPED, UNREADABLE }

    private final TotpSecretCipher cipher;
    private final MemberRepository memberRepository;
    private final MemberTotpRepository memberTotpRepository;
    private final TransactionTemplate transactionTemplate;

    public TotpSecretRewrapService(TotpSecretCipher cipher, MemberRepository memberRepository,
                                   MemberTotpRepository memberTotpRepository, PlatformTransactionManager transactionManager) {
        this.cipher = cipher;
        this.memberRepository = memberRepository;
        this.memberTotpRepository = memberTotpRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 조회 자체가 실패하면 예외가 그대로 나간다 — 최종 경계는 runner다. */
    public RewrapReport rewrapAll() {
        List<Long> targets = new ArrayList<>();
        for (MemberTotp totp : memberTotpRepository.findAll()) {
            if (!readableWithCurrentKey(totp)) {
                targets.add(totp.getMemberId());
            }
        }

        int rewrapped = 0;
        int unreadable = 0;
        int errors = 0;
        for (Long memberId : targets) {
            try {
                // 커밋 실패도 이 경계 안에서 잡는다 — 콜백 밖(execute의 commit)에서 던져진다
                RowResult result = transactionTemplate.execute(status -> rewrap(memberId));
                if (result == RowResult.REWRAPPED) {
                    rewrapped++;
                } else if (result == RowResult.UNREADABLE) {
                    unreadable++;
                    log.error("TOTP 비밀값을 현재·옛 키 어느 것으로도 복호할 수 없어 그대로 둡니다 (memberId={})", memberId);
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("TOTP 비밀값 재암호화 실패 — 이 행은 건너뜁니다 (memberId={}, 예외={})", memberId, e.getClass().getName());
            }
        }

        int total = 0;
        List<Long> notReadable = new ArrayList<>();
        for (MemberTotp totp : memberTotpRepository.findAll()) {
            total++;
            if (!readableWithCurrentKey(totp)) {
                notReadable.add(totp.getMemberId());
            }
        }
        if (!notReadable.isEmpty()) {
            log.error("현재 키만으로 복호되지 않는 TOTP 행이 있습니다 (memberIds={})", notReadable);
        }
        return new RewrapReport(cipher.currentKeyId(), cipher.previousKeyId(), total, rewrapped, unreadable, errors,
                total - notReadable.size(), List.copyOf(notReadable));
    }

    private RowResult rewrap(Long memberId) {
        memberRepository.findByIdForUpdate(memberId);
        Optional<MemberTotp> found = memberTotpRepository.findById(memberId);
        if (found.isEmpty() || readableWithCurrentKey(found.get())) {
            // 그 사이 해제됐거나 다른 경로(재등록·다른 기동)가 이미 현재 키로 바꿨다
            return RowResult.SKIPPED;
        }
        MemberTotp totp = found.get();
        byte[] plain;
        try {
            plain = cipher.decrypt(memberId, totp.getSecret());
        } catch (TotpSecretCipher.DecryptionException e) {
            return RowResult.UNREADABLE;
        }
        try {
            totp.replaceSecret(cipher.encrypt(memberId, plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        return RowResult.REWRAPPED;
    }

    private boolean readableWithCurrentKey(MemberTotp totp) {
        try {
            Arrays.fill(cipher.decryptWithCurrentKey(totp.getMemberId(), totp.getSecret()), (byte) 0);
            return true;
        } catch (TotpSecretCipher.DecryptionException e) {
            return false;
        }
    }
}
