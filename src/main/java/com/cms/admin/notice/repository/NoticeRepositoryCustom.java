package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NoticeRepositoryCustom {

    Page<Notice> searchNotices(NoticeSearchRequest request, Pageable pageable);

    /**
     * 공개(비로그인) 목록의 제목 검색 전용. admin {@link #searchNotices}(선택적 {@code useYn})와 달리
     * 노출·미삭제 조건이 구현에 고정돼 있다 — 공개 조건이 "선택 필터"가 되면 안 되기 때문이다
     * (adversarial-review/plan/PLAN-public-notice-search.md 쟁점 2). {@code keyword}는 호출자가
     * 정규화한 비어 있지 않은 값이어야 한다. LIKE 와일드카드({@code %}·{@code _})는 QueryDSL이 이스케이프한다.
     */
    Page<Notice> searchPublishedByTitle(String keyword, Pageable pageable);
}
