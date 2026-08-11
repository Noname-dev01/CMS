package com.cms.admin.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileImageUrlsTest {

    private Member memberWith(Long id, ProfileImageKind kind, String url) {
        return Member.builder()
                .id(id)
                .profileImageKind(kind)
                .profileImageUrl(url)
                .build();
    }

    @Test
    @DisplayName("kind=NONE이면 self/target 둘 다 null을 반환한다")
    void none_returnsNull() {
        Member member = memberWith(1L, ProfileImageKind.NONE, null);

        assertNull(ProfileImageUrls.resolveSelfUrl(member));
        assertNull(ProfileImageUrls.resolveTargetUrl(1L, member));
    }

    @Test
    @DisplayName("kind=PRESET이면 원값을 그대로 pass-through한다")
    void preset_passesThroughRawValue() {
        Member member = memberWith(1L, ProfileImageKind.PRESET, "/img/undraw_profile_1.svg");

        assertEquals("/img/undraw_profile_1.svg", ProfileImageUrls.resolveSelfUrl(member));
        assertEquals("/img/undraw_profile_1.svg", ProfileImageUrls.resolveTargetUrl(1L, member));
    }

    @Test
    @DisplayName("kind=LEGACY_INLINE이면 원값(data URI)을 그대로 pass-through한다")
    void legacyInline_passesThroughRawValue() {
        String dataUri = "data:image/png;base64,AAAA";
        Member member = memberWith(1L, ProfileImageKind.LEGACY_INLINE, dataUri);

        assertEquals(dataUri, ProfileImageUrls.resolveSelfUrl(member));
    }

    @Test
    @DisplayName("kind=UPLOADED이면 self는 /me/ 라우트, target은 /{id}/ 라우트를 반환하고 버전 쿼리 파라미터가 붙는다")
    void uploaded_buildsDownloadRouteWithVersionToken() {
        Member member = memberWith(7L, ProfileImageKind.UPLOADED, "2026/08/10/uuid.png");

        String selfUrl = ProfileImageUrls.resolveSelfUrl(member);
        String targetUrl = ProfileImageUrls.resolveTargetUrl(7L, member);

        assertTrue(selfUrl.startsWith("/admin/api/members/me/profile-image?v="));
        assertTrue(targetUrl.startsWith("/admin/api/members/7/profile-image?v="));
        // storageKey 원문이 URL에 노출되면 안 된다.
        assertTrue(!selfUrl.contains("uuid.png"));
    }

    @Test
    @DisplayName("storageKey가 다르면 버전 토큰도 달라진다(캐시 버스팅)")
    void uploaded_differentStorageKey_differentVersionToken() {
        Member before = memberWith(1L, ProfileImageKind.UPLOADED, "2026/08/10/before.png");
        Member after = memberWith(1L, ProfileImageKind.UPLOADED, "2026/08/10/after.png");

        assertNotEquals(ProfileImageUrls.resolveSelfUrl(before), ProfileImageUrls.resolveSelfUrl(after));
    }

    @Test
    @DisplayName("같은 storageKey면 버전 토큰도 항상 같다(결정론적)")
    void uploaded_sameStorageKey_sameVersionToken() {
        Member a = memberWith(1L, ProfileImageKind.UPLOADED, "2026/08/10/same.png");
        Member b = memberWith(2L, ProfileImageKind.UPLOADED, "2026/08/10/same.png");

        String tokenA = ProfileImageUrls.resolveSelfUrl(a).substring(ProfileImageUrls.resolveSelfUrl(a).indexOf("?v="));
        String tokenB = ProfileImageUrls.resolveSelfUrl(b).substring(ProfileImageUrls.resolveSelfUrl(b).indexOf("?v="));

        assertEquals(tokenA, tokenB);
    }
}
