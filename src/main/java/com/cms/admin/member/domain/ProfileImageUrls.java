package com.cms.admin.member.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@code Member.profileImageUrl}(원값)을 브라우저가 실제로 쓸 수 있는 src 문자열로 변환한다.
 * {@code AdminMemberService.toResponse()}(REST 응답)와 {@code AdminSecurityService}(탑바,
 * com.cms.config.auth 패키지 — SecurityContext에 캐시된 Member 엔티티를 직접 읽는 완전히 다른
 * 코드 경로)가 둘 다 이 변환이 필요해 공유 유틸로 추출했다
 * (adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 4).
 *
 * <p>상태 없는 정적 유틸 — 도메인 엔티티(Member)에 URL 구성 메서드를 두지 않는 이유는 라우트
 * 경로 구성이 프레젠테이션 관심사이기 때문이다(엔티티는 도메인 상태·불변식만 책임진다는
 * 기존 컨벤션 유지).
 */
public final class ProfileImageUrls {

    private ProfileImageUrls() {
    }

    /**
     * 본인(SELF) 컨텍스트용 URL. {@code kind=UPLOADED}일 때만 다운로드 라우트를 가리키고,
     * 그 외(PRESET/LEGACY_INLINE)는 원값을 그대로 pass-through, NONE이면 null.
     */
    public static String resolveSelfUrl(Member member) {
        return resolve(member, "/admin/api/members/me/profile-image");
    }

    /**
     * 타 관리자(OTHER) 컨텍스트용 URL. {@code targetId}는 조회 대상 회원의 id다.
     */
    public static String resolveTargetUrl(Long targetId, Member member) {
        return resolve(member, "/admin/api/members/" + targetId + "/profile-image");
    }

    private static String resolve(Member member, String downloadBasePath) {
        if (member.getProfileImageKind() == ProfileImageKind.UPLOADED) {
            String token = versionToken(member.getProfileImageUrl());
            return downloadBasePath + "?v=" + token;
        }
        // PRESET/LEGACY_INLINE은 그대로 pass-through, NONE은 null(값 자체가 null이므로 그대로 반환).
        return member.getProfileImageUrl();
    }

    /**
     * storageKey가 바뀔 때마다(교체·초기화·프리셋 전환) 값이 반드시 달라지는 캐시 버스팅
     * 토큰이다. {@code updateDate}(이름·이메일 등과 공유되는 범용 필드) 대신 storageKey 자체를
     * 해시한다 — storageKey는 매 저장마다 새 UUID를 포함하므로 이미지가 실제로 바뀔 때만,
     * 그리고 항상 값이 달라진다. storageKey 원문은 노출하지 않는다(서버 내부 경로 비노출
     * 원칙 — 다른 첨부파일 DTO들과 동일, 쟁점 5).
     */
    private static String versionToken(String storageKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(storageKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= 12) {
                    break;
                }
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            // JVM 표준 알고리즘이라 실제로는 발생하지 않는다 — 발생해도 캐시 버스팅이
            // 안 될 뿐 기능이 깨지지 않도록 storageKey 길이 기반 폴백을 쓴다.
            return Integer.toHexString(storageKey.hashCode());
        }
    }
}
