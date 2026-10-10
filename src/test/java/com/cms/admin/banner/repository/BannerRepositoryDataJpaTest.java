package com.cms.admin.banner.repository;

import com.cms.admin.banner.domain.Banner;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import com.cms.config.QuerydslConfig;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 노출 조회의 실제 DB 동작(PLAN-public-home-banner.md 쟁점 7·R1-2) — {@code [start, end)} 경계, 노출 여부와의 AND, 정렬,
 * 그리고 <b>저장·flush·영속성 컨텍스트 초기화·재조회 후</b> 판정(메모리에 남은 엔티티가 아니라 DB에 저장된 값으로 판정한다).
 * {@code DisplayPeriod.isActive}와 같은 의미임을 같은 시각 열로 대조한다. {@code @DataJpaTest}는 롤백하므로 DB에 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class BannerRepositoryDataJpaTest extends MariaDbContainerSupport {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 10, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Autowired BannerRepository bannerRepository;
    @Autowired EntityManager em;

    private int keySeq;

    private Banner save(String title, boolean useYn, LocalDateTime start, LocalDateTime end, int ord) {
        Banner saved = bannerRepository.save(Banner.builder()
                .title(title).storageKey("test/" + System.nanoTime() + "-" + (++keySeq) + ".png").contentType("image/png").fileSize(10L)
                .displayStart(start).displayEnd(end).useYn(useYn).ord(ord)
                .createDate(START).updateDate(START).build());
        em.flush();
        em.clear();                       // 이후 조회는 DB에서 다시 읽는다
        return saved;
    }

    private List<String> displayableTitles(LocalDateTime now) {
        return bannerRepository.findDisplayable(now).stream().map(Banner::getTitle).toList();
    }

    @Test
    @DisplayName("경계: 시작 직전 ✗, 시작 시각 ✓, 종료 직전 ✓, 종료 시각 ✗ — DisplayPeriod.isActive와 같은 열로 대조")
    void boundaries_matchDisplayPeriod() {
        save("period", true, START, END, 0);

        for (LocalDateTime now : List.of(START.minusSeconds(1), START, START.plusSeconds(1), END.minusSeconds(1), END, END.plusSeconds(1))) {
            boolean expected = com.cms.common.display.DisplayPeriod.isActive(START, END, now);
            assertThat(displayableTitles(now).contains("period")).as("now=" + now).isEqualTo(expected);
        }
        assertThat(displayableTitles(START.minusSeconds(1))).doesNotContain("period");
        assertThat(displayableTitles(START)).contains("period");
        assertThat(displayableTitles(END.minusSeconds(1))).contains("period");
        assertThat(displayableTitles(END)).doesNotContain("period");
    }

    @Test
    @DisplayName("기간이 비어 있으면 열려 있다: null/null은 항상, 시작만 있으면 시작 이후, 종료만 있으면 종료 전까지")
    void openEndedPeriods() {
        save("always", true, null, null, 0);
        save("fromStart", true, START, null, 1);
        save("untilEnd", true, null, END, 2);

        assertThat(displayableTitles(START.minusDays(1))).contains("always", "untilEnd").doesNotContain("fromStart");
        assertThat(displayableTitles(END)).contains("always", "fromStart").doesNotContain("untilEnd");
    }

    @Test
    @DisplayName("노출 여부(useYn)와 기간은 AND — 기간 안이어도 비노출이면 나오지 않는다")
    void useYnAndPeriod() {
        save("hidden-in-period", false, START, END, 0);
        save("visible-in-period", true, START, END, 1);

        assertThat(displayableTitles(START.plusDays(1))).contains("visible-in-period").doesNotContain("hidden-in-period");
    }

    @Test
    @DisplayName("정렬은 ord 오름차순, 동률이면 id 오름차순")
    void orderedByOrdThenId() {
        Banner first = save("tie-a", true, null, null, 5);
        Banner second = save("tie-b", true, null, null, 5);
        save("front", true, null, null, 1);

        List<Banner> result = bannerRepository.findDisplayable(START).stream()
                .filter(b -> List.of("tie-a", "tie-b", "front").contains(b.getTitle())).toList();

        assertThat(result).extracting(Banner::getTitle).containsExactly("front", "tie-a", "tie-b");
        assertThat(result.get(1).getId()).isEqualTo(first.getId());
        assertThat(result.get(2).getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("findDisplayableById: 노출 중일 때만 반환하고 비노출·기간 외·없는 ID는 비어 있다")
    void findDisplayableById() {
        Banner active = save("active", true, START, END, 0);
        Banner hidden = save("hidden", false, START, END, 1);
        Banner scheduled = save("scheduled", true, END, null, 2);
        LocalDateTime now = START.plusDays(1);

        assertThat(bannerRepository.findDisplayableById(active.getId(), now)).isPresent();
        assertThat(bannerRepository.findDisplayableById(hidden.getId(), now)).isEmpty();
        assertThat(bannerRepository.findDisplayableById(scheduled.getId(), now)).isEmpty();
        assertThat(bannerRepository.findDisplayableById(active.getId(), END)).as("종료 시각").isEmpty();
        assertThat(bannerRepository.findDisplayableById(Long.MAX_VALUE, now)).isEmpty();
    }

    @Test
    @DisplayName("저장된 시각은 datetime(6)이라 분 단위로 절단된 값이 그대로 왕복한다(소수 초 손실·시작 앞당김 없음)")
    void periodRoundTripsAfterClear() {
        Banner saved = save("round-trip", true, LocalDateTime.of(2026, 10, 10, 9, 5), LocalDateTime.of(2026, 10, 10, 9, 6), 0);

        Banner reloaded = bannerRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getDisplayStart()).isEqualTo(LocalDateTime.of(2026, 10, 10, 9, 5));
        assertThat(reloaded.getDisplayEnd()).isEqualTo(LocalDateTime.of(2026, 10, 10, 9, 6));
        assertThat(bannerRepository.findDisplayableById(saved.getId(), LocalDateTime.of(2026, 10, 10, 9, 5, 59))).isPresent();
        assertThat(bannerRepository.findDisplayableById(saved.getId(), LocalDateTime.of(2026, 10, 10, 9, 6))).isEmpty();
    }

    @Test
    @DisplayName("findMaxOrd: 배너가 없으면 -1, 있으면 최대 ord")
    void findMaxOrd() {
        int before = bannerRepository.findMaxOrd();
        save("max", true, null, null, before + 7);

        assertThat(bannerRepository.findMaxOrd()).isEqualTo(before + 7);
    }
}
