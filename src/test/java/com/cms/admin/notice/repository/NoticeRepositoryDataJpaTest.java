package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers가 띄우는 일회용 MariaDB를 사용하는 JPA 슬라이스 테스트({@link MariaDbContainerSupport}).
 * 소프트 삭제 필터·검색 조합·락 조회 메서드의 실제 동작을 검증한다.
 * 정렬 변환 로직 자체(순수 단위)는 NoticeRepositoryImplSortTest가 담당한다.
 *
 * <p>@DataJpaTest는 각 테스트를 트랜잭션으로 감싸고 종료 시 롤백하므로
 * 실DB에 데이터가 남지 않는다(VisitLogRepositoryDataJpaTest와 동일 원칙).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class NoticeRepositoryDataJpaTest extends MariaDbContainerSupport {

    @Autowired
    NoticeRepository noticeRepository;

    private Notice saveNotice(String title, String content, boolean useYn, boolean deleted) {
        LocalDateTime now = LocalDateTime.now();
        return noticeRepository.save(Notice.builder()
                .title(title)
                .content(content)
                .useYn(useYn)
                .deleted(deleted)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
    }

    @Test
    @DisplayName("notice 테이블이 V8 마이그레이션으로 생성되어 저장이 성공한다")
    void notice_tableCreated_saveSucceeds() {
        Notice saved = saveNotice("DataJpaTest 저장 확인", "본문", true, false);

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("삭제된 공지는 searchNotices 결과에서 제외된다 (keyword·useYn 필터 무관)")
    void searchNotices_excludesDeleted() {
        String marker = "삭제필터검증" + System.nanoTime();
        saveNotice(marker + "-active", "본문", true, false);
        saveNotice(marker + "-deleted", "본문", true, true);

        Page<Notice> all = noticeRepository.searchNotices(
                NoticeSearchRequest.builder().keyword(marker).build(), PageRequest.of(0, 20));

        assertThat(all.getContent()).hasSize(1);
        assertThat(all.getContent().get(0).getTitle()).endsWith("-active");
    }

    @Test
    @DisplayName("삭제된 공지는 useYn 필터 조합에서도 제외된다")
    void searchNotices_excludesDeleted_withUseYnFilter() {
        String marker = "삭제필터useYn" + System.nanoTime();
        saveNotice(marker + "-active-true", "본문", true, false);
        saveNotice(marker + "-deleted-true", "본문", true, true);

        Page<Notice> result = noticeRepository.searchNotices(
                NoticeSearchRequest.builder().keyword(marker).useYn(true).build(), PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).endsWith("-active-true");
    }

    @Test
    @DisplayName("keyword는 제목에 부분 일치(contains)로 검색된다")
    void searchNotices_keywordContains() {
        String marker = "부분일치검증" + System.nanoTime();
        saveNotice(marker + "-공지A", "본문", true, false);
        saveNotice("다른제목" + System.nanoTime(), "본문", true, false);

        Page<Notice> result = noticeRepository.searchNotices(
                NoticeSearchRequest.builder().keyword(marker).build(), PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("findByIdAndDeletedFalse는 삭제된 공지를 반환하지 않는다")
    void findByIdAndDeletedFalse_excludesDeleted() {
        Notice deleted = saveNotice("삭제조회검증" + System.nanoTime(), "본문", true, true);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalse(deleted.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndDeletedFalse는 삭제되지 않은 공지를 반환한다")
    void findByIdAndDeletedFalse_returnsActive() {
        Notice active = saveNotice("활성조회검증" + System.nanoTime(), "본문", true, false);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalse(active.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(active.getId());
    }

    @Test
    @DisplayName("findByIdAndDeletedFalseForUpdate는 삭제된 공지를 반환하지 않는다 (락 조회도 삭제 필터가 적용됨)")
    void findByIdAndDeletedFalseForUpdate_excludesDeleted() {
        Notice deleted = saveNotice("락삭제조회검증" + System.nanoTime(), "본문", true, true);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalseForUpdate(deleted.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndDeletedFalseForUpdate는 삭제되지 않은 공지를 락과 함께 반환한다")
    void findByIdAndDeletedFalseForUpdate_returnsActive() {
        Notice active = saveNotice("락활성조회검증" + System.nanoTime(), "본문", true, false);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalseForUpdate(active.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(active.getId());
    }

    @Test
    @DisplayName("findByDeletedFalseAndUseYnTrue는 삭제되었거나 비노출인 공지를 제외한다 (공개 목록 전용)")
    void findByDeletedFalseAndUseYnTrue_excludesDeletedAndHidden() {
        String marker = "공개목록필터검증" + System.nanoTime();
        Notice published = saveNotice(marker + "-published", "본문", true, false);
        saveNotice(marker + "-hidden", "본문", false, false);
        saveNotice(marker + "-deleted", "본문", true, true);

        Page<Notice> result = noticeRepository.findByDeletedFalseAndUseYnTrue(
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("id"))));

        assertThat(result.getContent())
                .extracting(Notice::getId)
                .contains(published.getId());
        assertThat(result.getContent())
                .extracting(Notice::getTitle)
                .noneMatch(title -> title.endsWith("-hidden") || title.endsWith("-deleted"));
    }

    @Test
    @DisplayName("findByIdAndDeletedFalseAndUseYnTrue는 비노출 공지를 반환하지 않는다")
    void findByIdAndDeletedFalseAndUseYnTrue_excludesHidden() {
        Notice hidden = saveNotice("공개상세비노출검증" + System.nanoTime(), "본문", false, false);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(hidden.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndDeletedFalseAndUseYnTrue는 삭제된 공지를 반환하지 않는다")
    void findByIdAndDeletedFalseAndUseYnTrue_excludesDeleted() {
        Notice deleted = saveNotice("공개상세삭제검증" + System.nanoTime(), "본문", true, true);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(deleted.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndDeletedFalseAndUseYnTrue는 노출·미삭제 공지를 반환한다")
    void findByIdAndDeletedFalseAndUseYnTrue_returnsPublished() {
        Notice published = saveNotice("공개상세노출검증" + System.nanoTime(), "본문", true, false);

        Optional<Notice> result = noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(published.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(published.getId());
    }

    @Test
    @DisplayName("findByDeletedFalseAndUseYnTrue는 동률 createDate에서 id desc로 tie-break한다")
    void findByDeletedFalseAndUseYnTrue_tieBreaksById() {
        String marker = "타이브레이크검증" + System.nanoTime();
        LocalDateTime sameInstant = LocalDateTime.now();
        Notice first = noticeRepository.save(Notice.builder()
                .title(marker + "-first").content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(sameInstant).updateDate(sameInstant).build());
        Notice second = noticeRepository.save(Notice.builder()
                .title(marker + "-second").content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(sameInstant).updateDate(sameInstant).build());

        Page<Notice> result = noticeRepository.findByDeletedFalseAndUseYnTrue(
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id"))));

        int firstIndex = indexOfId(result, first.getId());
        int secondIndex = indexOfId(result, second.getId());
        assertThat(firstIndex).isGreaterThan(secondIndex);
    }

    // ===================== searchPublishedByTitle (공개 목록 제목 검색 — PLAN-public-notice-search.md 쟁점 2·3·7) =====================
    // 컨테이너 DB는 JVM 단위로 공유되므로, 총건수 단언은 고유 마커가 든 키워드로 다른 테스트 데이터와 분리한다.

    private static final PageRequest PUBLIC_PAGE = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));

    @Test
    @DisplayName("searchPublishedByTitle은 목록·COUNT 모두 노출·미삭제 공지만 센다 — 비공개 일치 공지가 총건수·페이지 수로 새지 않는다")
    void searchPublishedByTitle_countAndPagesExcludeHiddenAndDeleted() {
        String marker = "공개검색카운트" + System.nanoTime();
        for (int i = 0; i < 11; i++) {
            saveNotice(marker + "-공개" + i, "본문", true, false);
        }
        saveNotice(marker + "-비노출", "본문", false, false);
        saveNotice(marker + "-삭제", "본문", true, true);
        saveNotice("불일치" + System.nanoTime(), "본문", true, false);

        Page<Notice> result = noticeRepository.searchPublishedByTitle(marker, PUBLIC_PAGE);

        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getContent()).hasSize(10)
                .extracting(Notice::getTitle)
                .allMatch(title -> title.startsWith(marker + "-공개"));
    }

    @Test
    @DisplayName("searchPublishedByTitle은 비노출·삭제 공지만 일치하면 총건수 0·페이지 0이다")
    void searchPublishedByTitle_onlyPrivateMatches_isEmpty() {
        String marker = "공개검색비공개만" + System.nanoTime();
        saveNotice(marker + "-비노출", "본문", false, false);
        saveNotice(marker + "-삭제", "본문", true, true);

        Page<Notice> result = noticeRepository.searchPublishedByTitle(marker, PUBLIC_PAGE);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("searchPublishedByTitle에서 %·_·! 는 와일드카드가 아니라 문자 그대로 매칭된다")
    void searchPublishedByTitle_likeSpecialCharactersAreLiteral() {
        String marker = "와일드카드" + System.nanoTime();
        Notice percent = saveNotice(marker + "-a%b", "본문", true, false);
        saveNotice(marker + "-aXYb", "본문", true, false);
        Notice underscore = saveNotice(marker + "-c_d", "본문", true, false);
        saveNotice(marker + "-cZd", "본문", true, false);
        Notice bang = saveNotice(marker + "-e!f", "본문", true, false);

        assertThat(noticeRepository.searchPublishedByTitle(marker + "-a%b", PUBLIC_PAGE).getContent())
                .extracting(Notice::getId).containsExactly(percent.getId());
        assertThat(noticeRepository.searchPublishedByTitle(marker + "-c_d", PUBLIC_PAGE).getContent())
                .extracting(Notice::getId).containsExactly(underscore.getId());
        assertThat(noticeRepository.searchPublishedByTitle(marker + "-e!f", PUBLIC_PAGE).getContent())
                .extracting(Notice::getId).containsExactly(bang.getId());
        assertThat(noticeRepository.searchPublishedByTitle(marker + "-%", PUBLIC_PAGE).getContent()).isEmpty();
    }

    @Test
    @DisplayName("searchPublishedByTitle은 콜레이션(utf8mb4_general_ci)에 따라 대소문자를 구분하지 않는다")
    void searchPublishedByTitle_caseInsensitiveByCollation() {
        String marker = "대소문자" + System.nanoTime();
        Notice upper = saveNotice(marker + "-NOTICE", "본문", true, false);

        assertThat(noticeRepository.searchPublishedByTitle(marker + "-notice", PUBLIC_PAGE).getContent())
                .extracting(Notice::getId).containsExactly(upper.getId());
    }

    @Test
    @DisplayName("searchPublishedByTitle은 createDate desc, 동률이면 id desc로 정렬한다")
    void searchPublishedByTitle_sortedByCreateDateThenIdDesc() {
        String marker = "검색정렬" + System.nanoTime();
        LocalDateTime base = LocalDateTime.now();
        Notice older = noticeRepository.save(Notice.builder()
                .title(marker + "-older").content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(base.minusDays(1)).updateDate(base).build());
        Notice tieFirst = noticeRepository.save(Notice.builder()
                .title(marker + "-tie1").content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(base).updateDate(base).build());
        Notice tieSecond = noticeRepository.save(Notice.builder()
                .title(marker + "-tie2").content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(base).updateDate(base).build());

        assertThat(noticeRepository.searchPublishedByTitle(marker, PUBLIC_PAGE).getContent())
                .extracting(Notice::getId)
                .containsExactly(tieSecond.getId(), tieFirst.getId(), older.getId());
    }

    private int indexOfId(Page<Notice> page, Long id) {
        return page.getContent().stream().map(Notice::getId).toList().indexOf(id);
    }
}
