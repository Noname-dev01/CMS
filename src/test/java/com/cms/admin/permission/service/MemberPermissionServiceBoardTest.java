package com.cms.admin.permission.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.event.NotificationRequestedEvent;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.MemberBoardPermission;
import com.cms.admin.permission.MemberBoardPermissionId;
import com.cms.admin.permission.MemberBoardPermissionRepository;
import com.cms.admin.permission.MemberPermissionRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionChangedEvent;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.dto.response.MemberPermissionMatrixResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 권한관리 PUT의 게시판별 권한(PLAN-board.md 쟁점 5) — 기능 단위 시험은 {@link MemberPermissionServiceTest}. */
@ExtendWith(MockitoExtension.class)
class MemberPermissionServiceBoardTest {

    static final long MEMBER_ID = 10L;

    @Mock MemberRepository memberRepository;
    @Mock MemberPermissionRepository memberPermissionRepository;
    @Mock MemberBoardPermissionRepository memberBoardPermissionRepository;
    @Mock BoardRepository boardRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberPermissionService service;

    private static Member manager(long version) {
        return Member.builder().id(MEMBER_ID).userId("manager10").userName("매니저10").email("m10@example.com")
                .userType(Role.ROLE_MANAGER).status(MemberStatus.ACTIVE).permissionVersion(version).build();
    }

    private static Board board(long id, boolean deleted) {
        return Board.builder().id(id).name("게시판" + id).publicYn(true).attachmentYn(true).deleted(deleted).build();
    }

    private static MemberPermissionUpdateRequest.BoardGrant grant(long boardId, PermissionAction action) {
        return new MemberPermissionUpdateRequest.BoardGrant(boardId, action);
    }

    private static MemberPermissionUpdateRequest request(long version, MemberPermissionUpdateRequest.BoardGrant... grants) {
        return MemberPermissionUpdateRequest.builder().version(version).grants(List.of()).boardGrants(List.of(grants)).build();
    }

    private void seedBoardRows(Object... boardAction) {
        List<MemberBoardPermission> rows = new ArrayList<>();
        for (int i = 0; i < boardAction.length; i += 2) {
            rows.add(new MemberBoardPermission(MEMBER_ID, (Long) boardAction[i], (String) boardAction[i + 1]));
        }
        given(memberBoardPermissionRepository.findByMemberId(MEMBER_ID)).willReturn(rows);
    }

    @Test
    @DisplayName("게시판 요청 검증은 회원 잠금 전 400: 중복·같은 게시판 READ 없는 쓰기(다른 게시판의 READ로는 안 됨)·grants의 BOARD 기능")
    void invalidBoardGrants_rejectedBeforeLock() {
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, grant(1, PermissionAction.READ), grant(1, PermissionAction.READ))))
                .isInstanceOf(InvalidRequestException.class).hasMessage("중복된 게시판 권한 항목이 있습니다.");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, grant(1, PermissionAction.CREATE), grant(2, PermissionAction.READ))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("게시판 #1").hasMessageContaining("조회 권한과 함께");
        MemberPermissionUpdateRequest boardAsFeature = MemberPermissionUpdateRequest.builder().version(0L).boardGrants(List.of())
                .grants(List.of(new MemberPermissionUpdateRequest.Grant(AdminFeature.BOARD, PermissionAction.READ))).build();
        assertThatThrownBy(() -> service.replace(MEMBER_ID, boardAsFeature))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("게시판별로");

        verifyNoInteractions(memberRepository, memberBoardPermissionRepository, boardRepository, eventPublisher);
    }

    @Test
    @DisplayName("없거나 삭제된 게시판을 부여하면 400이고 쓰기·버전·이벤트가 없다(게시판은 공유 잠금으로 확인)")
    void missingOrDeletedBoard_rejected() {
        Member target = manager(3);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(target));
        given(boardRepository.findAllByIdInForShare(anyCollection())).willReturn(List.of(board(1, false), board(2, true)));

        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(3, grant(2, PermissionAction.READ))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("#2");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(3, grant(9, PermissionAction.READ))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("#9");

        assertThat(target.getPermissionVersion()).isEqualTo(3L);
        verify(memberBoardPermissionRepository, never()).saveAll(any());
        verify(memberBoardPermissionRepository, never()).deleteKey(anyLong(), anyLong(), anyString());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("게시판 diff: 회수는 키 단위 벌크 삭제, 추가는 저장, version+1·이벤트 1회, 감사·알림에 게시판 ID만(이름 없음)")
    void replace_appliesBoardDiff() {
        Member target = manager(3);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(target));
        given(boardRepository.findAllByIdInForShare(anyCollection())).willReturn(List.of(board(10, false), board(20, false)));
        seedBoardRows(10L, "READ", 10L, "UPDATE");
        given(memberBoardPermissionRepository.existsById(any(MemberBoardPermissionId.class))).willReturn(false);
        given(boardRepository.findByDeletedFalseOrderByIdAsc()).willReturn(List.of(board(10, false), board(20, false)));

        MemberPermissionUpdateResult result = service.replace(MEMBER_ID,
                request(3, grant(10, PermissionAction.READ), grant(20, PermissionAction.READ)));

        verify(memberBoardPermissionRepository).deleteKey(MEMBER_ID, 10L, "UPDATE");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MemberBoardPermission>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(memberBoardPermissionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(MemberBoardPermission::getBoardId, MemberBoardPermission::getAction)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(20L, "READ"));
        assertThat(target.getPermissionVersion()).isEqualTo(4L);
        assertThat(result.getAuditLabel()).isEqualTo("v3→v4: 추가 1·회수 1 | -게시판#10.수정 | +게시판#20.조회");
        verify(eventPublisher).publishEvent(new PermissionChangedEvent(MEMBER_ID));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        NotificationRequestedEvent notification = events.getAllValues().stream()
                .filter(NotificationRequestedEvent.class::isInstance).map(NotificationRequestedEvent.class::cast).findFirst().orElseThrow();
        assertThat(notification.message()).isEqualTo("권한이 변경되었습니다(추가 1·회수 1): -게시판 #10 수정, +게시판 #20 조회")
                .doesNotContain("게시판10").doesNotContain("게시판20");

        MemberPermissionMatrixResponse response = result.getResponse();
        assertThat(response.getBoards()).extracting(MemberPermissionMatrixResponse.BoardRow::getBoardId,
                        MemberPermissionMatrixResponse.BoardRow::getGrantedActions)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10L, List.of(PermissionAction.READ)),
                        org.assertj.core.groups.Tuple.tuple(20L, List.of(PermissionAction.READ)));
    }

    @Test
    @DisplayName("게시판 변형 행(PK 콜레이션 충돌)이 있으면 409이고 쓰기·이벤트가 없다")
    void boardVariantRow_conflict() {
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(manager(3)));
        given(boardRepository.findAllByIdInForShare(anyCollection())).willReturn(List.of(board(10, false)));
        seedBoardRows(10L, "read");                                 // 판정기가 무시하는 변형 행
        given(memberBoardPermissionRepository.existsById(any(MemberBoardPermissionId.class))).willReturn(true);
        given(boardRepository.findByDeletedFalseOrderByIdAsc()).willReturn(List.of(board(10, false)));

        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(3, grant(10, PermissionAction.READ))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("형식이 올바르지 않은 행");
        verify(memberBoardPermissionRepository, never()).saveAll(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("게시판 권한이 같으면 '변경 없음' — 버전·쓰기·이벤트 없음")
    void unchangedBoardGrants_noop() {
        Member target = manager(5);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(target));
        given(boardRepository.findAllByIdInForShare(anyCollection())).willReturn(List.of(board(10, false)));
        seedBoardRows(10L, "READ");
        given(boardRepository.findByDeletedFalseOrderByIdAsc()).willReturn(List.of(board(10, false)));

        MemberPermissionUpdateResult result = service.replace(MEMBER_ID, request(5, grant(10, PermissionAction.READ)));

        assertThat(result.getAuditLabel()).isEqualTo("v5: 변경 없음");
        assertThat(target.getPermissionVersion()).isEqualTo(5L);
        verify(memberBoardPermissionRepository, never()).saveAll(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("감사 라벨이 500자를 넘으면 499자 + '…'로 맞춘다")
    void auditLabelIsFitted() {
        String fitted = MemberPermissionService.fitAuditLabel("가".repeat(600));
        assertThat(fitted).hasSize(MemberPermissionService.MAX_AUDIT_LABEL_LENGTH).endsWith("…");
        assertThat(MemberPermissionService.fitAuditLabel("짧음")).isEqualTo("짧음");
    }
}
