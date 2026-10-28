package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.dto.request.BoardUpdateRequest;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.permission.MemberBoardPermissionRepository;
import com.cms.admin.site.ContentSiteScope;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 게시판 서비스의 입력 검증 순서·version 비교 단위 시험(PLAN-board-definition.md PR 1). 통합 시험(BoardDisplaySettingsApiIntegrationTest)이
 * 결과를 고정하고, 여기서는 "400은 행 잠금 조회 전에 나간다"를 고정한다 — 잠금을 쥔 채 예외가 나가면 트랜잭션이 끝날 때까지 다른 요청이 막힌다.
 */
@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock BoardRepository boardRepository;
    @Mock PostRepository postRepository;
    @Mock MemberBoardPermissionRepository memberBoardPermissionRepository;
    @Mock ContentSiteScope contentSiteScope;
    @Mock ApplicationEventPublisher eventPublisher;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks BoardService service;

    private static Board board(int version) {
        return Board.builder().id(5L).name("자료실").publicYn(true).attachmentYn(true).deleted(false).version(version).build();
    }

    @Test
    @DisplayName("수정: version 누락·0·음수는 400이고 게시판 행을 조회(잠금)하지 않는다")
    void update_badVersion_rejectedBeforeLock() {
        for (Integer bad : new Integer[] {null, 0, -3}) {
            BoardUpdateRequest request = BoardUpdateRequest.builder().name("x").version(bad).build();
            assertThatThrownBy(() -> service.updateBoard(5L, request)).isInstanceOf(InvalidRequestException.class);
        }
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("수정: 범위 밖 목록 크기·배지 기간·긴 설명은 400이고 게시판 행을 조회(잠금)하지 않는다")
    void update_outOfRange_rejectedBeforeLock() {
        BoardUpdateRequest[] bad = {
                BoardUpdateRequest.builder().listPageSize(15).version(1).build(),
                BoardUpdateRequest.builder().newBadgeDays(31).version(1).build(),
                BoardUpdateRequest.builder().newBadgeDays(-1).version(1).build(),
                BoardUpdateRequest.builder().description("가".repeat(501)).version(1).build(),
                BoardUpdateRequest.builder().authorLabel("나".repeat(51)).version(1).build(),
        };
        for (BoardUpdateRequest request : bad) {
            assertThatThrownBy(() -> service.updateBoard(5L, request)).isInstanceOf(InvalidRequestException.class);
        }
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("수정: version만 보내면 변경할 필드가 없어 400이고 게시판 행을 조회하지 않는다")
    void update_versionOnly_rejected() {
        assertThatThrownBy(() -> service.updateBoard(5L, BoardUpdateRequest.builder().version(1).build()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("변경할 필드가 없습니다");
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("수정: 잠근 행의 version과 다르면 409이고 엔티티는 바뀌지 않는다, 같으면 1 증가")
    void update_versionCompare() {
        Board stale = board(3);
        given(boardRepository.findByIdAndDeletedFalseForUpdate(5L)).willReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.updateBoard(5L, BoardUpdateRequest.builder().name("새이름").version(2).build()))
                .isInstanceOf(ConflictException.class);
        assertThat(stale.getName()).isEqualTo("자료실");
        assertThat(stale.getVersion()).isEqualTo(3);

        service.updateBoard(5L, BoardUpdateRequest.builder().name("새이름").version(3).build());
        assertThat(stale.getName()).isEqualTo("새이름");
        assertThat(stale.getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("수정: 설명·표시명은 키 누락(null)이면 유지, 공백이면 삭제, 값이면 정리 저장 — 다른 표시 설정은 건드리지 않는다")
    void update_threeStateText() {
        Board board = Board.builder().id(5L).name("자료실").publicYn(true).attachmentYn(true).deleted(false).version(1)
                .description("옛 설명").authorLabel("옛 표시명").listPageSize(30).build();
        given(boardRepository.findByIdAndDeletedFalseForUpdate(5L)).willReturn(Optional.of(board));

        service.updateBoard(5L, BoardUpdateRequest.builder().name("개명").version(1).build());
        assertThat(board.getDescription()).isEqualTo("옛 설명");
        assertThat(board.getAuthorLabel()).isEqualTo("옛 표시명");
        assertThat(board.getListPageSize()).as("다른 표시 설정 유지").isEqualTo(30);

        service.updateBoard(5L, BoardUpdateRequest.builder().description("  새 설명  ").authorLabel("   ").version(2).build());
        assertThat(board.getDescription()).isEqualTo("새 설명");
        assertThat(board.getAuthorLabel()).as("공백만 = 삭제").isNull();
    }

    @Test
    @DisplayName("공통 공지 게시판의 쓰기 중지 요청은 400이고 사이트 전용 공지 게시판은 허용된다")
    void update_commonNoticeCannotStop() {
        Board common = Board.builder().id(1L).name("공지사항").publicYn(true).attachmentYn(true).deleted(false).boardKey(Board.NOTICE_KEY).version(1).build();
        given(boardRepository.findByIdAndDeletedFalseForUpdate(1L)).willReturn(Optional.of(common));
        assertThatThrownBy(() -> service.updateBoard(1L, BoardUpdateRequest.builder().writableYn(false).version(1).build()))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(common.getWritableYn()).isTrue();

        Board siteNotice = Board.builder().id(2L).name("전용공지").publicYn(true).attachmentYn(true).deleted(false)
                .boardKey(Board.noticeKeyFor(9L)).siteId(9L).version(1).build();
        given(boardRepository.findByIdAndDeletedFalseForUpdate(2L)).willReturn(Optional.of(siteNotice));
        service.updateBoard(2L, BoardUpdateRequest.builder().writableYn(false).version(1).build());
        assertThat(siteNotice.getWritableYn()).isFalse();
    }

    @Test
    @DisplayName("삭제: version 누락은 400(조회 전), 낡은 version은 409(삭제 안 됨)")
    void delete_versionChecks() {
        assertThatThrownBy(() -> service.deleteBoard(5L, null)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(boardRepository);

        Board board = board(2);
        given(boardRepository.findByIdAndDeletedFalseForUpdate(5L)).willReturn(Optional.of(board));
        assertThatThrownBy(() -> service.deleteBoard(5L, 1)).isInstanceOf(ConflictException.class);
        assertThat(board.getDeleted()).isFalse();
    }

    // ===================== 작성 정책 (PLAN-board-definition.md PR 2) =====================

    @Test
    @DisplayName("수정: 범위 밖 첨부 개수·크기, 빈·전역 밖 확장자는 400이고 게시판 행을 조회(잠금)하지 않는다")
    void update_writePolicyOutOfRange_rejectedBeforeLock() {
        BoardUpdateRequest[] bad = {
                BoardUpdateRequest.builder().attachMaxCount(0).version(1).build(),
                BoardUpdateRequest.builder().attachMaxCount(6).version(1).build(),
                BoardUpdateRequest.builder().attachMaxSizeMb(0).version(1).build(),
                BoardUpdateRequest.builder().attachMaxSizeMb(11).version(1).build(),
                BoardUpdateRequest.builder().attachExtensions(java.util.List.of()).version(1).build(),
                BoardUpdateRequest.builder().attachExtensions(java.util.List.of("pdf", "exe")).version(1).build(),
        };
        for (BoardUpdateRequest request : bad) {
            assertThatThrownBy(() -> service.updateBoard(5L, request)).isInstanceOf(InvalidRequestException.class);
        }
        verifyNoInteractions(boardRepository);
    }

    @Test
    @DisplayName("수정: 작성 정책 필드만 보내도 변경 있음으로 판정하고, 경계값(1·5개, 1·10MB)을 받는다")
    void update_writePolicyOnly_isAChange() {
        Board board = board(1);
        given(boardRepository.findByIdAndDeletedFalseForUpdate(5L)).willReturn(Optional.of(board));

        service.updateBoard(5L, BoardUpdateRequest.builder().attachMaxCount(1).attachMaxSizeMb(1).version(1).build());
        assertThat(board.getAttachMaxCount()).isEqualTo(1);
        assertThat(board.getAttachMaxSizeMb()).isEqualTo(1);
        assertThat(board.getVersion()).isEqualTo(2);

        service.updateBoard(5L, BoardUpdateRequest.builder().attachMaxCount(5).attachMaxSizeMb(10).version(2).build());
        assertThat(board.getAttachMaxCount()).isEqualTo(5);
        assertThat(board.getAttachMaxSizeMb()).isEqualTo(10);

        service.updateBoard(5L, BoardUpdateRequest.builder().bodyImageYn(false).version(3).build());
        assertThat(board.getBodyImageYn()).isFalse();
        service.updateBoard(5L, BoardUpdateRequest.builder().publishPeriodYn(false).version(4).build());
        assertThat(board.getPublishPeriodYn()).isFalse();
        assertThat(board.getBodyImageYn()).as("다른 정책은 유지").isFalse();
    }

    @Test
    @DisplayName("수정: 확장자는 누락이면 유지, 일부면 정규형 저장, 전체를 고르면 null(전체 허용)로 되돌린다")
    void update_extensionsThreeCases() {
        Board board = board(1);
        given(boardRepository.findByIdAndDeletedFalseForUpdate(5L)).willReturn(Optional.of(board));

        service.updateBoard(5L, BoardUpdateRequest.builder().attachExtensions(java.util.List.of("PDF", " pdf ", "hwp")).version(1).build());
        assertThat(board.getAttachExtensions()).isEqualTo("hwp,pdf");

        service.updateBoard(5L, BoardUpdateRequest.builder().name("개명").version(2).build());
        assertThat(board.getAttachExtensions()).as("누락 = 유지").isEqualTo("hwp,pdf");

        service.updateBoard(5L, BoardUpdateRequest.builder()
                .attachExtensions(com.cms.common.attachment.AttachmentFilePolicy.allowedExtensions()).version(3).build());
        assertThat(board.getAttachExtensions()).as("전체 선택 = null").isNull();
    }
}
