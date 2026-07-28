package com.cms.publicweb.notice.service;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.publicweb.notice.dto.PublicNoticeDetail;
import com.cms.publicweb.notice.dto.PublicNoticeSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final NoticeRepository noticeRepository;

    @Transactional(readOnly = true)
    public Page<PublicNoticeSummary> getPublishedNotices(int page) {
        int safePage = normalizePage(page);
        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));
        return noticeRepository.findByDeletedFalseAndUseYnTrue(pageable).map(PublicNoticeSummary::from);
    }

    @Transactional(readOnly = true)
    public Optional<PublicNoticeDetail> findPublishedNotice(Long id) {
        return noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(id).map(this::toDetail);
    }

    private PublicNoticeDetail toDetail(Notice notice) {
        return PublicNoticeDetail.from(notice);
    }

    private int normalizePage(int page) {
        if (page < 0 || page > MAX_PAGE) {
            return 0;
        }
        return page;
    }
}
