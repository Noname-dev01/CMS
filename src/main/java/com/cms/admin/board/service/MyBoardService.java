package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.dto.response.MyBoardResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.common.attachment.AttachmentFilePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 현재 사용자가 조회(READ)할 수 있는 게시판과 게시판별 허용 동작(PLAN-board.md 쟁점 8) — 게시글 관리 화면의 게시판 선택·버튼 표시용.
 * ADMIN은 삭제되지 않은 게시판 전부·전 동작, MANAGER는 그 회원의 게시판별 허용(의존 규칙 적용)으로 READ가 있는 게시판만.
 */
@Service
@RequiredArgsConstructor
public class MyBoardService {

    private final BoardRepository boardRepository;
    private final AdminPermissionEvaluator permissionEvaluator;

    @Transactional(readOnly = true)
    public List<MyBoardResponse> getMyBoards(Authentication authentication) {
        Supplier<PermissionSnapshot> snapshotOnce = memoize(permissionEvaluator::snapshot);
        List<MyBoardResponse> result = new ArrayList<>();
        for (Board board : boardRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()) {
            Set<PermissionAction> actions = permissionEvaluator.boardActions(snapshotOnce, authentication, board.getId());
            if (actions.contains(PermissionAction.READ)) {
                Set<String> boardExtensions = board.attachExtensionSet();
                // 실효 목록: 제한 없으면 전역 전체. 저장값이 전역 밖이어도 화면에 천장을 넘는 값이 나가지 않게 전역과 교집합만 낸다
                List<String> extensions = AttachmentFilePolicy.allowedExtensions().stream()
                        .filter(extension -> boardExtensions == null || boardExtensions.contains(extension))
                        .toList();
                result.add(new MyBoardResponse(board.getId(), board.getName(), board.getPublicYn(), board.getAttachmentYn(), board.getWritableYn(),
                        board.getPinMaxCount(),
                        Math.min(board.getAttachMaxCount(), AttachmentFilePolicy.MAX_COUNT_PER_OWNER),
                        Math.min(board.getAttachMaxSizeMb(), AttachmentFilePolicy.MAX_FILE_SIZE_MB),
                        extensions, board.getBodyImageYn(), board.getPublishPeriodYn(), board.type(),
                        Arrays.stream(PermissionAction.values()).filter(actions::contains).toList()));
            }
        }
        return result;
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private T value;
            private boolean loaded;

            @Override
            public T get() {
                if (!loaded) {
                    value = delegate.get();
                    loaded = true;
                }
                return value;
            }
        };
    }
}
