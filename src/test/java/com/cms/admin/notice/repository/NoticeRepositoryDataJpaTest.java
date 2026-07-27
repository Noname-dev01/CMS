package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
}
