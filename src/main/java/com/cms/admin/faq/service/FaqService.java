package com.cms.admin.faq.service;

import com.cms.admin.faq.domain.Faq;
import com.cms.admin.faq.domain.FaqCategory;
import com.cms.admin.faq.domain.FaqLock;
import com.cms.admin.faq.dto.request.FaqCategoryRequest;
import com.cms.admin.faq.dto.request.FaqCategoryUpdateRequest;
import com.cms.admin.faq.dto.request.FaqOrderRequest;
import com.cms.admin.faq.dto.request.FaqRequest;
import com.cms.admin.faq.dto.response.FaqCategoryResponse;
import com.cms.admin.faq.dto.response.FaqResponse;
import com.cms.admin.faq.dto.response.FaqTreeResponse;
import com.cms.admin.faq.repository.FaqCategoryRepository;
import com.cms.admin.faq.repository.FaqLockRepository;
import com.cms.admin.faq.repository.FaqRepository;
import com.cms.admin.site.ContentSiteScope;
import com.cms.admin.site.SiteScope;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.html.ContentBodyPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FAQ 카테고리·FAQ 관리(PLAN-faq.md). 권한은 컨트롤러의 {@code @RequirePermission(FAQ, …)}가 판정하고 서비스는 대상의 존재·상태만 본다.
 *
 * <p><b>잠금</b>: <b>모든 쓰기 메서드의 첫 DB 조회가 가드 행({@code faq_lock}) {@code FOR UPDATE}</b>다 — 규칙이 하나(가드 → 끝)라 교착 추론이 필요 없고,
 * 비잠금 읽기(개수·최대 {@code ord}·목록)는 가드를 잡은 <b>뒤에</b> 한다(스냅샷이 잠금 이후에 확정된다). 구조가 바뀌는 쓰기(등록·삭제·카테고리 이동·실제로
 * 바뀐 순서 반영)만 {@link FaqLock#bumpVersion}으로 구조 버전을 올린다 — 이름·질문·답변·노출만 바뀌는 수정은 올리지 않는다(진행 중인 순서 초안을
 * 불필요하게 무효화하지 않으려고).
 *
 * <p><b>{@code ord} 불변식</b>: 각 부모(카테고리는 전체, FAQ는 카테고리)의 {@code ord}는 항상 정확히 0..n-1이다. 등록은 맨 끝, 삭제·이동은 영향받은
 * 목록을 같은 트랜잭션에서 즉시 재번호한다 — 순서 반영의 "같은 순서 재저장 = 쓰기·버전 없음"이 이 연속성에 기댄다(R2-1).
 *
 * <p><b>감사</b>: 실패 행의 {@code errorMessage}는 고정 문구다 — 답변·질문이 사용자 입력이라 정리·저장 예외 메시지에 섞여 감사 테이블에 남는 것을 막는다.
 * {@code targetId}는 Aspect가 서비스 반환 객체의 최상위 {@code getId()}로 뽑는다.
 */
@Service
@RequiredArgsConstructor
public class FaqService {

    /** 정확한 상한 — 가드로 직렬화되므로 동시 등록에도 넘지 않는다(PLAN-faq.md 쟁점 6). */
    public static final int MAX_CATEGORIES = 30;
    public static final int MAX_FAQS = 200;

    static final String CATEGORY_NOT_FOUND = "FAQ 카테고리를 찾을 수 없습니다.";
    static final String FAQ_NOT_FOUND = "FAQ를 찾을 수 없습니다.";
    static final String STALE_CATEGORY = "다른 관리자가 이 카테고리를 먼저 수정했습니다. 화면을 새로 고친 뒤 다시 시도해주세요.";
    static final String STALE_FAQ_CATEGORY = "다른 관리자가 이 FAQ를 다른 카테고리로 옮겼습니다. 화면을 새로 고친 뒤 다시 시도해주세요.";
    static final String STALE_STRUCTURE = "FAQ 구조가 변경되었습니다. 화면을 새로 고친 뒤 다시 시도해주세요.";

    private final FaqCategoryRepository categoryRepository;
    private final FaqRepository faqRepository;
    private final FaqLockRepository lockRepository;
    private final ContentSiteScope contentSiteScope;
    private final Clock clock;

    // ===================== 조회 =====================

    /**
     * 카테고리·FAQ 트리와 구조 버전을 <b>한 읽기 트랜잭션(같은 스냅샷)</b>에서 읽는다 — 쓰기만 직렬화해서는 "옛 순서 + 새 version" 조합을 막지 못한다
     * (PLAN-faq.md R1-2). 잠금 없는 일반 SELECT이고 첫 읽기가 스냅샷을 확정한다.
     */
    @Transactional(readOnly = true)
    public FaqTreeResponse getTree() {
        long version = lockRepository.findById(FaqLock.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("faq_lock 가드 행이 없습니다(V42 시드 누락)."))
                .getVersion();
        return toTree(version, categoryRepository.findAllByOrderByOrdAscIdAsc(),
                faqRepository.findAllByOrderByCategoryIdAscOrdAscIdAsc());
    }

    @Transactional(readOnly = true)
    public FaqResponse getFaq(Long id) {
        return FaqResponse.from(faqRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(FAQ_NOT_FOUND)));
    }

    // ===================== 카테고리 =====================

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_CATEGORY_CREATE, targetType = "FAQ_CATEGORY", targetIdExpression = "id",
            safeErrorMessage = "FAQ 카테고리 등록에 실패했습니다.")
    public FaqCategoryResponse createCategory(FaqCategoryRequest request) {
        String name = requireName(request.getName());
        Long siteId = SiteScope.parse(request.getSiteScope());

        FaqLock lock = lockGuard();
        if (categoryRepository.count() >= MAX_CATEGORIES) {
            throw new ConflictException("FAQ 카테고리는 최대 " + MAX_CATEGORIES + "개까지 등록할 수 있습니다.");
        }
        contentSiteScope.requireAssignable(siteId);
        LocalDateTime now = LocalDateTime.now(clock);
        FaqCategory saved = categoryRepository.save(FaqCategory.builder()
                .name(name)
                .ord(categoryRepository.findMaxOrd() + 1)
                .useYn(request.getUseYn())
                .siteId(siteId)
                .createDate(now)
                .updateDate(now)
                .build());
        lock.bumpVersion();
        return FaqCategoryResponse.from(saved);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_CATEGORY_UPDATE, targetType = "FAQ_CATEGORY", targetIdExpression = "id",
            safeErrorMessage = "FAQ 카테고리 수정에 실패했습니다.")
    public FaqCategoryResponse updateCategory(Long id, FaqCategoryUpdateRequest request) {
        String name = requireName(request.getName());
        Long siteId = SiteScope.parseRequired(request.getSiteScope());
        int version = SiteScope.requireVersion(request.getVersion(), 1);

        lockGuard();
        FaqCategory target = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(CATEGORY_NOT_FOUND));
        // 가드 아래에서 낡은 화면을 거른다 — 다른 관리자가 방금 바꾼 표시 대상을 옛 값으로 되돌려 공개 범위를 넓히지 못하게(PLAN-site-menu.md S6b)
        if (target.getVersion() != version) {
            throw new ConflictException(STALE_CATEGORY);
        }
        contentSiteScope.requireAssignable(siteId);
        target.replace(name, request.getUseYn(), siteId, LocalDateTime.now(clock));
        return FaqCategoryResponse.from(target);
    }

    /** FAQ가 하나라도 남아 있으면 409(먼저 옮기거나 지워야 한다). 지운 뒤 남은 카테고리를 재번호한다. */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_CATEGORY_DELETE, targetType = "FAQ_CATEGORY", targetIdExpression = "id",
            safeErrorMessage = "FAQ 카테고리 삭제에 실패했습니다.")
    public FaqCategoryResponse deleteCategory(Long id) {
        FaqLock lock = lockGuard();
        FaqCategory target = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(CATEGORY_NOT_FOUND));
        if (faqRepository.existsByCategoryId(id)) {
            throw new ConflictException("FAQ가 있는 카테고리는 삭제할 수 없습니다. FAQ를 다른 카테고리로 옮기거나 먼저 삭제해주세요.");
        }
        // 행이 사라진 뒤에는 응답을 만들 수 없으므로 삭제 전에 스냅샷을 만든다
        FaqCategoryResponse snapshot = FaqCategoryResponse.from(target);

        categoryRepository.delete(target);
        LocalDateTime now = LocalDateTime.now(clock);
        List<FaqCategory> rest = categoryRepository.findAllByOrderByOrdAscIdAsc();
        for (int index = 0; index < rest.size(); index++) {
            if (rest.get(index).getOrd() != index) {
                rest.get(index).changeOrder(index, now);
            }
        }
        lock.bumpVersion();
        return snapshot;
    }

    // ===================== FAQ =====================

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_CREATE, targetType = "FAQ", targetIdExpression = "id",
            safeErrorMessage = "FAQ 등록에 실패했습니다.")
    public FaqResponse createFaq(FaqRequest request) {
        String question = requireQuestion(request.getQuestion());
        String answer = ContentBodyPolicy.sanitizeFaq(request.getAnswer(), request.getContentFormat()).html();

        FaqLock lock = lockGuard();
        requireCategory(request.getCategoryId());
        if (faqRepository.count() >= MAX_FAQS) {
            throw new ConflictException("FAQ는 최대 " + MAX_FAQS + "개까지 등록할 수 있습니다. 사용하지 않는 FAQ를 삭제해주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Faq saved = faqRepository.save(Faq.builder()
                .categoryId(request.getCategoryId())
                .question(question)
                .answerHtml(answer)
                .ord(faqRepository.findMaxOrdByCategoryId(request.getCategoryId()) + 1)
                .useYn(request.getUseYn())
                .createDate(now)
                .updateDate(now)
                .build());
        lock.bumpVersion();
        return FaqResponse.from(saved);
    }

    /**
     * 질문·답변·노출 전체 교체. {@code categoryId}가 현재와 다르면 대상 카테고리의 맨 끝으로 옮기고 원래 목록을 재번호한다(구조 변경 — 버전 +1).
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_UPDATE, targetType = "FAQ", targetIdExpression = "id",
            safeErrorMessage = "FAQ 수정에 실패했습니다.")
    public FaqResponse updateFaq(Long id, FaqRequest request) {
        String question = requireQuestion(request.getQuestion());
        String answer = ContentBodyPolicy.sanitizeFaq(request.getAnswer(), request.getContentFormat()).html();
        if (request.getBaseCategoryId() == null) {
            throw new InvalidRequestException("baseCategoryId가 필요합니다.");
        }

        FaqLock lock = lockGuard();
        Faq target = faqRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(FAQ_NOT_FOUND));
        // 소속 카테고리가 곧 표시 대상이다 — 편집 시작 때와 소속이 달라졌으면 다른 관리자가 옮긴 것이므로 거부한다(S6b)
        if (!target.getCategoryId().equals(request.getBaseCategoryId())) {
            throw new ConflictException(STALE_FAQ_CATEGORY);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        target.replace(question, answer, request.getUseYn(), now);

        Long oldCategoryId = target.getCategoryId();
        if (!oldCategoryId.equals(request.getCategoryId())) {
            requireCategory(request.getCategoryId());
            target.moveTo(request.getCategoryId(), faqRepository.findMaxOrdByCategoryId(request.getCategoryId()) + 1, now);
            compactFaqs(oldCategoryId, now);
            lock.bumpVersion();
        }
        return FaqResponse.from(target);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_DELETE, targetType = "FAQ", targetIdExpression = "id",
            safeErrorMessage = "FAQ 삭제에 실패했습니다.")
    public FaqResponse deleteFaq(Long id) {
        FaqLock lock = lockGuard();
        Faq target = faqRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(FAQ_NOT_FOUND));
        // 행이 사라진 뒤에는 응답을 만들 수 없으므로 삭제 전에 스냅샷을 만든다
        FaqResponse snapshot = FaqResponse.from(target);

        faqRepository.delete(target);
        compactFaqs(target.getCategoryId(), LocalDateTime.now(clock));
        lock.bumpVersion();
        return snapshot;
    }

    // ===================== 순서 =====================

    /**
     * 순서 일괄 반영. 형식 검증 → 가드 잠금 → 구조 버전 불일치 409(낡은 화면) → 요청 집합이 현재 구조와 다르면 400(버전이 같은데 집합이 다르면 클라이언트
     * 오류 — 카테고리 소속 변경도 여기서 400) → 값이 다른 행만 {@code ord = index}로 쓰고 <b>실제로 바뀐 것이 있을 때만</b> 버전을 올린다
     * (같은 순서 재저장은 쓰기·버전 없음). 응답은 같은 트랜잭션에서 만든 새 트리다.
     */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.FAQ_ORDER, targetType = "FAQ", safeErrorMessage = "FAQ 순서 반영에 실패했습니다.")
    public FaqTreeResponse saveOrder(FaqOrderRequest request) {
        List<FaqOrderRequest.CategoryOrder> requested = request.getCategories();
        Set<Long> requestedCategoryIds = requested.stream().map(FaqOrderRequest.CategoryOrder::getId).collect(Collectors.toSet());
        if (requestedCategoryIds.size() != requested.size()) {
            throw new InvalidRequestException("카테고리 순서에 중복된 항목이 있습니다.");
        }
        for (FaqOrderRequest.CategoryOrder category : requested) {
            if (new HashSet<>(category.getFaqIds()).size() != category.getFaqIds().size()) {
                throw new InvalidRequestException("FAQ 순서에 중복된 항목이 있습니다.");
            }
        }

        FaqLock lock = lockGuard();
        if (!lock.getVersion().equals(request.getVersion())) {
            throw new ConflictException(STALE_STRUCTURE);
        }

        List<FaqCategory> categories = categoryRepository.findAllByOrderByOrdAscIdAsc();
        List<Faq> faqs = faqRepository.findAllByOrderByCategoryIdAscOrdAscIdAsc();
        Map<Long, FaqCategory> categoryById = categories.stream()
                .collect(Collectors.toMap(FaqCategory::getId, Function.identity()));
        Map<Long, Faq> faqById = faqs.stream().collect(Collectors.toMap(Faq::getId, Function.identity()));
        Map<Long, Set<Long>> faqIdsByCategory = faqs.stream().collect(
                Collectors.groupingBy(Faq::getCategoryId, Collectors.mapping(Faq::getId, Collectors.toSet())));

        if (!requestedCategoryIds.equals(categoryById.keySet())) {
            throw new InvalidRequestException("요청한 카테고리 목록이 현재 구조와 다릅니다.");
        }
        for (FaqOrderRequest.CategoryOrder category : requested) {
            Set<Long> current = faqIdsByCategory.getOrDefault(category.getId(), Set.of());
            if (!new HashSet<>(category.getFaqIds()).equals(current)) {
                throw new InvalidRequestException("요청한 FAQ 목록이 현재 구조와 다릅니다. 다른 카테고리로 옮기려면 FAQ를 수정해주세요.");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        boolean changed = false;
        for (int index = 0; index < requested.size(); index++) {
            FaqCategory category = categoryById.get(requested.get(index).getId());
            if (category.getOrd() != index) {
                category.changeOrder(index, now);
                changed = true;
            }
            List<Long> faqIds = requested.get(index).getFaqIds();
            for (int faqIndex = 0; faqIndex < faqIds.size(); faqIndex++) {
                Faq faq = faqById.get(faqIds.get(faqIndex));
                if (faq.getOrd() != faqIndex) {
                    faq.changeOrder(faqIndex, now);
                    changed = true;
                }
            }
        }
        if (changed) {
            lock.bumpVersion();
        }
        return toTree(lock.getVersion(), categories, faqs);
    }

    // ===================== 내부 =====================

    /** 모든 쓰기의 첫 DB 조회. 가드 행이 없으면 조용히 잠금 없이 진행하지 않고 실패한다. */
    private FaqLock lockGuard() {
        return lockRepository.findByIdForUpdate(FaqLock.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("faq_lock 가드 행이 없습니다(V42 시드 누락)."));
    }

    private void requireCategory(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new InvalidRequestException("선택한 카테고리가 존재하지 않습니다. 화면을 새로 고친 뒤 다시 시도해주세요.");
        }
    }

    /** 카테고리의 FAQ를 현재 표시 순서 그대로 {@code 0..n-1}로 다시 매긴다(값이 같은 행은 쓰지 않는다). */
    private void compactFaqs(Long categoryId, LocalDateTime now) {
        List<Faq> rest = faqRepository.findByCategoryIdOrderByOrdAscIdAsc(categoryId);
        for (int index = 0; index < rest.size(); index++) {
            if (rest.get(index).getOrd() != index) {
                rest.get(index).changeOrder(index, now);
            }
        }
    }

    private static FaqTreeResponse toTree(long version, List<FaqCategory> categories, List<Faq> faqs) {
        Map<Long, List<FaqTreeResponse.Item>> itemsByCategory = new LinkedHashMap<>();
        faqs.stream()
                .sorted(Comparator.comparing(Faq::getCategoryId).thenComparing(Faq::getOrd).thenComparing(Faq::getId))
                .forEach(faq -> itemsByCategory.computeIfAbsent(faq.getCategoryId(), key -> new ArrayList<>())
                        .add(new FaqTreeResponse.Item(faq.getId(), faq.getCategoryId(), faq.getQuestion(), faq.getOrd(), faq.getUseYn())));
        List<FaqTreeResponse.Category> tree = categories.stream()
                .sorted(Comparator.comparing(FaqCategory::getOrd).thenComparing(FaqCategory::getId))
                .map(category -> new FaqTreeResponse.Category(category.getId(), category.getName(), category.getOrd(),
                        category.getUseYn(), category.getSiteId(), category.getVersion(),
                        itemsByCategory.getOrDefault(category.getId(), List.of())))
                .toList();
        return new FaqTreeResponse(version, tree);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("카테고리 이름은 공백일 수 없습니다.");
        }
        return name.strip();
    }

    private static String requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new InvalidRequestException("질문은 공백일 수 없습니다.");
        }
        return question.strip();
    }
}
