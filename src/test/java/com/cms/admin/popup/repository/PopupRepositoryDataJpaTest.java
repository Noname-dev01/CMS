package com.cms.admin.popup.repository;

import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.popup.domain.Popup;
import com.cms.admin.popup.domain.PopupType;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 노출 조회의 실제 DB 동작(PLAN-popup.md 쟁점 6) — {@code [start, end)} 경계, 노출 여부와의 AND, 등록 순서 정렬, 건수 제한,
 * 이미지 조회의 종류(이미지형) 조건, 그리고 <b>저장·flush·영속성 컨텍스트 초기화·재조회 후</b> 판정(메모리에 남은 엔티티가 아니라 DB에 저장된
 * 값으로 판정한다). {@code DisplayPeriod.isActive}와 같은 의미임을 같은 시각 열로 대조한다. {@code @DataJpaTest}는 롤백하므로 DB에 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class PopupRepositoryDataJpaTest extends MariaDbContainerSupport {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 10, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final PageRequest ALL = PageRequest.of(0, 100);

    @Autowired PopupRepository popupRepository;
    @Autowired EntityManager em;

    private int keySeq;

    private Popup save(String title, PopupType type, boolean useYn, LocalDateTime start, LocalDateTime end) {
        return save(title, type, useYn, start, end, true);
    }

    /** @param withImage 이미지형에서 false면 이미지 연결(content_image_ref) 없이 저장한다 — 공개 조회에서 제외되어야 하는 불완전 팝업 */
    private Popup save(String title, PopupType type, boolean useYn, LocalDateTime start, LocalDateTime end, boolean withImage) {
        Popup.PopupBuilder builder = Popup.builder()
                .title(title).revision(0).popupType(type)
                .posX(10).posY(10).width(300)
                .displayStart(start).displayEnd(end).useYn(useYn)
                .createDate(START).updateDate(START);
        if (type == PopupType.HTML) {
            builder.contentHtml("<p>x</p>");
        }
        Popup saved = popupRepository.save(builder.build());
        if (type == PopupType.IMAGE && withImage) {
            ContentImage image = ContentImage.builder()
                    .storageKey("test/" + System.nanoTime() + "-" + (++keySeq) + ".png").contentType("image/png").fileSize(10L)
                    .uploaderId("t").scopeType("POPUP").namespace("popup").createDate(START).build();
            em.persist(image);
            em.persist(new ContentImageRef("POPUP", saved.getId(), image.getId()));
        }
        em.flush();
        em.clear();                       // 이후 조회는 DB에서 다시 읽는다
        return saved;
    }

    private Popup save(String title, boolean useYn, LocalDateTime start, LocalDateTime end) {
        return save(title, PopupType.IMAGE, useYn, start, end);
    }

    private List<String> displayableTitles(LocalDateTime now) {
        return popupRepository.findDisplayable(now, ALL).stream().map(Popup::getTitle).toList();
    }

    @Test
    @DisplayName("경계: 시작 직전 ✗, 시작 시각 ✓, 종료 직전 ✓, 종료 시각 ✗ — DisplayPeriod.isActive와 같은 열로 대조")
    void boundaries_matchDisplayPeriod() {
        save("period", true, START, END);

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
        save("always", true, null, null);
        save("fromStart", true, START, null);
        save("untilEnd", true, null, END);

        assertThat(displayableTitles(START.minusDays(1))).contains("always", "untilEnd").doesNotContain("fromStart");
        assertThat(displayableTitles(END)).contains("always", "fromStart").doesNotContain("untilEnd");
    }

    @Test
    @DisplayName("노출 여부(useYn)와 기간은 AND — 기간 안이어도 비노출이면 나오지 않는다")
    void useYnAndPeriod() {
        save("hidden-in-period", false, START, END);
        save("visible-in-period", true, START, END);

        assertThat(displayableTitles(START.plusDays(1))).contains("visible-in-period").doesNotContain("hidden-in-period");
    }

    @Test
    @DisplayName("정렬은 id 오름차순(등록 순서)이고, 건수 제한은 앞에서부터 자른다")
    void orderedByIdAndLimited() {
        Popup first = save("first", true, null, null);
        Popup second = save("second", PopupType.HTML, true, null, null);
        Popup third = save("third", true, null, null);

        List<Popup> all = popupRepository.findDisplayable(START, ALL).stream()
                .filter(p -> List.of("first", "second", "third").contains(p.getTitle())).toList();
        assertThat(all).extracting(Popup::getId).containsExactly(first.getId(), second.getId(), third.getId());

        // 이 시험이 만든 3건 중 앞의 것부터 남는다 — 제한은 전체 노출 목록에서 앞쪽을 자르므로 기존 행이 없는 격리된 롤백 트랜잭션 안에서만 정확하다
        List<Popup> limited = popupRepository.findDisplayable(START, PageRequest.of(0, 2));
        assertThat(limited).hasSize(2);
        assertThat(limited.get(0).getId()).isLessThan(limited.get(1).getId());
    }

    @Test
    @DisplayName("findDisplayableImageById: 노출 중인 이미지형일 때만 반환하고 비노출·기간 외·HTML형·없는 ID는 비어 있다")
    void findDisplayableImageById() {
        Popup active = save("active", true, START, END);
        Popup hidden = save("hidden", false, START, END);
        Popup scheduled = save("scheduled", true, END, null);
        Popup htmlType = save("html", PopupType.HTML, true, START, END);
        LocalDateTime now = START.plusDays(1);

        assertThat(popupRepository.findDisplayableImageById(active.getId(), now)).isPresent();
        assertThat(popupRepository.findDisplayableImageById(hidden.getId(), now)).isEmpty();
        assertThat(popupRepository.findDisplayableImageById(scheduled.getId(), now)).isEmpty();
        assertThat(popupRepository.findDisplayableImageById(htmlType.getId(), now)).as("HTML형은 이미지 요청에 행이 없는 것과 같다").isEmpty();
        assertThat(popupRepository.findDisplayableImageById(active.getId(), END)).as("종료 시각").isEmpty();
        assertThat(popupRepository.findDisplayableImageById(Long.MAX_VALUE, now)).isEmpty();
    }

    @Test
    @DisplayName("저장된 시각은 datetime(6)이라 분 단위로 절단된 값이 그대로 왕복한다(소수 초 손실·시작 앞당김 없음)")
    void periodRoundTripsAfterClear() {
        Popup saved = save("round-trip", true, LocalDateTime.of(2026, 10, 10, 9, 5), LocalDateTime.of(2026, 10, 10, 9, 6));

        Popup reloaded = popupRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getDisplayStart()).isEqualTo(LocalDateTime.of(2026, 10, 10, 9, 5));
        assertThat(reloaded.getDisplayEnd()).isEqualTo(LocalDateTime.of(2026, 10, 10, 9, 6));
        assertThat(popupRepository.findDisplayableImageById(saved.getId(), LocalDateTime.of(2026, 10, 10, 9, 5, 59))).isPresent();
        assertThat(popupRepository.findDisplayableImageById(saved.getId(), LocalDateTime.of(2026, 10, 10, 9, 6))).isEmpty();
    }

    @Test
    @DisplayName("replace는 revision을 +1 하고 종류·이미지 필드는 건드리지 않는다")
    void replaceBumpsRevisionOnly() {
        Popup saved = save("rev", true, null, null);
        Popup loaded = popupRepository.findById(saved.getId()).orElseThrow();

        loaded.replace("새 제목", "/boards/1", null, 20, 30, 400, null, null, null, true, START);
        em.flush();
        em.clear();

        Popup reloaded = popupRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRevision()).isEqualTo(1);
        assertThat(reloaded.getTitle()).isEqualTo("새 제목");
        assertThat(reloaded.getPopupType()).isEqualTo(PopupType.IMAGE);
    }

    @Test
    @DisplayName("적격성(HTML형이거나 이미지 연결이 있는 이미지형)은 LIMIT 전에 판정된다 — 이미지 연결 없는 이미지형이 슬롯을 차지하지 않고 HTML형은 사라지지 않는다(R3-2)")
    void eligibilityIsDecidedBeforeLimit() {
        for (int i = 0; i < 5; i++) {
            save("orphan" + i, PopupType.IMAGE, true, null, null, false);   // 가장 앞 id 5개가 모두 불완전
        }
        save("html", PopupType.HTML, true, null, null);
        Popup linked = save("linked", PopupType.IMAGE, true, null, null);

        List<String> shown = popupRepository.findDisplayable(START, PageRequest.of(0, 5)).stream().map(Popup::getTitle).toList();

        assertThat(shown).containsExactly("html", "linked");
        assertThat(popupRepository.findDisplayableImageById(linked.getId(), START)).isPresent();
    }
}
