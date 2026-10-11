package com.cms.publicweb.popup.dto;

import com.cms.admin.popup.domain.Popup;
import com.cms.admin.popup.domain.PopupType;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.web.SafeUrls;

import java.util.Optional;

/**
 * 공개 메인에 그릴 팝업. 관리 응답({@code PopupResponse})을 재사용하지 않는다 — 노출 여부·기간·저장 키가 딸려 나가지 않게 필요한 값만 담는다.
 *
 * <ul>
 *   <li>이미지형: 이미지는 {@code /popups/{id}/image}로만 가리킨다. 링크는 출력 시 한 번 더 {@link SafeUrls}로 거르고(DB 직접 수정 방어)
 *       통과하지 못하면 링크 없는 팝업으로 그린다. 외부 http(s) 링크는 새 탭 + {@code noopener noreferrer}.</li>
 *   <li>HTML형: 본문은 <b>출력 시 한 번 더</b> 이미지 불허 규칙으로 정리한 값만 담는다 — 템플릿의 유일한 {@code th:utext}가 이 값만 받는다.
 *       정리 결과가 비면(DB 직접 수정으로 본문이 무효가 됨) 팝업을 만들지 않는다.</li>
 * </ul>
 *
 * <p>{@code revision}은 방문자 브라우저의 "오늘 하루 보지 않기"가 수정 전 팝업에 대한 것인지 가리는 값이다.
 */
public record PublicPopup(Long id, String title, boolean image, String linkUrl, boolean externalLink, String bodyHtml,
                          int posX, int posY, int width, Integer height, int revision) {

    public static Optional<PublicPopup> from(Popup popup) {
        int posX = popup.getPosX();
        int posY = popup.getPosY();
        int width = popup.getWidth();
        int revision = popup.getRevision();
        if (popup.getPopupType() == PopupType.IMAGE) {
            String link = popup.getLinkUrl();
            boolean safe = SafeUrls.isSafeMenuUrl(link);
            return Optional.of(new PublicPopup(popup.getId(), popup.getTitle(), true, safe ? link : null,
                    safe && SafeUrls.isExternalHttpUrl(link), null, posX, posY, width, null, revision));
        }
        String body = popup.getContentHtml() == null ? "" : ContentBodyPolicy.sanitizeForPopupOutput(popup.getContentHtml());
        if (body.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PublicPopup(popup.getId(), popup.getTitle(), false, null, false, body,
                posX, posY, width, popup.getHeight(), revision));
    }
}
