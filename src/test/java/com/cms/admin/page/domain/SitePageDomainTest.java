package com.cms.admin.page.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitePageDomainTest {

    private static SitePage page(int revisionNo, int version, boolean use, boolean footer) {
        return SitePage.builder().id(1L).slug("terms").currentRevisionNo(revisionNo).version(version)
                .useYn(use).footerYn(footer).build();
    }

    static Stream<String> validSlugValues() {
        return Stream.of("ab", "terms", "privacy-policy", "a1", "0-9", "a-b-c", "x".repeat(50));
    }

    static Stream<String> invalidSlugValues() {
        return Stream.of("a", "-ab", "ab-", "a--b", "Ab", "a_b", "a b", "한글", "a/b", "a.b", "..", "x".repeat(51), "");
    }

    @ParameterizedTest
    @MethodSource("validSlugValues")
    @DisplayName("슬러그 허용: 소문자·숫자·하이픈 2~50자")
    void validSlugs(String slug) {
        assertThat(SiteSlug.isValid(slug)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidSlugValues")
    @DisplayName("슬러그 거부: 1자·처음/끝/연속 하이픈·대문자·밑줄·공백·한글·슬래시·점·51자·빈 값")
    void invalidSlugs(String slug) {
        assertThat(SiteSlug.isValid(slug)).isFalse();
    }

    @Test
    @DisplayName("슬러그 null은 거부, 예약어 4개는 형식은 유효하지만 예약어다")
    void nullAndReserved() {
        assertThat(SiteSlug.isValid(null)).isFalse();
        for (String reserved : new String[]{"revisions", "new", "edit", "index"}) {
            assertThat(SiteSlug.isValid(reserved)).isTrue();
            assertThat(SiteSlug.isReserved(reserved)).as(reserved).isTrue();
        }
        assertThat(SiteSlug.isReserved("terms")).isFalse();
    }

    @Test
    @DisplayName("changeFlags: 실제로 바뀔 때만 true — 같은 값이면 false")
    void changeFlags() {
        SitePage page = page(1, 1, true, false);

        assertThat(page.changeFlags(true, false)).isFalse();
        assertThat(page.changeFlags(false, false)).isTrue();
        assertThat(page.getUseYn()).isFalse();
        assertThat(page.changeFlags(false, true)).isTrue();
        assertThat(page.getFooterYn()).isTrue();
    }

    @Test
    @DisplayName("advanceRevision·markChanged: 개정 번호와 버전은 따로 오른다(메타만 바뀌면 버전만)")
    void revisionAndVersionAreIndependent() {
        SitePage page = page(3, 7, true, false);
        LocalDateTime now = LocalDateTime.of(2026, 10, 11, 12, 0);

        page.markChanged(now);
        assertThat(page.getCurrentRevisionNo()).isEqualTo(3);
        assertThat(page.getVersion()).isEqualTo(8);
        assertThat(page.getUpdateDate()).isEqualTo(now);

        assertThat(page.advanceRevision()).isEqualTo(4);
        assertThat(page.getVersion()).isEqualTo(8);
    }

    @Test
    @DisplayName("int 한계에서 증가는 쓰기 전에 ArithmeticException으로 실패하고 값은 그대로다(음수 개정이 저장되지 않는다)")
    void incrementOverflowFailsClosed() {
        SitePage page = page(Integer.MAX_VALUE, Integer.MAX_VALUE, true, false);

        assertThatThrownBy(page::advanceRevision).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> page.markChanged(LocalDateTime.now())).isInstanceOf(ArithmeticException.class);

        assertThat(page.getCurrentRevisionNo()).isEqualTo(Integer.MAX_VALUE);
        assertThat(page.getVersion()).isEqualTo(Integer.MAX_VALUE);
    }
}
