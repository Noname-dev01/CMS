package com.cms.publicweb.faq;

import com.cms.admin.faq.repository.FaqRepository;
import com.cms.admin.faq.repository.PublicFaqRow;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.html.SanitizedHtml;
import com.cms.publicweb.faq.dto.PublicFaqCategory;
import com.cms.publicweb.faq.dto.PublicFaqItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공개(비로그인) FAQ 조회 전용 서비스(PLAN-faq.md 쟁점 8). 관리 서비스와 별도 클래스로 두어 공개 불변식이 선택적 필터가 아니라 이 클래스가 돌려주는
 * 모든 것의 조건이 되게 한다.
 *
 * <p><b>불변식</b>: 노출 중인 카테고리 ∧ 노출 중인 FAQ만(쿼리에 고정 — 카테고리를 숨기면 그 안의 FAQ가 모두 숨는다). 답변은 출력 시 한 번 더 이미지 불허
 * 규칙으로 정리하고, 정리 결과에 보이는 내용이 없으면({@link SanitizedHtml#blank()}) 그 FAQ를 건너뛴다 — 문자열 {@code isBlank}는
 * {@code <p><br></p>}를 못 잡는다. 카테고리 제거는 <b>건너뛴 뒤</b>에 한다(보이는 FAQ가 없으면 카테고리를 그리지 않는다).
 */
@Service
@RequiredArgsConstructor
public class PublicFaqService {

    private final FaqRepository faqRepository;

    @Transactional(readOnly = true)
    public List<PublicFaqCategory> findPublicFaqs() {
        // 카테고리는 첫 보이는 항목을 만났을 때 비로소 만들어진다 — 보이는 FAQ가 없는 카테고리는 자연히 빠진다
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, List<PublicFaqItem>> itemsByCategory = new LinkedHashMap<>();
        for (PublicFaqRow row : faqRepository.findPublicRows()) {
            SanitizedHtml answer = ContentBodyPolicy.sanitizeForFaqOutput(row.answerHtml());
            if (answer.blank()) {
                continue;
            }
            names.putIfAbsent(row.categoryId(), row.categoryName());
            itemsByCategory.computeIfAbsent(row.categoryId(), key -> new ArrayList<>())
                    .add(new PublicFaqItem(row.faqId(), row.question(), answer.html()));
        }
        List<PublicFaqCategory> result = new ArrayList<>(names.size());
        names.forEach((id, name) -> result.add(new PublicFaqCategory(id, name, List.copyOf(itemsByCategory.get(id)))));
        return result;
    }
}
