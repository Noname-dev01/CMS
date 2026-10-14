package com.cms.publicweb.menu.dto;

import java.util.List;

/**
 * 공개 화면에 그릴 메뉴 한 칸. {@code url}이 null이면 링크 없는 라벨(그룹)이다. 이미 {@code SiteLinkPolicy}로 다시 걸러진 값만 담긴다.
 *
 * @param external 같은 출처 경로가 아닌 외부 http(s) 링크 — 새 탭과 {@code rel="noopener noreferrer"}로 그린다
 */
public record PublicMenuItem(String name, String url, boolean external, List<PublicMenuItem> children) {
}
