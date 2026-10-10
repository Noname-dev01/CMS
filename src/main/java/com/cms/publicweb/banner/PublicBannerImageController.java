package com.cms.publicweb.banner;

import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.banner.dto.PublicBannerImageRef;
import com.cms.publicweb.support.PublicImageStreamer;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.util.Optional;

/**
 * 배너 이미지 공개 다운로드 {@code GET/HEAD /banners/{id}/image}(PLAN-public-home-banner.md 쟁점 11). {@code SecurityConfig}가 이 경로의
 * GET·HEAD만 무인증 공개하고, 노출 여부·기간 재검증은 {@link PublicBannerService}가 한다.
 *
 * <p>{@code id}는 문자열로 받아 직접 파싱한다 — 비숫자·{@code Long} 범위 밖·없는 ID·비노출·기간 외 배너를 모두 같은 404로 흡수해 존재 여부를
 * 드러내지 않는다(본문 이미지·공개 첨부와 같은 정책). 예외를 던지지 않는다(던지면 404가 아니라 HTML 500).
 */
@Controller
@RequestMapping("/banners")
@RequiredArgsConstructor
public class PublicBannerImageController {

    private final PublicBannerService publicBannerService;

    @GetMapping("/{id}/image")
    public void image(@PathVariable String id, HttpServletResponse response) throws IOException {
        Optional<Opened> opened = open(id, response);
        if (opened.isPresent()) {
            PublicImageStreamer.stream(opened.get().stream(), opened.get().contentType(), response);
        }
    }

    /** HEAD는 GET과 같은 경로로 404를 판정하고 본문은 읽지 않는다. */
    @RequestMapping(value = "/{id}/image", method = RequestMethod.HEAD)
    public void imageHead(@PathVariable String id, HttpServletResponse response) throws IOException {
        Optional<Opened> opened = open(id, response);
        if (opened.isPresent()) {
            PublicImageStreamer.headOnly(opened.get().stream(), opened.get().contentType(), response);
        }
    }

    private Optional<Opened> open(String rawId, HttpServletResponse response) throws IOException {
        Long id = parseId(rawId);
        Optional<Opened> opened = id == null
                ? Optional.empty()
                : publicBannerService.findDisplayableImage(id)
                        .flatMap(ref -> publicBannerService.open(ref).map(stream -> new Opened(stream, ref)));
        if (opened.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        return opened;
    }

    private Long parseId(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Opened(StoredFileStream stream, PublicBannerImageRef ref) {
        String contentType() {
            return ref.contentType();
        }
    }
}
