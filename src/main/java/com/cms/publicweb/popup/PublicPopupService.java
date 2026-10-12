package com.cms.publicweb.popup;

import com.cms.admin.popup.repository.PopupRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.media.service.MediaImageLookup;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.popup.dto.PublicPopup;
import com.cms.publicweb.popup.dto.PublicPopupImageRef;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 공개(비로그인) 팝업 조회 전용 서비스. 관리 서비스와 별도 클래스로 두는 이유는 배너·공지와 같다 — 공개 불변식(노출 여부 ∧ 기간
 * {@code [start, end)})이 선택적 필터가 아니라 이 클래스가 돌려주는 모든 것의 조건이 되게 한다. 조건은 {@code PopupRepository}의
 * {@code findDisplayable*} 쿼리에 고정돼 있어 호출자가 건너뛸 수 없다. 판정 시각은 주입된 KST {@link Clock}으로 호출당 1회 산출한다.
 */
@Service
@RequiredArgsConstructor
public class PublicPopupService {

    /** 공개 메인이 한 번에 보여 주는 최대 팝업 수. 더 많이 켜 두면 나머지는 나오지 않는다(관리 화면이 안내한다 — PLAN-popup.md 쟁점 8). */
    public static final int MAX_DISPLAYED = 5;

    private final PopupRepository popupRepository;
    private final MediaImageLookup imageLookup;
    private final FileStorage fileStorage;
    private final Clock clock;

    /** 공개 메인에 지금 노출할 팝업을 등록 순서대로 최대 {@value #MAX_DISPLAYED}건. */
    @Transactional(readOnly = true)
    public List<PublicPopup> findDisplayablePopups() {
        return popupRepository.findDisplayable(LocalDateTime.now(clock), PageRequest.of(0, MAX_DISPLAYED)).stream()
                .map(PublicPopup::from)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 이미지 다운로드 시점 재검증(트랜잭션). 노출 중이 아니거나(비노출·기간 외)·없거나·HTML형이면 empty — 컨트롤러가 같은 404로 흡수한다.
     * 열린 자원 없이 파일 참조만 반환한다(열린 스트림이 트랜잭션 프록시를 통과하지 않게 {@link #open}과 분리).
     */
    @Transactional(readOnly = true)
    public Optional<PublicPopupImageRef> findDisplayableImage(Long id) {
        return popupRepository.findDisplayableImageById(id, LocalDateTime.now(clock))
                .flatMap(popup -> imageLookup.forOwner(ContentImageService.OWNER_POPUP, popup.getId()))
                .map(image -> new PublicPopupImageRef(image.storageKey(), image.namespace(), image.contentType()));
    }

    /** 파일을 스트림으로 연다. <b>트랜잭션 없음</b>(의도). 파일이 없으면 empty(404), 그 외 실패는 전파(500). 반환 스트림은 받은 쪽이 닫는다. */
    public Optional<StoredFileStream> open(PublicPopupImageRef ref) {
        try {
            return Optional.of(ref.namespace().isEmpty()
                    ? fileStorage.open(ref.storageKey())
                    : fileStorage.open(ref.storageKey(), ref.namespace()));
        } catch (StorageFileNotFoundException e) {
            return Optional.empty();
        }
    }
}
