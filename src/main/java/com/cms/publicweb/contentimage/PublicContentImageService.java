package com.cms.publicweb.contentimage;

import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 본문 이미지 공개 다운로드 판정(PLAN-html-editor.md 쟁점 6). 다음 중 하나면 허용, 아니면 empty(→ 404, 존재 여부 비노출):
 * <ol>
 *   <li>공개 게시글(노출·미삭제, 공개·미삭제 게시판 소속)이 참조하고 이미지가 그 게시판 출처다(PLAN-board.md 쟁점 9). 공지도 공지 게시판의
 *       게시글이라 이 경로로 공개된다(PLAN-notice-to-board.md 쟁점 7)</li>
 *   <li>이미지가 게시판 출처이고 요청자가 <b>그 게시판의 현재 READ</b> 권한 보유자(ADMIN 포함)다 — 작성 중·비공개 게시글 미리보기. 권한을 회수하면 즉시 404다</li>
 * </ol>
 * 조회(트랜잭션)와 파일 열기(트랜잭션 없음)를 나눈다 — 열린 스트림이 트랜잭션 프록시를 통과하지 않게
 * (공개 첨부 다운로드 {@code PublicNoticeService}와 같은 구조).
 */
@Service
@RequiredArgsConstructor
public class PublicContentImageService {

    private final ContentImageRepository imageRepository;
    private final ContentImageRefRepository refRepository;
    private final AdminPermissionEvaluator permissionEvaluator;
    private final FileStorage fileStorage;

    @Transactional(readOnly = true)
    public Optional<ContentImageMeta> findViewable(Long imageId, Authentication authentication) {
        return imageRepository.findById(imageId)
                // 값싼 판정부터 — 익명 요청은 앞의 두 DB 조회만 거치고 권한 판정은 DB를 보지 않고 거부한다
                .filter(image -> refRepository.existsPublishedPostRef(imageId)
                        // 게시판 출처 이미지의 미리보기는 그 게시판의 현재 READ로만(PLAN-board.md 쟁점 9 — 업로더 본인 조건 없음, 회수 즉시 반영)
                        || (ContentImageService.SCOPE_BOARD.equals(image.getScopeType()) && image.getScopeId() != null
                            && permissionEvaluator.allowsBoard(authentication, image.getScopeId(), PermissionAction.READ)))
                .map(image -> new ContentImageMeta(image.getStorageKey(), image.getContentType()));
    }

    /** 트랜잭션 없음(의도). 파일이 없으면 empty(404), 그 외 실패는 전파(500). 반환 스트림은 호출자가 닫는다. */
    public Optional<ContentImageDownload> open(ContentImageMeta meta) {
        try {
            StoredFileStream opened = fileStorage.open(meta.storageKey());
            return Optional.of(new ContentImageDownload(meta.contentType(), opened.size(), opened.inputStream()));
        } catch (StorageFileNotFoundException e) {
            return Optional.empty();
        }
    }
}
