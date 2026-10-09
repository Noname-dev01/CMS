package com.cms.publicweb.notice.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.publicweb.board.dto.PublicBoardListResult;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.publicweb.board.dto.PublicPostAttachmentRef;
import com.cms.publicweb.board.dto.PublicPostDetail;
import com.cms.publicweb.board.service.PublicBoardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 공개 공지 서비스는 공지 게시판({@code board_key='NOTICE'})을 찾아 {@link PublicBoardService}에 위임하는 얇은 어댑터다
 * (PLAN-notice-to-board.md 쟁점 8). 공개 불변식 자체의 시험은 {@code PublicBoardServiceTest}·{@code PublicBoardIntegrationTest}에 있고,
 * 여기서는 위임과 fail-closed(공지 게시판 없음 → 모든 메서드 empty)만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class PublicNoticeServiceTest {

    private static final Long NOTICE_BOARD_ID = 3L;

    @Mock
    BoardRepository boardRepository;

    @Mock
    PublicBoardService publicBoardService;

    @InjectMocks
    PublicNoticeService service;

    private void noticeBoardExists() {
        given(boardRepository.findIdByBoardKey(Board.NOTICE_KEY)).willReturn(Optional.of(NOTICE_BOARD_ID));
    }

    @Test
    @DisplayName("목록·상세·첨부 조회는 공지 게시판 ID로 게시판 서비스에 위임한다")
    void delegatesToBoardServiceWithNoticeBoardId() {
        noticeBoardExists();
        PublicBoardListResult list = new PublicBoardListResult(NOTICE_BOARD_ID, "공지사항", new PageImpl<>(List.of()), null);
        PublicPostDetail detail = PublicPostDetail.builder().id(1L).title("t").build();
        PublicPostAttachmentRef ref = new PublicPostAttachmentRef("f.pdf", "k");
        given(publicBoardService.getPublishedPosts(NOTICE_BOARD_ID, 2, "점검")).willReturn(Optional.of(list));
        given(publicBoardService.findPublishedPost(NOTICE_BOARD_ID, 1L)).willReturn(Optional.of(detail));
        given(publicBoardService.findPublishedAttachment(NOTICE_BOARD_ID, 1L, 5L)).willReturn(Optional.of(ref));

        assertSame(list, service.getPublishedNotices(2, "점검").orElseThrow());
        assertSame(detail, service.findPublishedNotice(1L).orElseThrow());
        assertSame(ref, service.findPublishedAttachment(1L, 5L).orElseThrow());
    }

    @Test
    @DisplayName("공지 게시판이 없거나 삭제됐으면(키 조회 empty) 목록·상세·첨부 모두 empty이고 게시판 서비스를 호출하지 않는다 — fail-closed 404")
    void noticeBoardMissing_everythingEmpty() {
        given(boardRepository.findIdByBoardKey(Board.NOTICE_KEY)).willReturn(Optional.empty());

        assertTrue(service.getPublishedNotices(0, null).isEmpty());
        assertTrue(service.findPublishedNotice(1L).isEmpty());
        assertTrue(service.findPublishedAttachment(1L, 5L).isEmpty());

        verifyNoInteractions(publicBoardService);
    }

    @Test
    @DisplayName("게시판 서비스가 empty(비공개 게시판 등)를 주면 그대로 empty — 빈 목록이나 예외로 번역하지 않는다")
    void boardServiceEmpty_propagatesEmpty() {
        noticeBoardExists();
        given(publicBoardService.getPublishedPosts(anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .willReturn(Optional.empty());

        assertTrue(service.getPublishedNotices(0, null).isEmpty());
    }

    @Test
    @DisplayName("파일 열기는 게시판 서비스에 그대로 위임하고 게시판 조회를 하지 않는다(트랜잭션 없는 단계)")
    void openAttachment_delegatesWithoutBoardLookup() {
        PublicPostAttachmentRef ref = new PublicPostAttachmentRef("f.pdf", "k");
        PublicPostAttachmentDownload download = new PublicPostAttachmentDownload("f.pdf", 3, new ByteArrayInputStream(new byte[3]));
        given(publicBoardService.openAttachment(ref)).willReturn(Optional.of(download));

        assertEquals(download, service.openAttachment(ref).orElseThrow());

        verify(publicBoardService).openAttachment(ref);
        verifyNoInteractions(boardRepository);
    }
}
