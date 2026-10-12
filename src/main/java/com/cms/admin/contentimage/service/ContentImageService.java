package com.cms.admin.contentimage.service;

import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.dto.ContentImageUploadResponse;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.media.service.MediaImageRegistrar;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.image.ImageFileValidator;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 편집기 본문 이미지 업로드·참조 교체(PLAN-html-editor.md 쟁점 6·8·9·10, 미디어 라이브러리 PLAN-media-library.md).
 *
 * <p>업로드는 공통 등록 작업자({@link MediaImageRegistrar})가 카운터 행을 비관적 락으로 잡고 바이트·개수 상한을 검사·증가시킨 뒤 파일을 저장한다.
 * 게시판 에디터 업로드의 파일은 {@code FileStorage} 루트(네임스페이스 없음)에 두고, 커밋되지 못하면 정리한다.
 */
@Service
@RequiredArgsConstructor
public class ContentImageService {

    /** 게시글 본문의 참조 owner_type. 공개 판정 쿼리({@code existsPublishedPostRef})의 리터럴과 같아야 한다. */
    public static final String OWNER_POST = "POST";

    /** 배너·팝업과 이미지의 연결 owner_type(PLAN-media-library.md D4 — 소유자 테이블에 FK 컬럼을 두지 않는다). */
    public static final String OWNER_BANNER = "BANNER";
    public static final String OWNER_POPUP = "POPUP";

    /** 게시판 편집기 업로드 출처(scope_id = 게시판 ID). 공개 판정 쿼리의 리터럴과 같아야 한다. */
    public static final String SCOPE_BOARD = "BOARD";

    /**
     * 라이브러리 계열 출처 — 게시판 권한이 아니라 미디어 라이브러리 권한({@code MEDIA})이 지키는 이미지다(D3). {@code scope_id}는 NULL이다.
     * 공개 판정 쿼리({@code existsPublishedPostRef})의 리터럴 목록과 같아야 한다.
     */
    public static final String SCOPE_LIBRARY = "LIBRARY";
    public static final String SCOPE_BANNER = "BANNER";
    public static final String SCOPE_POPUP = "POPUP";

    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 본문 이미지 상한: 한 변 4096px·총 4096² 픽셀, 헤더 검사만(전체 디코드는 힙 ~64MB라 하지 않음 — 쟁점 8). */
    public static final ImageFileValidator.Limits LIMITS = new ImageFileValidator.Limits(4096, 4096L * 4096, false);

    private final ContentImageRepository imageRepository;
    private final ContentImageRefRepository refRepository;
    private final BoardRepository boardRepository;
    private final AdminSecurityService adminSecurityService;
    private final AdminPermissionEvaluator permissionEvaluator;
    private final MediaImageRegistrar registrar;

    /** 게시판 권한이 아니라 라이브러리 권한이 지키는 출처인지. */
    public static boolean isLibraryScope(String scopeType) {
        return SCOPE_LIBRARY.equals(scopeType) || SCOPE_BANNER.equals(scopeType) || SCOPE_POPUP.equals(scopeType);
    }

    /** 소유자(배너·팝업)와 함께 태어나고 함께 사라지는 출처인지 — 라이브러리 직접 업로드({@code LIBRARY})는 소유자 삭제가 날리지 않는다. */
    public static boolean isOwnedScope(String scopeType) {
        return SCOPE_BANNER.equals(scopeType) || SCOPE_POPUP.equals(scopeType);
    }

    /**
     * 게시판 편집기의 이미지 업로드(PLAN-board.md 쟁점 9). URL 게이트·핸들러 선언은 그 게시판의 READ이고, 여기서 같은 게시판의 CREATE 또는 UPDATE를
     * 다시 판정한다(403) — 작성 중(게시글 ID 없음)과 수정 중 모두 이미지를 넣어야 하는데 {@code @RequireBoardPermission}은 동작 하나만 받기 때문이다.
     * 공지도 공지 게시판의 게시글이라 이 경로를 쓴다(PLAN-notice-to-board.md 쟁점 7). 이미지는 BOARD 출처(scope_id = 게시판 ID)로 저장돼 그 게시판의 게시글에만 넣을 수 있고, 그 게시판의 현재
     * READ 권한자(ADMIN 포함)만 미참조 상태로 미리 볼 수 있다. 삭제된 게시판이면 404.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.CONTENT_IMAGE_UPLOAD, targetType = "CONTENT_IMAGE", targetIdExpression = "id")
    public ContentImageUploadResponse uploadForBoard(Long boardId, MultipartFile file) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!permissionEvaluator.allowsBoard(authentication, boardId, PermissionAction.CREATE)
                && !permissionEvaluator.allowsBoard(authentication, boardId, PermissionAction.UPDATE)) {
            throw new AccessDeniedException("게시글 작성 또는 수정 권한이 필요합니다.");
        }
        boardRepository.findByIdAndDeletedFalse(boardId)
                .orElseThrow(() -> new ResourceNotFoundException("게시판을 찾을 수 없습니다."));
        return store(file, SCOPE_BOARD, boardId);
    }

    /** 권한 판정을 마친 업로드의 공통 처리 — 검증·카운터 잠금·저장·행 기록. 출처만 진입점이 정한다. */
    private ContentImageUploadResponse store(MultipartFile file, String scopeType, Long scopeId) {
        String uploaderId = adminSecurityService.getCurrentAdminUserId();
        if (uploaderId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 이미지를 선택해주세요.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidRequestException("본문 이미지는 5MB 이하만 업로드할 수 있습니다.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }
        String contentType = file.getContentType();
        ImageFileValidator.validate(content, contentType, LIMITS);

        ContentImage saved = registrar.register(content, contentType, file.getOriginalFilename(), uploaderId, scopeType, scopeId, "");
        return ContentImageUploadResponse.from(saved);
    }

    /**
     * (ownerType, ownerId)의 참조를 본문의 이미지 ID 집합으로 교체한다. 존재하지 않는 이미지 ID는 참조에서 뺀다(깨진 이미지로
     * 남지만 무해). 호출자(콘텐츠 저장)의 트랜잭션·행 잠금 안에서 실행되어야 한다.
     *
     * <p>대상 이미지 행을 id 오름차순으로 {@code FOR SHARE} 읽어 <b>존재 확인과 삭제 방지를 한 번에</b> 한다(PLAN-media-library.md D8·R2-2) —
     * 삭제가 먼저 끝났으면 그 ID는 조회되지 않아 조용히 빠지고, 이 잠금이 먼저면 삭제는 대기했다가 새 참조를 보고 409가 된다.
     */
    @Transactional
    public void replaceRefs(String ownerType, Long ownerId, String scopeType, Long scopeId, Collection<Long> imageIds) {
        Set<Long> existing = new HashSet<>();
        if (!imageIds.isEmpty()) {
            Set<Long> alreadyReferenced = new HashSet<>(refRepository.findImageIdsByOwner(ownerType, ownerId));
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            // 참조 자격(PLAN-board.md 쟁점 9, PLAN-media-library.md D3): 게시판 출처 이미지는 콘텐츠 출처와 같아야 하고, 라이브러리 계열 이미지는
            // 저장하는 사람의 현재 MEDIA 읽기 권한이 필요하다(이미 이 콘텐츠가 참조 중인 것은 권한이 회수돼도 유지할 수 있다). 거부는 삭제·쓰기 전이라 기존 참조가 남는다.
            List<ContentImage> images = imageRepository.findAllByIdForShare(imageIds);
            for (ContentImage image : images) {
                if (isLibraryScope(image.getScopeType())) {
                    if (!alreadyReferenced.contains(image.getId())
                            && !permissionEvaluator.allows(authentication, AdminFeature.MEDIA, PermissionAction.READ)) {
                        throw new AccessDeniedException("미디어 라이브러리 이미지를 사용할 권한이 없습니다.");
                    }
                } else if (!scopeType.equals(image.getScopeType()) || !Objects.equals(scopeId, image.getScopeId())) {
                    throw new InvalidRequestException("다른 게시판·공지에서 올린 이미지는 사용할 수 없습니다. 이미지를 다시 올려 주세요.");
                }
                existing.add(image.getId());
            }
        }
        refRepository.deleteByOwner(ownerType, ownerId);
        List<ContentImageRef> refs = imageIds.stream()
                .filter(existing::contains)
                .distinct()
                .map(imageId -> new ContentImageRef(ownerType, ownerId, imageId))
                .toList();
        refRepository.saveAll(refs);
    }
}
