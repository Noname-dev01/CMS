package com.cms.publicweb.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentDownload;
import com.cms.publicweb.notice.dto.PublicNoticeAttachmentRef;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeListResult;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 공개(비로그인) 공지 조회 전용 Service. {@code NoticeService}와 별도 클래스로 두는 이유는
 * PLAN-public-notice.md 결정 1 참조 — "노출+미삭제"가 admin처럼 선택적 필터가 아니라
 * 이 클래스가 반환하는 모든 것의 불변식이 되도록 타입 단위로 격리한다.
 *
 * <p>{@code page} 파라미터의 원시 파싱(문자열 → int, 예외 회피)은 Controller의 책임이고
 * (PLAN-public-notice.md 결정 3-1), 이 Service는 그 위의 비즈니스 규칙(음수 보정·상한·
 * 고정 페이지 크기·고정 정렬)만 책임진다 — Controller가 안전한 값을 넘기지 못하는 다른
 * 호출부가 생기더라도 이 계층에서 방어적으로 재보정한다.
 */
@Service
@RequiredArgsConstructor
public class PublicNoticeService {

    private static final int PAGE_SIZE = 10;

    /**
     * 단일 요청이 만들 수 있는 OFFSET 상한. {@code notice} 테이블에 {@code deleted}·{@code use_yn}
     * 인덱스가 없어 문법적으로 유효한 거대 {@code page} 값(예: Integer.MAX_VALUE)이 큰 스캔
     * 비용을 유발할 수 있다 — COUNT 쿼리 비용 자체를 줄이지는 못하지만 OFFSET 스캔 비용
     * 상한은 확보한다(PLAN-public-notice.md 결정 8, NoticeService.MAX_PAGE_SIZE 캡 선례를
     * 따름). 이번 범위는 실사용 공지 수가 수백 건 이하인 소규모 운영을 전제한다.
     */
    private static final int MAX_PAGE = 1000;

    /** 검색어 상한 — {@code String.length()}(UTF-16 코드 유닛) 기준으로 목록 화면 입력란의 {@code maxlength}와 같다. */
    static final int MAX_KEYWORD_LENGTH = 100;

    private final NoticeRepository noticeRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final FileStorage fileStorage;

    /**
     * 공개 목록. {@code rawKeyword}가 비어 있으면(공백만 포함) 기존 목록 경로를 그대로 쓰고, 있으면 제목 검색
     * 경로를 쓴다. 키워드는 앞뒤 공백을 제거하며, {@link #MAX_KEYWORD_LENGTH}(UTF-16 코드 유닛 — HTML
     * {@code maxlength}와 같은 단위)를 넘으면 DB를 조회하지 않고 빈 페이지를 돌려준다(이상 입력 흡수 —
     * adversarial-review/plan/PLAN-public-notice-search.md 쟁점 3).
     */
    @Transactional(readOnly = true)
    public PublicNoticeListResult getPublishedNotices(int page, String rawKeyword) {
        int safePage = normalizePage(page);
        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));
        String keyword = rawKeyword == null ? "" : rawKeyword.strip();

        if (keyword.isEmpty()) {
            return new PublicNoticeListResult(
                    noticeRepository.findByDeletedFalseAndUseYnTrue(pageable).map(PublicNoticeSummary::from), null);
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            return new PublicNoticeListResult(Page.empty(pageable), keyword);
        }
        return new PublicNoticeListResult(
                noticeRepository.searchPublishedByTitle(keyword, pageable).map(PublicNoticeSummary::from), keyword);
    }

    @Transactional(readOnly = true)
    public Optional<PublicNoticeDetail> findPublishedNotice(Long id) {
        return noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(id).map(this::toDetail);
    }

    /**
     * 다운로드 시점 재검증 — notice의 공개 조건을 먼저 확인해야 트랜잭션 스냅샷이 확정된다
     * (PLAN-public-notice-attachment.md 결정 2). 이 재검증이 보장하는 것은 "재검증 SELECT를
     * 실행한 시점의 공개 상태"뿐이다 — 그 이후·응답 전송 완료 이전에 완료되는 비공개 전환까지
     * 차단하지는 않는다(약한 보장, 락 미사용). 스트리밍 전환 이후에는 이 창이 전송 시간만큼 길어진다.
     *
     * <p>열린 자원 없이 파일 참조(메타데이터)만 반환한다 — 파일 열기는 트랜잭션 밖의
     * {@link #openAttachment}가 맡는다(결정 S3: 열린 스트림이 트랜잭션 프록시를 통과하면 commit
     * 실패 시 닫을 수 없다).
     */
    @Transactional(readOnly = true)
    public Optional<PublicNoticeAttachmentRef> findPublishedAttachment(Long noticeId, Long attachmentId) {
        if (noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(noticeId).isEmpty()) {
            return Optional.empty();
        }
        return noticeAttachmentRepository.findByIdAndNoticeId(attachmentId, noticeId)
                .map(attachment -> new PublicNoticeAttachmentRef(attachment.getOriginalFilename(), attachment.getStorageKey()));
    }

    /**
     * 파일을 스트림으로 연다. <b>트랜잭션 없음</b>(의도) — {@link #findPublishedAttachment} 이후에
     * 호출한다. 반환된 {@link PublicNoticeAttachmentDownload}는 받은 쪽이 반드시 닫아야 한다.
     * 파일이 없으면({@link StorageFileNotFoundException}) empty로 흡수해 404로 매핑되게 하고,
     * 그 외 실패는 전파한다(500).
     */
    public Optional<PublicNoticeAttachmentDownload> openAttachment(PublicNoticeAttachmentRef ref) {
        try {
            StoredFileStream opened = fileStorage.open(ref.storageKey());
            return Optional.of(new PublicNoticeAttachmentDownload(ref.originalFilename(), opened.size(), opened.inputStream()));
        } catch (StorageFileNotFoundException e) {
            return Optional.empty();
        }
    }

    private PublicNoticeDetail toDetail(Notice notice) {
        List<NoticeAttachment> attachments = noticeAttachmentRepository.findByNoticeIdOrderByIdAsc(notice.getId());
        return PublicNoticeDetail.from(notice, attachments);
    }

    private int normalizePage(int page) {
        if (page < 0 || page > MAX_PAGE) {
            return 0;
        }
        return page;
    }
}
