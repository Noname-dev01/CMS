package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.dto.request.PostCreateRequest;
import com.cms.admin.board.dto.request.PostSearchRequest;
import com.cms.admin.board.dto.request.PostUpdateRequest;
import com.cms.admin.board.dto.response.PostPageResponse;
import com.cms.admin.board.dto.response.PostResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 게시글 서비스 단위 시험. 옛 {@code NoticeServiceTest}를 이식했다(공지가 공지 게시판으로 흡수됨, PLAN-notice-to-board.md) — 본문 HTML 계약·
 * 부분 수정·소프트 삭제·첨부 잔존 409·목록 clamp 규칙은 공지와 같고, 게시글은 게시판 소속(행 잠금 조회가 {@code (postId, boardId)}) 검사가 더해진다.
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    static final Long BOARD_ID = 3L;

    @Mock
    PostRepository postRepository;

    @Mock
    PostAttachmentRepository postAttachmentRepository;

    @Mock
    BoardRepository boardRepository;

    @Mock
    AdminSecurityService adminSecurityService;

    @Mock
    ContentImageService contentImageService;

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), KST);

    @InjectMocks
    PostService postService;

    private Board board() {
        return Board.builder().id(BOARD_ID).name("공지사항").publicYn(true).attachmentYn(true).deleted(false).build();
    }

    private Post existingPost() {
        LocalDateTime now = LocalDateTime.now();
        return Post.builder()
                .id(1L)
                .boardId(BOARD_ID)
                .title("기존 제목")
                .content("<p>기존 본문</p>")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build();
    }

    /** 게시판이 살아 있다고 가정(수정·삭제·조회의 게시판 미삭제 확인). */
    private void boardAlive() {
        given(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).willReturn(Optional.of(board()));
    }

    private void givenSaveEchoesWithId() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        given(boardRepository.findByIdAndDeletedFalseForShare(BOARD_ID)).willReturn(Optional.of(board()));
        given(postRepository.save(any(Post.class))).willAnswer(invocation -> {
            Post p = invocation.getArgument(0);
            return Post.builder().id(7L).boardId(p.getBoardId()).title(p.getTitle()).content(p.getContent()).useYn(p.getUseYn())
                    .deleted(p.getDeleted()).authorId(p.getAuthorId()).createDate(p.getCreateDate())
                    .updateDate(p.getUpdateDate()).build();
        });
    }

    // ===================== createPost =====================

    @Test
    @DisplayName("생성 성공 시 author가 자동 채워지고 useYn 누락 시 true로 기본화되며 게시판 공유 잠금으로 확인한다")
    void createPost_success_authorAutoFilled_defaultUseYnTrue() {
        givenSaveEchoesWithId();

        PostResponse response = postService.createPost(BOARD_ID, PostCreateRequest.builder()
                .title("글 제목").content("<p>글 본문</p>").contentFormat("HTML").build());

        assertEquals("admin01", response.getAuthorId());
        assertTrue(response.getUseYn());
        verify(boardRepository).findByIdAndDeletedFalseForShare(BOARD_ID);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertEquals(BOARD_ID, captor.getValue().getBoardId());
        assertFalse(captor.getValue().getDeleted());
        // 저장 시각은 주입된 KST Clock에서 나오며 createDate == updateDate
        assertEquals(FIXED_NOW, captor.getValue().getCreateDate());
        assertEquals(FIXED_NOW, captor.getValue().getUpdateDate());
    }

    @Test
    @DisplayName("삭제됐거나 없는 게시판에는 생성할 수 없다 — 404이고 저장하지 않는다")
    void createPost_boardMissing_notFound() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        given(boardRepository.findByIdAndDeletedFalseForShare(BOARD_ID)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> postService.createPost(BOARD_ID,
                PostCreateRequest.builder().title("제목").content("<p>본문</p>").contentFormat("HTML").build()));
        verify(postRepository, never()).save(any());
    }

    // ===================== 본문 HTML 계약 (PLAN-html-editor.md) =====================

    @Test
    @DisplayName("생성 시 본문은 sanitize되어 저장되고 이미지 참조가 게시글 ID·게시판 출처로 교체된다")
    void createPost_sanitizesAndReplacesRefs() {
        givenSaveEchoesWithId();
        PostCreateRequest request = PostCreateRequest.builder()
                .title("제목")
                .content("<p onclick=\"x\">안녕<script>alert(1)</script><img src=\"/content-images/3\"><img src=\"https://evil.test/a.png\"></p>")
                .contentFormat("HTML")
                .build();

        postService.createPost(BOARD_ID, request);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertEquals("<p>안녕<img src=\"/content-images/3\"></p>", captor.getValue().getContent());
        verify(contentImageService).replaceRefs("POST", 7L, "BOARD", BOARD_ID, java.util.Set.of(3L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "TEXT", "html"})
    @DisplayName("형식 표식이 HTML이 아니면 400(배포 전 평문 편집 화면 차단, R2-2)")
    void createPost_missingFormat_invalidRequest(String format) {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        PostCreateRequest request = PostCreateRequest.builder()
                .title("제목").content("<h2>안내</h2>").contentFormat(format).build();

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> postService.createPost(BOARD_ID, request));
        assertTrue(e.getMessage().contains("새로고침"));
        verify(postRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<p><br></p>", "<p>&nbsp;</p>", "<script>x</script>", "<p><img src=\"https://evil.test/a.png\"></p>"})
    @DisplayName("보이는 텍스트도 이미지도 없는 본문은 400")
    void createPost_blankHtml_invalidRequest(String content) {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        PostCreateRequest request = PostCreateRequest.builder()
                .title("제목").content(content).contentFormat("HTML").build();

        assertThrows(InvalidRequestException.class, () -> postService.createPost(BOARD_ID, request));
    }

    @Test
    @DisplayName("이미지만 있는 본문은 허용된다")
    void createPost_imageOnly_allowed() {
        givenSaveEchoesWithId();
        PostCreateRequest request = PostCreateRequest.builder()
                .title("제목").content("<p><img src=\"/content-images/1\"></p>").contentFormat("HTML").build();

        postService.createPost(BOARD_ID, request);

        verify(postRepository).save(any(Post.class));
    }

    @Test
    @DisplayName("보이는 텍스트 10,001자는 허용, 10,002자는 400(공백도 정규화 없이 센다)")
    void createPost_textLengthBoundary() {
        givenSaveEchoesWithId();
        // "<p>" + 10,000자 + "</p>" = 10,000 + 블록 종료 1 = 10,001
        PostCreateRequest atLimit = PostCreateRequest.builder()
                .title("제목").content("<p>" + "가".repeat(10_000) + "</p>").contentFormat("HTML").build();
        postService.createPost(BOARD_ID, atLimit);

        PostCreateRequest overBySpaces = PostCreateRequest.builder()
                .title("제목").content("<p>A" + " ".repeat(9_999) + "B</p>").contentFormat("HTML").build();
        assertThrows(InvalidRequestException.class, () -> postService.createPost(BOARD_ID, overBySpaces));
    }

    @Test
    @DisplayName("정리된 HTML이 200,000바이트를 넘으면 400")
    void createPost_tooManyBytes_invalidRequest() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn("admin01");
        // 글자 수는 상한 안(1,000개 링크 × 1자)이지만 링크 마크업으로 바이트가 넘친다
        String links = "<a href=\"https://example.com/" + "x".repeat(150) + "\">a</a>";
        PostCreateRequest request = PostCreateRequest.builder()
                .title("제목").content("<p>" + links.repeat(1_000) + "</p>").contentFormat("HTML").build();

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> postService.createPost(BOARD_ID, request));
        assertTrue(e.getMessage().contains("서식"));
    }

    @Test
    @DisplayName("useYn만 바꾸는 PATCH는 형식 표식 없이 허용되고 참조를 건드리지 않는다")
    void updatePost_useYnOnly_noFormatNeeded() {
        Post target = existingPost();
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(target));

        postService.updatePost(BOARD_ID, 1L, PostUpdateRequest.builder().useYn(false).build());

        assertFalse(target.getUseYn());
        verify(contentImageService, never()).replaceRefs(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("본문 수정은 형식 표식이 필요하고, 성공 시 참조를 교체한다")
    void updatePost_content_requiresFormatAndReplacesRefs() {
        Post target = existingPost();
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(target));

        assertThrows(InvalidRequestException.class,
                () -> postService.updatePost(BOARD_ID, 1L, PostUpdateRequest.builder().content("<p>새 본문</p>").build()));

        postService.updatePost(BOARD_ID, 1L, PostUpdateRequest.builder()
                .content("<p>새 본문<img src=\"/content-images/9\"></p>").contentFormat("HTML").build());

        assertEquals("<p>새 본문<img src=\"/content-images/9\"></p>", target.getContent());
        verify(contentImageService).replaceRefs("POST", 1L, "BOARD", BOARD_ID, java.util.Set.of(9L));
    }

    @Test
    @DisplayName("응답 본문은 출력 시에도 sanitize된다(저장 경로 밖 데이터 방어)")
    void response_sanitizesStoredContent() {
        Post tainted = Post.builder().id(1L).boardId(BOARD_ID).title("t").content("<p>x<img src=x onerror=alert(1)></p><script>y</script>")
                .useYn(true).deleted(false).authorId("a").build();
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalse(1L, BOARD_ID)).willReturn(Optional.of(tainted));

        assertEquals("<p>x</p>", postService.getPost(BOARD_ID, 1L).getContent());
    }

    @Test
    @DisplayName("author를 확인할 수 없으면 AccessDeniedException")
    void createPost_authorNull_accessDenied() {
        given(adminSecurityService.getCurrentAdminUserId()).willReturn(null);

        PostCreateRequest request = PostCreateRequest.builder()
                .title("글 제목").content("<p>글 본문</p>").contentFormat("HTML").build();

        assertThrows(AccessDeniedException.class, () -> postService.createPost(BOARD_ID, request));
        verify(postRepository, never()).save(any());
    }

    // ===================== updatePost =====================

    @Test
    @DisplayName("수정 성공 — null 필드는 기존값 유지, 게시글 락 조회 메서드를 사용한다")
    void updatePost_success_partialUpdate_usesLockQuery() {
        Post target = existingPost();
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(target));

        PostResponse response = postService.updatePost(BOARD_ID, 1L, PostUpdateRequest.builder().title("변경된 제목").build());

        assertEquals("변경된 제목", response.getTitle());
        assertEquals("<p>기존 본문</p>", response.getContent());
        assertEquals(FIXED_NOW, target.getUpdateDate()); // 수정 시각은 주입된 KST Clock
        verify(postRepository).findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID);
    }

    @Test
    @DisplayName("값이 온 title이 공백이면 400 INVALID_REQUEST")
    void updatePost_blankTitle_invalidRequest() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(existingPost()));
        boardAlive();

        PostUpdateRequest request = PostUpdateRequest.builder().title("   ").build();

        assertThrows(InvalidRequestException.class, () -> postService.updatePost(BOARD_ID, 1L, request));
    }

    @Test
    @DisplayName("title·content·useYn 전체가 null인 PATCH는 400 INVALID_REQUEST — 락 조회보다 먼저 거부된다")
    void updatePost_allFieldsNull_invalidRequest() {
        PostUpdateRequest request = PostUpdateRequest.builder().build();

        assertThrows(InvalidRequestException.class, () -> postService.updatePost(BOARD_ID, 1L, request));
        verify(postRepository, never()).findByIdAndBoardIdAndDeletedFalseForUpdate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("존재하지 않거나 이미 삭제된(또는 다른 게시판의) 게시글 수정 시 404")
    void updatePost_notFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(99L, BOARD_ID)).willReturn(Optional.empty());

        PostUpdateRequest request = PostUpdateRequest.builder().title("변경").build();

        assertThrows(ResourceNotFoundException.class, () -> postService.updatePost(BOARD_ID, 99L, request));
    }

    @Test
    @DisplayName("삭제된 게시판의 게시글은 수정할 수 없다 — 404")
    void updatePost_boardDeleted_notFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(existingPost()));
        given(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> postService.updatePost(BOARD_ID, 1L, PostUpdateRequest.builder().title("변경").build()));
    }

    // ===================== deletePost =====================

    @Test
    @DisplayName("삭제 성공 — deleted=true로 전환되고 락 조회 메서드를 사용하며 응답을 반환한다")
    void deletePost_success_softDelete_usesLockQuery_returnsResponse() {
        Post target = existingPost();
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(target));

        PostResponse response = postService.deletePost(BOARD_ID, 1L);

        assertEquals(1L, response.getId());
        assertTrue(target.getDeleted());
        assertEquals(FIXED_NOW, target.getUpdateDate()); // 삭제 시각도 주입된 KST Clock
        verify(postRepository).findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID);
    }

    @Test
    @DisplayName("존재하지 않거나 이미 삭제된 게시글 삭제 시 404")
    void deletePost_notFound() {
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(99L, BOARD_ID)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> postService.deletePost(BOARD_ID, 99L));
    }

    @Test
    @DisplayName("첨부파일이 남아있으면 409 ConflictException — 오펀 방지(쟁점 14)")
    void deletePost_hasAttachments_conflict() {
        Post target = existingPost();
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(1L, BOARD_ID)).willReturn(Optional.of(target));
        given(postAttachmentRepository.countByPostId(1L)).willReturn(2L);

        assertThrows(ConflictException.class, () -> postService.deletePost(BOARD_ID, 1L));
        assertFalse(target.getDeleted());
    }

    // ===================== getPost =====================

    @Test
    @DisplayName("존재하지 않는 게시글 조회 시 404")
    void getPost_notFound() {
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalse(99L, BOARD_ID)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> postService.getPost(BOARD_ID, 99L));
    }

    @Test
    @DisplayName("삭제됐거나 없는 게시판의 게시글 조회는 404")
    void getPost_boardMissing_notFound() {
        given(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> postService.getPost(BOARD_ID, 1L));
    }

    @Test
    @DisplayName("게시글 상세 조회 성공 시 본문을 포함해 반환한다")
    void getPost_success() {
        boardAlive();
        given(postRepository.findByIdAndBoardIdAndDeletedFalse(1L, BOARD_ID)).willReturn(Optional.of(existingPost()));

        PostResponse response = postService.getPost(BOARD_ID, 1L);

        assertEquals("<p>기존 본문</p>", response.getContent());
    }

    // ===================== getPosts =====================

    @Test
    @DisplayName("목록 조회는 요약 DTO(본문 제외)로 매핑된다")
    void getPosts_summaryMapping() {
        boardAlive();
        Page<Post> page = new PageImpl<>(List.of(existingPost()), PageRequest.of(0, 20), 1);
        given(postRepository.searchPosts(any(), any(), any())).willReturn(page);

        PostPageResponse response = postService.getPosts(BOARD_ID, PostSearchRequest.builder().build(), PageRequest.of(0, 20));

        assertEquals(1, response.getContent().size());
        assertEquals("기존 제목", response.getContent().get(0).getTitle());
    }

    @Test
    @DisplayName("size가 100을 초과하면 100으로 clamp되어 Repository에 전달된다")
    void getPosts_sizeClamp_over100() {
        boardAlive();
        given(postRepository.searchPosts(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        Pageable requested = PageRequest.of(2, 200, Sort.by("title").ascending());
        postService.getPosts(BOARD_ID, PostSearchRequest.builder().build(), requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).searchPosts(any(), any(), captor.capture());

        Pageable effective = captor.getValue();
        assertEquals(100, effective.getPageSize());
        assertEquals(2, effective.getPageNumber());
        assertEquals(Sort.by("title").ascending(), effective.getSort());
    }

    @Test
    @DisplayName("size가 100 이하면 clamp 없이 원래 Pageable이 그대로 전달된다")
    void getPosts_sizeWithinLimit_unchanged() {
        boardAlive();
        given(postRepository.searchPosts(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        Pageable requested = PageRequest.of(0, 50);
        postService.getPosts(BOARD_ID, PostSearchRequest.builder().build(), requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).searchPosts(any(), any(), captor.capture());

        assertEquals(requested, captor.getValue());
    }

    @Test
    @DisplayName("목록은 조회할 게시판이 삭제됐거나 없으면 404이고 게시글 저장소를 조회하지 않는다")
    void getPosts_boardMissing_notFound() {
        given(boardRepository.findByIdAndDeletedFalse(BOARD_ID)).willReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> postService.getPosts(BOARD_ID, PostSearchRequest.builder().build(), PageRequest.of(0, 20)));
        verify(postRepository, never()).searchPosts(any(), any(), any());
    }
}
