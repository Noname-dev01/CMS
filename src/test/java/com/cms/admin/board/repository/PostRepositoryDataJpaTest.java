package com.cms.admin.board.repository;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.dto.request.PostSearchRequest;
import com.cms.admin.site.Site;
import com.cms.admin.site.SiteType;
import com.cms.common.display.DisplayPeriod;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers가 띄우는 일회용 MariaDB를 사용하는 JPA 슬라이스 테스트({@link MariaDbContainerSupport}). 옛 {@code NoticeRepositoryDataJpaTest}를
 * 이식했다(공지가 공지 게시판으로 흡수됨, PLAN-notice-to-board.md) — 소프트 삭제 필터·검색 조합·락 조회·공개 조회(노출·미삭제·게시판 소속)·
 * 공개 제목 검색(COUNT 누수·LIKE 이스케이프·콜레이션·정렬)의 실제 동작을 검증한다. 정렬 변환 로직 자체(순수 단위)는 {@code PostRepositoryImplSortTest}가 담당한다.
 *
 * <p>@DataJpaTest는 각 테스트를 트랜잭션으로 감싸고 종료 시 롤백하므로 실DB에 데이터가 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class PostRepositoryDataJpaTest extends MariaDbContainerSupport {

    /** 기간이 없는 글만 다루는 기존 시험의 판정 시각 — 아무 값이어도 결과가 같다(기간 NULL = 제한 없음). 기간 경계는 아래 별도 시험이 다룬다. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 12, 0);

    private static final PageRequest PUBLIC_PAGE = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));

    @Autowired
    PostRepository postRepository;

    @Autowired
    BoardRepository boardRepository;

    private Long boardId;
    private Long otherBoardId;

    @BeforeEach
    void createBoards() {
        boardId = boardRepository.save(Board.builder().name("시험 게시판").publicYn(true).attachmentYn(true).deleted(false).build()).getId();
        otherBoardId = boardRepository.save(Board.builder().name("다른 게시판").publicYn(true).attachmentYn(true).deleted(false).build()).getId();
    }

    private Post savePost(Long board, String title, boolean useYn, boolean deleted) {
        LocalDateTime now = LocalDateTime.now();
        return postRepository.save(Post.builder()
                .boardId(board)
                .title(title)
                .content("본문")
                .useYn(useYn)
                .deleted(deleted)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
    }

    private Post savePost(String title, boolean useYn, boolean deleted) {
        return savePost(boardId, title, useYn, deleted);
    }

    private Post savePostAt(String title, LocalDateTime createDate) {
        return postRepository.save(Post.builder()
                .boardId(boardId).title(title).content("본문").useYn(true).deleted(false)
                .authorId("admin01").createDate(createDate).updateDate(createDate).build());
    }

    @Test
    @DisplayName("post 테이블이 마이그레이션으로 생성되어 저장이 성공한다")
    void post_tableCreated_saveSucceeds() {
        assertThat(savePost("DataJpaTest 저장 확인", true, false).getId()).isNotNull();
    }

    @Test
    @DisplayName("삭제된 게시글은 searchPosts 결과에서 제외된다 (keyword·useYn 필터 무관)")
    void searchPosts_excludesDeleted() {
        String marker = "삭제필터검증" + System.nanoTime();
        savePost(marker + "-active", true, false);
        savePost(marker + "-deleted", true, true);

        Page<Post> all = postRepository.searchPosts(boardId, PostSearchRequest.builder().keyword(marker).build(), PageRequest.of(0, 20));

        assertThat(all.getContent()).hasSize(1);
        assertThat(all.getContent().get(0).getTitle()).endsWith("-active");
    }

    @Test
    @DisplayName("삭제된 게시글은 useYn 필터 조합에서도 제외된다")
    void searchPosts_excludesDeleted_withUseYnFilter() {
        String marker = "삭제필터useYn" + System.nanoTime();
        savePost(marker + "-active-true", true, false);
        savePost(marker + "-deleted-true", true, true);

        Page<Post> result = postRepository.searchPosts(boardId, PostSearchRequest.builder().keyword(marker).useYn(true).build(), PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).endsWith("-active-true");
    }

    @Test
    @DisplayName("keyword는 제목에 부분 일치(contains)로 검색되고, 다른 게시판의 일치 글은 섞이지 않는다")
    void searchPosts_keywordContains_andBoardScoped() {
        String marker = "부분일치검증" + System.nanoTime();
        savePost(marker + "-글A", true, false);
        savePost(otherBoardId, marker + "-타게시판", true, false);
        savePost("다른제목" + System.nanoTime(), true, false);

        Page<Post> result = postRepository.searchPosts(boardId, PostSearchRequest.builder().keyword(marker).build(), PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).endsWith("-글A");
    }

    @Test
    @DisplayName("findByIdAndBoardIdAndDeletedFalse는 삭제된 글·다른 게시판 글을 반환하지 않고 정상 글만 반환한다")
    void findByIdAndBoardIdAndDeletedFalse_filters() {
        Post active = savePost("활성조회검증" + System.nanoTime(), true, false);
        Post deleted = savePost("삭제조회검증" + System.nanoTime(), true, true);

        assertThat(postRepository.findByIdAndBoardIdAndDeletedFalse(deleted.getId(), boardId)).isEmpty();
        assertThat(postRepository.findByIdAndBoardIdAndDeletedFalse(active.getId(), otherBoardId)).isEmpty();
        Optional<Post> found = postRepository.findByIdAndBoardIdAndDeletedFalse(active.getId(), boardId);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(active.getId());
    }

    @Test
    @DisplayName("findByIdAndBoardIdAndDeletedFalseForUpdate는 삭제된 글·다른 게시판 글을 반환하지 않고 정상 글은 락과 함께 반환한다")
    void findByIdAndBoardIdAndDeletedFalseForUpdate_filters() {
        Post active = savePost("락활성조회검증" + System.nanoTime(), true, false);
        Post deleted = savePost("락삭제조회검증" + System.nanoTime(), true, true);

        assertThat(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(deleted.getId(), boardId)).isEmpty();
        assertThat(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(active.getId(), otherBoardId)).isEmpty();
        assertThat(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(active.getId(), boardId)).isPresent();
    }

    @Test
    @DisplayName("findPublishedById는 비노출·삭제·다른 게시판 글을 반환하지 않고 노출·미삭제 글만 반환한다 (공개 상세 전용)")
    void findPublished_filters() {
        Post hidden = savePost("공개상세비노출검증" + System.nanoTime(), false, false);
        Post deleted = savePost("공개상세삭제검증" + System.nanoTime(), true, true);
        Post published = savePost("공개상세노출검증" + System.nanoTime(), true, false);

        assertThat(postRepository.findPublishedById(hidden.getId(), boardId, NOW)).isEmpty();
        assertThat(postRepository.findPublishedById(deleted.getId(), boardId, NOW)).isEmpty();
        assertThat(postRepository.findPublishedById(published.getId(), otherBoardId, NOW)).isEmpty();
        assertThat(postRepository.findPublishedById(published.getId(), boardId, NOW)).isPresent();
    }

    // ===================== searchPublished (공개 목록·제목 검색 — PLAN-public-notice-search.md 쟁점 2·3·7) =====================

    @Test
    @DisplayName("searchPublished(검색어 없음)는 노출·미삭제 글만, 그 게시판 글만 반환하고 동률 createDate는 id desc로 tie-break한다")
    void searchPublished_noKeyword_filtersAndTieBreaks() {
        String marker = "공개목록필터검증" + System.nanoTime();
        LocalDateTime sameInstant = LocalDateTime.now();
        Post first = savePostAt(marker + "-first", sameInstant);
        Post second = savePostAt(marker + "-second", sameInstant);
        savePost(marker + "-hidden", false, false);
        savePost(marker + "-deleted", true, true);
        savePost(otherBoardId, marker + "-other", true, false);

        Page<Post> result = postRepository.searchPublished(boardId, null, NOW, PageRequest.of(0, 50, PUBLIC_PAGE.getSort()));

        assertThat(result.getContent()).extracting(Post::getTitle)
                .noneMatch(title -> title.endsWith("-hidden") || title.endsWith("-deleted") || title.endsWith("-other"));
        java.util.List<Long> ids = result.getContent().stream().map(Post::getId).toList();
        assertThat(ids.indexOf(first.getId())).isGreaterThan(ids.indexOf(second.getId()));
    }

    @Test
    @DisplayName("searchPublished는 목록·COUNT 모두 노출·미삭제 글만 센다 — 비공개 일치 글이 총건수·페이지 수로 새지 않는다")
    void searchPublished_countAndPagesExcludeHiddenAndDeleted() {
        String marker = "공개검색카운트" + System.nanoTime();
        for (int i = 0; i < 11; i++) {
            savePost(marker + "-공개" + i, true, false);
        }
        savePost(marker + "-비노출", false, false);
        savePost(marker + "-삭제", true, true);
        savePost(otherBoardId, marker + "-타게시판", true, false);
        savePost("불일치" + System.nanoTime(), true, false);

        Page<Post> result = postRepository.searchPublished(boardId, marker, NOW, PUBLIC_PAGE);

        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getContent()).hasSize(10)
                .extracting(Post::getTitle)
                .allMatch(title -> title.startsWith(marker + "-공개"));
    }

    @Test
    @DisplayName("searchPublished는 비노출·삭제 글만 일치하면 총건수 0·페이지 0이다")
    void searchPublished_onlyPrivateMatches_isEmpty() {
        String marker = "공개검색비공개만" + System.nanoTime();
        savePost(marker + "-비노출", false, false);
        savePost(marker + "-삭제", true, true);

        Page<Post> result = postRepository.searchPublished(boardId, marker, NOW, PUBLIC_PAGE);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("searchPublished에서 %·_·! 는 와일드카드가 아니라 문자 그대로 매칭된다")
    void searchPublished_likeSpecialCharactersAreLiteral() {
        String marker = "와일드카드" + System.nanoTime();
        Post percent = savePost(marker + "-a%b", true, false);
        savePost(marker + "-aXYb", true, false);
        Post underscore = savePost(marker + "-c_d", true, false);
        savePost(marker + "-cZd", true, false);
        Post bang = savePost(marker + "-e!f", true, false);

        assertThat(postRepository.searchPublished(boardId, marker + "-a%b", NOW, PUBLIC_PAGE).getContent())
                .extracting(Post::getId).containsExactly(percent.getId());
        assertThat(postRepository.searchPublished(boardId, marker + "-c_d", NOW, PUBLIC_PAGE).getContent())
                .extracting(Post::getId).containsExactly(underscore.getId());
        assertThat(postRepository.searchPublished(boardId, marker + "-e!f", NOW, PUBLIC_PAGE).getContent())
                .extracting(Post::getId).containsExactly(bang.getId());
        assertThat(postRepository.searchPublished(boardId, marker + "-%", NOW, PUBLIC_PAGE).getContent()).isEmpty();
    }

    @Test
    @DisplayName("searchPublished는 콜레이션(utf8mb4_general_ci)에 따라 대소문자를 구분하지 않는다")
    void searchPublished_caseInsensitiveByCollation() {
        String marker = "대소문자" + System.nanoTime();
        Post upper = savePost(marker + "-NOTICE", true, false);

        assertThat(postRepository.searchPublished(boardId, marker + "-notice", NOW, PUBLIC_PAGE).getContent())
                .extracting(Post::getId).containsExactly(upper.getId());
    }

    @Test
    @DisplayName("searchPublished는 createDate desc, 동률이면 id desc로 정렬한다")
    void searchPublished_sortedByCreateDateThenIdDesc() {
        String marker = "검색정렬" + System.nanoTime();
        LocalDateTime base = LocalDateTime.now();
        Post older = savePostAt(marker + "-older", base.minusDays(1));
        Post tieFirst = savePostAt(marker + "-tie1", base);
        Post tieSecond = savePostAt(marker + "-tie2", base);

        assertThat(postRepository.searchPublished(boardId, marker, NOW, PUBLIC_PAGE).getContent())
                .extracting(Post::getId)
                .containsExactly(tieSecond.getId(), tieFirst.getId(), older.getId());
    }

    // ===== 공개 메인 최신 글 findLatestPublished (PLAN-public-home-banner.md 쟁점 12) =====

    @Test
    @DisplayName("findLatestPublished: 공개 조건(게시판 공개·미삭제 ∧ 게시글 노출·미삭제)을 만족하는 글만, 최신순 + id 보조 정렬, limit 적용")
    void findLatestPublished_invariantOrderAndLimit() {
        LocalDateTime base = LocalDateTime.now().withNano(0);
        Post older = savePostAt("older", base.minusDays(1));
        Post tieFirst = savePostAt("tie1", base);
        Post tieSecond = savePostAt("tie2", base);
        savePost("hidden", false, false);                     // 미노출
        savePost("deleted", true, true);                      // 삭제

        var rows = postRepository.findLatestPublished(boardId, 2L, false, NOW, 10);

        assertThat(rows).extracting(PublishedPostRow::id).containsExactly(tieSecond.getId(), tieFirst.getId(), older.getId());
        assertThat(rows).extracting(PublishedPostRow::boardName).containsOnly("시험 게시판");
        assertThat(postRepository.findLatestPublished(boardId, 2L, false, NOW, 2)).hasSize(2);
    }

    @Test
    @DisplayName("findLatestPublished: 비공개·삭제된 게시판의 글은 노출 글이어도 나오지 않는다(게시판 조건이 쿼리에 고정)")
    void findLatestPublished_excludesPrivateAndDeletedBoards() {
        Long privateBoard = boardRepository.save(Board.builder().name("비공개 게시판").publicYn(false).attachmentYn(true).deleted(false).build()).getId();
        Long deletedBoard = boardRepository.save(Board.builder().name("삭제된 게시판").publicYn(true).attachmentYn(true).deleted(true).build()).getId();
        savePost(privateBoard, "비공개 게시판 글", true, false);
        savePost(deletedBoard, "삭제된 게시판 글", true, false);
        Post visible = savePost(boardId, "공개 글", true, false);

        assertThat(postRepository.findLatestPublished(null, 2L, false, NOW, 100))
                .extracting(PublishedPostRow::id).contains(visible.getId());
        assertThat(postRepository.findLatestPublished(null, 2L, false, NOW, 100))
                .extracting(PublishedPostRow::title).doesNotContain("비공개 게시판 글", "삭제된 게시판 글");
        assertThat(postRepository.findLatestPublished(privateBoard, 2L, false, NOW, 10)).isEmpty();
        assertThat(postRepository.findLatestPublished(deletedBoard, 2L, false, NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("findLatestPublished: excludeSystemBoards면 시스템 게시판(공통·전용 공지)을 뺀다 — 새 글 섹션용")
    void findLatestPublished_excludeSystemBoards() {
        Post inBoard = savePost(boardId, "일반 게시판 글", true, false);
        Long commonNotice = boardRepository.findIdByBoardKey(Board.NOTICE_KEY).orElseThrow();   // V2가 시드한 공통 공지 게시판
        Long siteNotice = boardRepository.save(Board.builder().name("전용 공지").publicYn(true).attachmentYn(true).deleted(false).boardKey("NOTICE:2").siteId(2L).build()).getId();
        Post inCommonNotice = savePost(commonNotice, "공통 공지 글", true, false);
        Post inSiteNotice = savePost(siteNotice, "전용 공지 글", true, false);

        assertThat(postRepository.findLatestPublished(null, 2L, true, NOW, 100))
                .extracting(PublishedPostRow::id).contains(inBoard.getId()).doesNotContain(inCommonNotice.getId(), inSiteNotice.getId());
        assertThat(postRepository.findLatestPublished(null, 2L, false, NOW, 100))
                .extracting(PublishedPostRow::id).contains(inBoard.getId(), inCommonNotice.getId(), inSiteNotice.getId());
    }

    @Test
    @DisplayName("사이트 소속(PR ④): 공통 게시판은 모든 사이트에, 전용 게시판은 그 사이트에만 보이고 findPublicById도 같은 규칙이다")
    void siteScope_filtersLatestAndPublicById() {
        Site alpha = savePublicSite("board-alpha", true);
        Site beta = savePublicSite("board-beta", true);
        Long onlyAlphaBoard = boardRepository.save(Board.builder().name("알파 전용").publicYn(true).attachmentYn(true).deleted(false).siteId(alpha.getId()).build()).getId();
        Post common = savePost(boardId, "공통 글", true, false);
        Post onlyAlpha = savePost(onlyAlphaBoard, "알파 글", true, false);

        assertThat(postRepository.findLatestPublished(null, alpha.getId(), false, NOW, 100))
                .extracting(PublishedPostRow::id).contains(common.getId(), onlyAlpha.getId());
        assertThat(postRepository.findLatestPublished(null, beta.getId(), false, NOW, 100))
                .extracting(PublishedPostRow::id).contains(common.getId()).doesNotContain(onlyAlpha.getId());
        assertThat(postRepository.findLatestPublished(onlyAlphaBoard, beta.getId(), false, NOW, 100)).isEmpty();

        assertThat(boardRepository.findPublicById(onlyAlphaBoard, alpha.getId())).isPresent();
        assertThat(boardRepository.findPublicById(onlyAlphaBoard, beta.getId())).isEmpty();
        assertThat(boardRepository.findPublicById(boardId, beta.getId())).isPresent();
    }

    @Test
    @DisplayName("공지 게시판 해석(PR ④): 전용 공지 게시판이 있으면 전용, 없으면 공통이다")
    void findNoticeBoardId_siteThenCommon() {
        Site alpha = savePublicSite("notice-alpha", true);
        Site beta = savePublicSite("notice-beta", true);
        Long commonNotice = boardRepository.findIdByBoardKey(Board.NOTICE_KEY).orElseThrow();   // V2가 시드한 공통 공지 게시판
        Long alphaNotice = boardRepository.save(Board.builder().name("알파 공지").publicYn(true).attachmentYn(true).deleted(false)
                .boardKey(Board.noticeKeyFor(alpha.getId())).siteId(alpha.getId()).build()).getId();

        assertThat(boardRepository.findNoticeBoardId(alpha.getId())).contains(alphaNotice);
        assertThat(boardRepository.findNoticeBoardId(beta.getId())).contains(commonNotice);
    }

    @Test
    @DisplayName("existsBySiteId는 삭제된 행도 센다(FK RESTRICT와 같은 범위) — 소프트 삭제가 소속을 비우면 세지 않는다")
    void existsBySiteId_countsAllRows() {
        Site alpha = savePublicSite("exists-alpha", true);
        Board board = boardRepository.save(Board.builder().name("알파 전용").publicYn(true).attachmentYn(true).deleted(false).siteId(alpha.getId()).build());
        assertThat(boardRepository.existsBySiteId(alpha.getId())).isTrue();

        board.softDelete(NOW);
        boardRepository.saveAndFlush(board);

        assertThat(boardRepository.existsBySiteId(alpha.getId())).isFalse();
    }

    private Site savePublicSite(String key, boolean useYn) {
        Site site = Site.builder().siteKey(key).siteName(key).siteType(SiteType.PUBLIC)
                .allowExternalLink(false).useYn(useYn).defaultYn(false).createDate(NOW).updateDate(NOW).build();
        em.persist(site);
        em.flush();
        return site;
    }

    // ===================== 노출 기간 [publishStart, publishEnd) (PLAN-post-publish-period.md 쟁점 3·9) =====================
    // 공개 조회 네 표현(QueryDSL 목록·COUNT, QueryDSL 메인, JPQL 상세)이 DisplayPeriod.isActive와 같은 의미임을 경계값으로 대조한다.
    // 저장 → flush → 영속성 컨텍스트 초기화 → 재조회로 datetime(6) 왕복까지 포함한다.

    private static final LocalDateTime P_START = LocalDateTime.of(2026, 10, 12, 9, 0);
    private static final LocalDateTime P_END = LocalDateTime.of(2026, 10, 20, 0, 0);
    /** datetime(6)의 최소 단위 — "직전"을 마이크로초 하나 앞으로 잡는다(나노초는 DB에서 반올림될 수 있다). */
    private static final long MICRO = 1_000L;

    @Autowired
    jakarta.persistence.EntityManager em;

    private Post savePostWithPeriod(String title, boolean useYn, LocalDateTime start, LocalDateTime end) {
        LocalDateTime created = LocalDateTime.of(2026, 10, 1, 0, 0);
        Post saved = postRepository.save(Post.builder()
                .boardId(boardId).title(title).content("본문").useYn(useYn).deleted(false)
                .authorId("admin01").createDate(created).updateDate(created)
                .publishStart(start).publishEnd(end).build());
        em.flush();
        em.clear();
        return saved;
    }

    private static LocalDateTime[] boundaryNows() {
        return new LocalDateTime[]{
                P_START.minusNanos(MICRO), P_START, P_START.plusNanos(MICRO),
                P_END.minusNanos(MICRO), P_END, P_END.plusNanos(MICRO),
                LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2099, 12, 31, 23, 59)};
    }

    /** 한 글에 대해 네 공개 조회가 모두 {@code expected}와 같은 결과인지 단언한다. */
    private void assertPublishedEverywhere(Post post, String marker, LocalDateTime now, boolean expected) {
        String ctx = "now=" + now + " period=[" + post.getPublishStart() + ", " + post.getPublishEnd() + ")";

        assertThat(postRepository.findPublishedById(post.getId(), boardId, now).isPresent()).as("상세 " + ctx).isEqualTo(expected);

        Page<Post> list = postRepository.searchPublished(boardId, marker, now, PUBLIC_PAGE);
        assertThat(list.getContent().stream().map(Post::getId).toList().contains(post.getId())).as("목록 " + ctx).isEqualTo(expected);
        assertThat(list.getTotalElements()).as("COUNT " + ctx).isEqualTo(expected ? 1L : 0L);

        assertThat(postRepository.findLatestPublished(boardId, 2L, false, now, 100).stream().anyMatch(r -> r.id().equals(post.getId())))
                .as("메인 " + ctx).isEqualTo(expected);
    }

    @Test
    @DisplayName("기간 [start, end): 시작 직전·시작·종료 직전·종료 경계에서 상세·목록·COUNT·메인이 DisplayPeriod.isActive와 일치한다")
    void period_boundaries_matchIsActive() {
        String marker = "기간경계" + System.nanoTime();
        Post post = savePostWithPeriod(marker, true, P_START, P_END);

        for (LocalDateTime now : boundaryNows()) {
            assertPublishedEverywhere(post, marker, now, DisplayPeriod.isActive(P_START, P_END, now));
        }
    }

    @Test
    @DisplayName("기간 형태: 시작만·종료만·양끝 null 각각이 네 쿼리에서 isActive와 일치한다 (null = 그쪽 열림)")
    void period_openEnded_matchIsActive() {
        String marker = "기간열림" + System.nanoTime();
        Post onlyStart = savePostWithPeriod(marker + "-시작만", true, P_START, null);
        Post onlyEnd = savePostWithPeriod(marker + "-종료만", true, null, P_END);
        Post none = savePostWithPeriod(marker + "-없음", true, null, null);

        for (LocalDateTime now : boundaryNows()) {
            assertPublishedEverywhere(onlyStart, marker + "-시작만", now, DisplayPeriod.isActive(P_START, null, now));
            assertPublishedEverywhere(onlyEnd, marker + "-종료만", now, DisplayPeriod.isActive(null, P_END, now));
            assertPublishedEverywhere(none, marker + "-없음", now, true);
        }
    }

    @Test
    @DisplayName("useYn=false는 기간 안이어도 공개되지 않는다 (둘 다 만족해야 노출)")
    void period_useYnFalse_neverPublished() {
        String marker = "기간비노출" + System.nanoTime();
        Post post = savePostWithPeriod(marker, false, P_START, P_END);

        assertPublishedEverywhere(post, marker, P_START.plusDays(1), false);
        assertPublishedEverywhere(post, marker, P_START.minusDays(1), false);
    }

    @Test
    @DisplayName("COUNT는 기간 밖 글을 세지 않는다 — 예약 글의 존재가 총건수·페이지 수로 새지 않는다")
    void period_countExcludesScheduledAndExpired() {
        String marker = "기간COUNT" + System.nanoTime();
        LocalDateTime now = P_START.plusDays(1);
        savePostWithPeriod(marker + "-노출", true, P_START, P_END);
        savePostWithPeriod(marker + "-예약", true, now.plusDays(1), null);
        savePostWithPeriod(marker + "-만료", true, null, now.minusDays(1));

        Page<Post> result = postRepository.searchPublished(boardId, marker, now, PUBLIC_PAGE);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(Post::getTitle).containsExactly(marker + "-노출");
    }

    @Test
    @DisplayName("저장한 기간은 datetime(6)로 왕복한다 — 분 단위 값이 그대로 돌아온다")
    void period_roundTrip() {
        Post saved = savePostWithPeriod("기간왕복" + System.nanoTime(), true, P_START, P_END);

        Post reloaded = postRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getPublishStart()).isEqualTo(P_START);
        assertThat(reloaded.getPublishEnd()).isEqualTo(P_END);
    }
}
