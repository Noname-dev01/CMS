package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

public interface ContentImageRepositoryCustom {

    /**
     * 라이브러리 목록·검색. {@code scopeTypes}는 <b>호출자가 볼 수 있는 출처</b>의 상한이다(서비스가 역할로 정한다 — BOARD·NOTICE는 ADMIN만).
     * 이 조건은 항상 붙어 목록·COUNT가 같은 조건 객체를 쓰므로 페이지네이션·총건수가 어긋나지 않는다(PLAN-media-library.md D3).
     *
     * @param keyword    원본 파일명 부분 일치(와일드카드 문자는 그대로 취급), 숫자만 있으면 ID 일치도 포함. null이면 무시
     * @param unusedOnly true면 참조가 하나도 없는 이미지만
     */
    Page<ContentImage> searchLibrary(Collection<String> scopeTypes, String keyword, boolean unusedOnly, Pageable pageable);
}
