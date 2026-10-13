package com.cms.admin.site;

import com.cms.common.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 콘텐츠를 사이트 전용으로 지정할 때의 대상 검증(PLAN-site-menu.md PR ③ S2). 대상 사이트 행을 {@code FOR SHARE}로 잠가 읽어 존재·공개 사이트
 * 여부를 확인한다 — {@code SiteService.deleteSite}가 사이트 행을 {@code FOR UPDATE}로 잡은 뒤 내용 존재를 보므로, 지정 쪽이 공유 잠금을 쥐고
 * INSERT/UPDATE 하면 삭제가 그 커밋을 기다린 뒤 새 소속을 보게 된다(삭제 409 / 지정 400 중 하나로 갈리고 FK 예외·고아가 없다).
 *
 * <p><b>잠금 순서</b>: 호출자의 기능 가드·대상 행 잠금을 먼저 잡은 <b>뒤</b>에 호출한다(가드 → 소유자 행 → 사이트 행). 사이트 쓰기 서비스는 콘텐츠
 * 가드를 잡지 않으므로 순환이 없다. 진행 중인 트랜잭션이 필요하다({@code MANDATORY}).
 */
@Component
@RequiredArgsConstructor
public class ContentSiteScope {

    private final SiteRepository siteRepository;

    /** {@code siteId}가 {@code null}(공통)이면 아무것도 하지 않는다. 그 밖에는 존재하는 공개 사이트여야 한다(비활성 사이트도 허용). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAssignable(Long siteId) {
        if (siteId == null) {
            return;
        }
        Site site = siteRepository.findByIdForShare(siteId)
                .orElseThrow(() -> new InvalidRequestException("선택한 사이트가 존재하지 않습니다. 화면을 새로 고친 뒤 다시 시도해주세요."));
        if (site.getSiteType() != SiteType.PUBLIC) {
            throw new InvalidRequestException("공개 사이트만 표시 대상으로 지정할 수 있습니다.");
        }
    }
}
