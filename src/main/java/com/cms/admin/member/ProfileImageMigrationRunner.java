package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.ProfileImageValidator;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로필 이미지 Base64-in-DB → FileStorage 1회성 이관.
 * 설계 결정 전문은 adversarial-review/plan/PLAN-profile-image-storage.md(v6 — 적대적 리뷰
 * 5라운드) 쟁점 6 참조.
 *
 * <p>{@code @Profile} 제한이 없다 — {@code AdminBootstrapLoader}와 달리 dev DB도 named
 * volume으로 영속되어 실사용 데이터가 있을 수 있고, prod도 대상이기 때문이다.
 *
 * <p><b>실패 정책(사용자 결정)</b>: 행 단위로 격리해 한 행의 실패가 다른 행 처리나 앱 기동을
 * 막지 않는다. 일반 실패(화이트리스트 밖 MIME·손상된 Base64·malformed data URI)는
 * {@link ProfileImageKind#LEGACY_INLINE}으로 남아 기존처럼 pass-through 렌더링된다.
 * 단, 크기 상한을 초과한 값은 예외적으로 pass-through하지 않고 {@link ProfileImageKind#NONE}으로
 * 안전하게 초기화한다(반복 조회마다 거대 페이로드가 나가는 자체 DoS를 막기 위함).
 *
 * <p><b>멱등성</b>: 별도 "이관 완료" 플래그 없이, 이관 성공 시 kind가 LEGACY_INLINE→UPLOADED로
 * 바뀌므로 조회 조건 자체가 재실행 시 자연히 대상에서 제외한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageMigrationRunner implements CommandLineRunner {

    private static final String PROFILE_IMAGE_NAMESPACE = "profile";

    /**
     * 문자열 길이 상한(디코딩 전 사전 검사) — 업로드 2MB(2,097,152 bytes) 원본 기준 Base64
     * 팽창(ceil(n/3)*4 ≈ 1.37배) + {@code data:image/xxx;base64,} 접두어를 감안해 여유 있게 산정.
     * DB 스칼라 쿼리(length())로 엔티티를 읽지 않고 먼저 걸러낸다(쟁점 6 — OOM 방지).
     */
    private static final int MAX_ENCODED_LENGTH = 2_900_000;

    /** 디코딩 후 재검증 상한(방어적 이중 검사 — 과거 다른 상한으로 저장됐을 가능성 대비). */
    private static final int MAX_DECODED_BYTES = 2 * 1024 * 1024;

    private static final Pattern DATA_URI_PATTERN =
            Pattern.compile("^data:([^;]+);base64,(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final MemberRepository memberRepository;
    private final FileStorage fileStorage;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(String... args) {
        List<Long> targetIds = memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE);

        int migrated = 0;
        int skipped = 0;
        int oversized = 0;
        int failed = 0;

        for (Long id : targetIds) {
            try {
                switch (processOne(id)) {
                    case MIGRATED -> migrated++;
                    case OVERSIZED -> oversized++;
                    case SKIPPED -> skipped++;
                }
            } catch (Exception e) {
                failed++;
                log.error("프로필 이미지 이관 중 예상치 못한 오류 — 원값 유지(pass-through). memberId={}", id, e);
            }
        }

        log.info("프로필 이미지 1회성 이관 완료. 대상={}, 이관={}, 크기초과초기화={}, 스킵={}, 실패={}",
                targetIds.size(), migrated, oversized, skipped, failed);
    }

    private enum Result { MIGRATED, OVERSIZED, SKIPPED }

    private Result processOne(Long id) {
        // 1) 엔티티를 전혀 읽지 않는 조건부 벌크 UPDATE로 크기 초과 행을 먼저 걸러낸다.
        //    DB가 CHAR_LENGTH 조건을 서버 사이드에서 평가하고 애플리케이션은 영향 행 수만 받는다.
        Integer resetCount = transactionTemplate.execute(status ->
                memberRepository.resetIfOversizedLegacyImage(id, MAX_ENCODED_LENGTH));
        if (resetCount != null && resetCount > 0) {
            return Result.OVERSIZED;
        }

        boolean migrated = Boolean.TRUE.equals(transactionTemplate.execute(status -> migrateWithinTransaction(id)));
        return migrated ? Result.MIGRATED : Result.SKIPPED;
    }

    /**
     * 행 잠금과 함께 재조회해 그 사이 다른 요청(온라인 업로드·초기화·프리셋 전환)이 먼저
     * 처리했는지 재검증한다 — {@code findByIdForUpdate}가 이미 이 목적으로 존재하는 메서드를
     * 재사용하는 것뿐이다(쟁점 6).
     */
    private boolean migrateWithinTransaction(Long id) {
        Member member = memberRepository.findByIdForUpdate(id).orElse(null);
        if (member == null || member.getProfileImageKind() != ProfileImageKind.LEGACY_INLINE) {
            return false;
        }

        String rawValue = member.getProfileImageUrl();
        Matcher matcher = DATA_URI_PATTERN.matcher(rawValue == null ? "" : rawValue);
        if (!matcher.matches()) {
            // data: 형태가 아닌 잔여값(V11 catch-all로 분류된 손상값 등) — 이관 대상이 아니다.
            return false;
        }

        String declaredMime = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException e) {
            log.warn("프로필 이미지 이관 스킵(손상된 Base64) — 원값 유지. memberId={}", id);
            return false;
        }
        if (decoded.length > MAX_DECODED_BYTES) {
            // 사전 문자열 길이 검사를 통과했지만 디코딩 후에도 여전히 큰 경우의 방어적 이중 검사.
            // 이미 트랜잭션 안에서 엔티티를 들고 있으므로 그대로 NONE으로 초기화한다.
            member.resetProfileImage(LocalDateTime.now());
            log.warn("프로필 이미지 이관 중 크기 초과 발견(디코딩 후) — NONE으로 초기화. memberId={}", id);
            return false;
        }

        try {
            ProfileImageValidator.validate(decoded, declaredMime);
        } catch (InvalidRequestException e) {
            log.warn("프로필 이미지 이관 스킵(검증 실패: {}) — 원값 유지. memberId={}", e.getMessage(), id);
            return false;
        }

        String storageKey = fileStorage.store(decoded, "legacy." + extensionFor(declaredMime), PROFILE_IMAGE_NAMESPACE);
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey, PROFILE_IMAGE_NAMESPACE,
                "memberId=" + id);

        member.migrateProfileImageToStorage(storageKey, declaredMime);
        return true;
    }

    private String extensionFor(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }
}
