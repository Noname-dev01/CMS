package com.cms.admin.page.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 삭제 감사용 스냅샷 — 행이 사라진 뒤에는 ID·마지막 개정 번호를 얻을 수 없으므로 삭제 전에 만든다. 응답 본문으로는 쓰지 않는다(204). */
@Getter
@AllArgsConstructor
public class SitePageDeleteResult {

    private final Long id;
    private final String auditLabel;
}
