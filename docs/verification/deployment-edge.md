# 배포 경계(ingress) 체크리스트 — M-05 / PR 6

## 범위와 전제

이 문서는 실제 인터넷 배포 전 네트워크 신뢰 경계에서 확인해야 할 항목을 나열한다. **ingress(리버스 프록시 제품·TLS 종료 위치·실제 호스팅)가 아직 정해지지 않았으므로 아래 항목은 전부 "미검증(ingress 미확정)"이다.** 특정 제품(nginx 등) 설정을 이 문서에서 미리 만들지 않는다 — ingress topology가 확정되면 그 경로에서 항목별로 실제 검증하고 결과를 이 문서에 채운다.

**이 문서의 완료(체크리스트 작성)는 로드맵 "후속 과제 — ① 실배포 인프라"(nginx·TLS 인증서·실제 호스팅·CD 파이프라인까지 포함) 항목 자체의 완료를 의미하지 않는다.** PR 6(본 문서·`docs/deployment.md` 갱신·격리 drill)의 완료와, 실제 ingress 구축·검증의 완료는 별개로 추적한다.

현재 `docker-compose.prod.yml`은 `127.0.0.1:8080`에만 바인딩한다 — 이 루프백 바인딩을 그대로 인터넷에 노출하면 안 된다.

## 체크리스트 (10항목 — remediation-plan.md 7절 M-05 9항목 + SameSite 쿠키 1항목)

- [ ] 외부 client → ingress → backend의 실제 hop과 TLS 종료 위치를 기록했다.
- [ ] host ingress라면 loopback backend 접근을 확인했다. ingress가 container라면 그 container의 `127.0.0.1`은 앱 host가 아니라는 점을 반영해 실제 private 경로를 확인했다.
- [ ] backend host port(8080)는 인터넷에 직접 노출되지 않으며, 별도 private/container 경로를 통한 우회도 제한했다.
- [ ] client가 보낸 `X-Forwarded-For` / `X-Real-IP` / `Forwarded` 및 scheme 관련 헤더를 ingress가 제거·재작성한다. 여러 proxy가 있으면 승인된 hop 정책을 명시했다.
- [ ] 앱이 신뢰할 proxy 범위를 명시했다. `server.forward-headers-strategy` 설정 한 줄만으로 검증 완료라고 보지 않는다.
- [ ] 서로 다른 두 외부 client가 서로 다른 `remoteAddr`/rate-limit key로 관찰된다. 같은 NAT의 두 브라우저를 "두 IP"로 오인하지 않는다.
- [ ] 임의 forwarded 헤더를 바꿔 레이트리밋 quota를 회피하거나 `AdminActionLogAspect`의 감사 IP를 위조할 수 없는지 시험했다. `RateLimitFilter`와 `ClientIpResolver`(감사·방문 로그)가 같은 `remoteAddr`를 쓰는지 실제 경로에서 대조했다(작성 당시의 IP 소스 불일치 — 감사 로그가 전달 헤더를 우선 사용 — 는 2026-09-28 `d8952ef` #44, 감사 H-03으로 `remoteAddr` 단일 소스로 통일됨).
- [ ] HTTPS 외부 요청을 앱이 올바른 scheme으로 인식하고 `Secure`/`HttpOnly` cookie, HTTP→HTTPS redirect, 혼합 콘텐츠, redirect loop를 확인했다.
- [ ] `APP_BASE_URL`의 비밀번호 재설정 링크가 최종 HTTPS origin과 일치한다.
- [ ] **세션·CSRF 쿠키의 `SameSite` 속성이 실제 ingress 구성(단일 origin/서브도메인 분리 등)과 맞는지 확인했다** — 로드맵 "후속 과제 — ① 실배포 인프라"가 명시한 `forward-headers-strategy`·secure/SameSite 쿠키 항목. 현재 코드는 별도 `SameSite` 설정이 없어 서블릿 컨테이너 기본값을 따른다.

proxy 제품·trusted range·인증서·호스트가 미정이면 이 항목들은 모두 미완료다. 이번 PR에서 임의 제품 설정을 정하지 않는다. 실제 검증 중 결함이 발견되면 해당 ingress 통합 범위의 최소 수정안을 별도 승인받는다.

## 갱신 이력

- 2026-10-06: 문서 정합(감사 L-02) — 7번째 항목의 IP 소스 불일치 서술을 #44 이후 상태로 갱신. 체크 상태는 그대로(전 항목 미검증).
- 2026-09-27: PR 6(M-01·M-05) — 최초 작성. `adversarial-review/remediation-plan.md` 7절 M-05 체크리스트를 제품 중립적으로 옮기고 SameSite 항목을 추가. 전 항목 미검증.
