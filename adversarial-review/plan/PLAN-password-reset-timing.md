# PLAN — 비밀번호 재설정 요청의 응답 시간과 계정 열거 신호 측정

> 상태: **측정 완료 (2026-11-07), 판정 강한 신호** — 계획 v5(적대적 리뷰 4라운드 ship) 그대로 측정했다. 결과는 아래 "측정 결과·판정". **처방**: 사용자가 2026-11-08에 "발급 작업 전체를 executor로"를 골랐고 `PLAN-password-reset-async-issue.md`로 구현했다 — 같은 도구로 재측정한 결과 모든 판정 쌍이 사전 기준 미달이다.
> 로드맵 근거: `PLAN-prod-path-verification.md` "후속" 후보(재설정 요청 응답 시간 반복 측정), 로드맵 64차 갱신
> 위험 변경: 없음(이번 범위는 측정. 제품 코드·스키마·인가 정책·의존성 변경 없음). 보정이 필요하면 **별도 계획**으로 다루며 그때 "위험 변경: 동시성·보안 계약"을 다시 판단한다.

## Context

prod 컨테이너 경로 확인(2026-11-07) 중 `POST /admin/api/password-reset-requests` 응답 시간이 존재 계정 0.113초, 미존재 0.016초로 관찰됐다. 각 1회라 결론을 낼 수 없다. 이 API의 "항상 200" 계약은 계정 열거 방지가 목적이므로, 시간으로 같은 정보가 새는지 판정한다.

### 정찰에서 확인한 사실

- **경로 차이**: `PasswordResetService.requestReset` → `issueToken`. 미존재는 `findByEmailForUpdate` 뒤 반환. 무자격·쿨다운은 행을 잠그고 읽은 뒤 반환하며 **만료된 자동 잠금이 없으면** 쓰기가 없다. 존재·자격 있음·쿨다운 아님만 `SecureRandom` 토큰 생성·SHA-256·UPDATE·커밋·`mailExecutor.execute` 제출을 더 수행한다. 발송 자체는 executor라 응답 경로 밖이다. 자격 검사 앞의 `releaseExpiredAutoLock`은 만료 자동 잠금 행을 수정한다(v2).
- **기존 판단**: 서비스 주석은 발급 트랜잭션의 시간 차를 "SMTP 왕복 대비 무시할 수준"으로 본다. 측정 근거는 없다. `PLAN-password-reset.md`는 비동기 발송 도입 전의 타이밍 차를 "쿨다운으로 반복 측정이 완화된다"며 수용했다.
- **공격자 예산**: `reset-request` 레이트리밋은 **IP당 5회/3600초**(키는 `getRemoteAddr()`)이고 초기 버킷이 차 있어 **한 IP도 대상당 최대 5회를 바로 보낼 수 있다**. 쿨다운은 **쓰기만** 막는다 — 같은 이메일의 잠금 조회·검사는 매번 돌므로 반복 관찰 자체를 막지 못한다(v2 정정). 다수 IP면 C·D 상태를 사실상 반복 관찰할 수 있다. 쓰기 경로를 탄 탐침은 피해자에게 실제 메일을 보내지만 C·D 상태 탐침은 메일이 없다.
- **측정 왜곡 요인**:
  - executor는 core=max=4·queue 20이고 발송 지연 시 큐가 찬다. 제출이 거부되면 `clearIssuedTokenQuietly`가 **요청 스레드에서** DB 쓰기를 한 번 더 한다. 실제 Gmail에 더미 자격증명을 보내면 발송 지연이 측정 대상에 섞인다.
  - 발송 실패는 토큰을 지운다. 그러면 쿨다운 상태가 달라진다.
  - prod `mail`은 `auth: true`·`starttls.enable: true`(`required` 없음)라 호스트를 바꾸면 AUTH만 광고하는 가짜 SMTP로 보낼 수 있다.
- **이메일 인덱스**: `uk_member_email` 유니크. `FOR UPDATE`는 존재 시 레코드 잠금, 미존재 시 갭 잠금 경로로 달라진다.
- **DB 영속성 설정**: compose가 `innodb_flush_log_at_trx_commit`을 지정하지 않아 이미지 기본값이다. 측정 때 실제 값을 기록한다.
- **측정 도구**: 호스트에 Python·`java`(PATH)가 없다. JDK 21은 `eclipse-temurin:21-jdk` 이미지와 `~/.jdks`에 있다. 측정 코드는 전례(`PLAN-webzine-excerpt-measurement.md` D2)대로 커밋하지 않고 재현에 필요한 전문을 부록에 싣는다.
- **자원 안전 전례**: `PLAN-prod-path-verification.md` D2의 잠금 컨테이너·사전 부재 확인·토큰 대조 정리 절차.

### 핵심 쟁점

1. **판정 단위** — 평균 차이가 아니라 "공격자가 표본 k개로, 낮은 오탐률에서 얼마나 구분하는가"다. 한 번의 탐침, 한 IP의 5회 묶음, 다수 IP의 반복 관찰을 각각 본다(v2).
2. **판정 기준 사전 고정** — 측정 뒤 기준을 정하면 결론이 숫자에 끌려간다.
3. **쿨다운 때문에 같은 계정을 반복 측정할 수 없다** — 표본 수만큼 계정이 필요하다.
4. **순서·드리프트·JIT** — 첫 관찰값의 100ms 차이는 콜드 스타트일 수 있다.
5. **외삽 한계** — 로컬 디스크·동일 Docker 망의 결과는 운영 기계·인터넷 경로를 대표하지 않는다.

## 설계 결정

### D1. 환경: prod 이미지 + 격리 합성 스택

| 선택지 | 판단 |
|---|---|
| A. prod 이미지 + compose 재정의 파일(레이트리밋 끔·가짜 SMTP) | **채택** — 관찰이 나온 경로와 같다. `show-sql` 꺼짐, 템플릿 캐시 켜짐, DB가 Docker 망 안 |
| B. `java -jar` + dev DB(전례) | 기각 — Windows→WSL 포트 전달 지연이 커밋 왕복에 섞이고 dev 데이터를 만진다 |
| C. Testcontainers 시험으로 측정 | 기각 — 슬라이스·테스트 JVM 조건이 운영과 다르다 |

재정의 파일은 scratchpad에 두고 앱에 `CMS_RATE_LIMIT_ENABLED=false`, `SPRING_MAIL_HOST=fake-smtp`, `SPRING_MAIL_PORT=2525`만 더한다. `.env.prod`는 합성 값으로 만들고 끝나면 지운다. 레이트리밋을 끄는 것은 이 실행에만이고, 한도는 판정 해석에서 다룬다.

**빈 DB 기동(v4)**: 스키마는 앱 기동 때 생기므로 시드보다 기동이 먼저고, 적격 ADMIN이 없으면 `AdminBootstrapLoader`가 자격증명을 요구하며 그 안에서 HIBP 검사를 호출한다. 그래서 `.env.prod`에 합성 `ADMIN_BOOTSTRAP_*`와 `CMS_PASSWORD_BREACH_CHECK_ENABLED=false`를 **최초 기동 전부터** 넣는다. 재설정 요청 경로는 이 검사를 지나지 않아 측정값에 영향이 없다.

### D2. 측정 도구: 단일 파일 Java 2개 (커밋 안 함, 의존성 없음)

- **`FakeSmtp.java`**: 같은 compose 망의 컨테이너에서 AUTH를 광고하고 모든 메일을 즉시 수락·폐기하며 수신 건수만 센다. 외부로 나가는 메일이 없다.
- **`Probe.java`**: 같은 망의 컨테이너에서 `app:8080`으로 접속한다. 네트워크 지터 면에서는 공격자에게 유리한 조건이지만, 즉시 수락 SMTP가 큐 포화를 없애므로 **모든 공격 조건의 최선은 아니다**(v2, D7). `GET /admin/password-reset`으로 세션·CSRF를 받고, raw socket keep-alive로 POST한다. 쓰기 직전부터 응답 끝까지를 `System.nanoTime()`으로 잰다. `HttpClient` 대신 소켓을 쓰는 이유는 클라이언트 쪽 할당·스케줄링 잡음을 줄이기 위해서다. HTTP 200이 아니면 그 블록을 무효 처리한다.
- 실행 이미지는 `Dockerfile` 빌더와 같은 `eclipse-temurin:21-jdk` digest, 소스는 읽기 전용 마운트, 클라이언트 JVM은 `-XX:+UseSerialGC`.

### D3. 표본 그룹

이메일은 모두 같은 길이(`t0001@measure.example` 형식)로 맞춘다.

| 그룹 | 블록당 건수 | 상태 | 의미 |
|---|---|---|---|
| E | 200 | ACTIVE·MANAGER·토큰 없음 | 쓰기 경로 |
| N1, N2 | 각 200 | 없는 이메일 | 기준선과 **A/A 대조** |
| DL, DD, DX, DU | 각 100 | LOCKED(수동)·MANAGER / DISABLED·MANAGER / DELETED·MANAGER / ACTIVE·ROLE_USER | 존재하지만 무자격. **하위 집단별로 판정**한다(v2) |
| C | 200 | ACTIVE·MANAGER, 쿨다운 안 | 존재하지만 쓰기 없음 |
| BE | 묶음 40개 × 5건 | ACTIVE·MANAGER, 토큰 없음에서 시작 | 같은 이메일 연속 5건: E→C→C→C→C 실측(v2) |
| BN | 묶음 40개 × 5건 | 없는 이메일 | 같은 이메일 연속 5건: N→N→…(v2) |

**측정 제외: 만료 자동 잠금 경로.** 이 경로는 쓰기가 있으므로 N과 다르게 보일 수 있으나, 대상은 `LoginFailureService`가 자동 잠근 뒤 30분이 지난 `ROLE_ADMIN`·`ROLE_MANAGER`이고 잠금 해제 후 자격이 있으면 E와 같은 쓰기 경로에 컬럼 몇 개가 더해진다. E가 그 쓰기 경로를 대표한다고 **가정**하며 별도로 재지 않는다. 쿨다운 안에서 자동 잠금이 풀리는 드문 조합(쓰기 있음·토큰 없음)도 재지 않는다. 결과 문서에 미측정으로 기록한다(v2).

- **시드**: 합성 DB에 SQL로 넣는다(`seq_1_to_N`). 비밀번호는 로그인에 쓰지 않는 더미 해시다.
- **블록마다 새 계정·새 이메일(v3)**: 어떤 계정·없는 이메일도 두 블록에서 재사용하지 않는다. 학습·평가가 대상으로 겹치지 않고 블록 사이 상태 초기화가 필요 없다. 블록 시작 직전에 그 블록의 계정을 넣는다.
- **C의 쿨다운 상태**: 블록 시작 직전에 만료 시각을 앱 기준(KST, DB 컨테이너는 UTC) `현재 + 40분`으로 둔다. 역산 발급 시각이 미래가 되어 블록 내내 쿨다운 분기를 탄다. 실제 발급과 값만 다르고 분기는 같다.
- **순서**: 블록마다 단건 요청과 묶음 단위를 시드 고정 난수로 섞는다. 묶음 5건은 연속으로 보내 60초 안에 끝나게 한다. 드리프트와 순서 효과를 그룹 간에 고르게 나눈다.
- **간격**: 요청 사이 200ms. 발송 작업이 다음 표본의 CPU와 경쟁하거나 큐를 채우지 않게 한다. 블록 1개(1,600건 — 구현 때 계산 오류를 바로잡음) 약 5.3분.
- **반복**: 앱 컨테이너 재기동(새 JVM) 3회 × JVM당 3블록 = 9블록. E·N1·N2·C는 그룹당 1,800건, D 하위 집단은 900건, 묶음은 그룹당 360개.
- **워밍업**: JVM마다 별도 계정으로 혼합 500건을 보내고 판정에서 뺀다. 첫 20건은 콜드 스타트 참고값으로 기록한다.

### D4. 판정 기준 — 측정 전에 고정

쌍(모두 "미존재와 구분되는가"): **P1 E−N**, **P2 C−N**, **P3 DL−N**, **P4 DD−N**, **P5 DX−N**, **P6 DU−N**, 대조 N1−N2. N은 N1+N2다. 참고로 E−C도 낸다(같은 존재 계정의 쓰기 유무).

기술 지표: AUC(Mann-Whitney), 중앙값 차이, p10·p90.

**분류 알고리즘(블록 교차 검증, v3 고정)** — 9개 폴드 각각에서 8개 블록이 학습, 남은 1개 블록이 평가다. 방향과 임계값은 **학습 블록에서만** 정한다.

1. **방향은 두 규칙이 각각 고른다(v4)**: 점수 후보는 시간과 −시간이다. 중앙값으로 고정하지 않는다 — 꼬리만 다른 분포를 놓치기 때문이다. 각 규칙이 두 후보에 아래 임계값을 구해 학습 지표가 더 높은 쪽을 택하고, 동률이면 점수 = 시간을 택한다. 평가에는 고른 방향·임계값을 그대로 쓴다.
2. **판정 규칙**: 점수 > 임계값이면 양성, 같으면 음성.
3. **균형 정확도 규칙**: 학습 점수의 서로 다른 값 사이 중간점을 모두 후보로 두고 학습 균형 정확도가 최대인 값을 고른다. 동률이면 가장 작은 후보다.
4. **FPR 1% 규칙**: 임계값은 학습 음성 점수의 99번째 백분위(nearest-rank)이고, 학습 지표는 그때의 학습 TPR이다.
5. **집계**: 폴드마다 평가 균형 정확도·TPR·FPR을 내고, 9개 폴드의 **중앙값과 최솟값**을 쓴다. 평가 FPR은 전체 폴드를 합산한다.
6. **묶음**: BE·BN의 최댓값과 중앙값을 각각 별도 통계량으로 두고, 음성은 해당 통계량의 BN으로 같은 알고리즘을 적용한다.
7. **바닥값(v4)**: N1−N2에도 같은 알고리즘을 적용해 함께 보고한다. 양방향 선택이 대조군 값을 부풀리는지 보는 기준이다.

**불확실성(v3)**: 표본을 합친 신뢰구간을 쓰지 않는다. 같은 블록·JVM의 표본은 독립이 아니기 때문이다. 폴드별 값과 JVM별 값(그 JVM의 3개 폴드 중앙값)을 그대로 표에 싣는다.

**FPR 전이 조건(v3)**: TPR@FPR 1%는 합산 평가 FPR이 **2% 이하**일 때만 판정에 쓴다. 넘으면 "임계값 전이 실패"로 별도 보고하고 판정에서 뺀다. 묶음은 폴드당 음성이 40개라 폴드 FPR이 거칠어 합산 값으로만 본다. **FPR 1% 미만은 판정에 쓰지 않고** 참고값으로 "표본으로 검증 불가"를 붙인다.

표본 수 k별 구분력(v2):
- **k=1**: 단건 그룹.
- **k=5 실측 묶음**: BE vs BN. 묶음 5건의 최댓값과 중앙값을 각각 통계량으로 쓴다(E→C 전이에서 첫 건만 느릴 수 있어 최댓값이 필요하다).
- **k=3·5·15 재표집(다수 IP 가정)**: C−N, D 하위 집단−N에 대해 평가 블록 안에서 무작위로 k개를 뽑은 중앙값으로 분류한다. **표본 독립을 가정**한 근사이므로 참고 구분을 명시한다. E는 쓰기가 첫 건뿐이라 재표집하지 않는다.

**유효성(먼저 확인, 하나라도 어기면 그 블록 무효)**
- 모든 요청 HTTP 200.
- 블록 뒤 DB(그 블록의 새 계정만 대조): E·BE 전원 토큰 있음, D 하위 집단 전원 토큰 없음, C 만료 시각 불변, BE의 토큰이 묶음 첫 건 뒤 바뀌지 않음(메일 1통).
- 가짜 SMTP 수신 건수 = E 건수 + BE 묶음 수 + 워밍업 쓰기 건수. 앱 로그에 `제출 실패`·`발송 실패`·`잠금 실패` 0건.
- 대조 N1−N2: 전체 AUC가 0.45~0.55. 어기면 환경 잡음으로 보고 원인을 확인한 뒤 전체를 한 번 다시 잰다. 그래도 어기면 판정하지 않고 사용자에게 묻는다.

**판정(k=1의 P1~P6과 k=5 실측 묶음 중 가장 강한 값으로 정한다. 재표집 값은 판정에 쓰지 않고 함께 보고한다)**

| 조건 | 판정 | 조치 |
|---|---|---|
| 폴드 중앙값 균형 정확도 ≥ 0.80이고 최솟값 ≥ 0.70, **또는** (FPR 전이 조건 충족 + 폴드 중앙값 TPR@FPR 1% ≥ 50%이고 최솟값 ≥ 30%) | **강한 신호** | 보정 선택지를 보고하고 별도 계획을 제안한다 |
| 그 외 중 폴드 중앙값 균형 정확도 ≥ 0.60, **또는** (FPR 전이 조건 충족 + 폴드 중앙값 TPR@FPR 1% ≥ 10%이고 최솟값 ≥ 5%) | **약한 신호** | 숫자와 한계를 보고하고 사용자가 결정한다 |
| 그 외 | **사전 기준 미달** | 보정을 제안하지 않는다. "신호 없음"이나 "무시할 수준"으로 쓰지 않고 측정 범위와 함께 기록한다(v2) |

JVM 3회의 판정 구간이 서로 갈리면 판정을 유보하고 JVM별 값을 사용자에게 보여 묻는다.

**판정이 말하지 못하는 것**: 운영 디스크·DB 부하·인터넷 경로, FPR 1% 미만의 희귀 꼬리. 쓰기 경로 비용의 주 후보가 커밋 fsync라면 느린 디스크에서 차이가 커질 수 있으므로, 모든 판정은 "이 기계·이 조건"의 결론으로만 기록한다.

### D5. 원인 분리 (참고, 판정에 쓰지 않음)

9블록 뒤 같은 스택에서 `SET GLOBAL innodb_flush_log_at_trx_commit=2`로 3블록을 더 잰다. 설정 변경 뒤 P1 차이가 감소하면 커밋 내구성 설정의 기여 가능성을 참고 결과로 기록한다. 실행 순서와 DB 상태가 통제되지 않아 fsync가 주요 비용이라고 확정하지 않는다(v5). 앱 계측은 코드 변경이 필요해 하지 않는다.

### D6. 보정은 이번 범위가 아니다

측정 결과를 보고한 뒤 사용자가 결정한다. 현재 알고 있는 후보는 다음과 같으며 선택을 전제하지 않는다.

- 발급 작업 전체를 executor로 옮겨 모든 입력의 응답 경로를 같게 만든다. 없는 이메일도 큐를 쓰게 되므로 포화 위험과 재설정 지연을 따져야 한다.
- 최소 응답 시간 패딩. 무인증 경로에서 스레드를 점유한다.

### D7. 측정 조건은 "저부하·발송 성공"으로 한정한다 (v2)

즉시 수락 SMTP와 200ms 간격은 큐 포화를 없앤다. 실제로 포화되면 쓰기 경로의 계정만 `clearIssuedTokenQuietly`를 요청 스레드에서 추가로 수행하므로 **부하가 신호를 키울 수 있다**. A/A 대조는 E에만 작용하는 이런 차이를 잡지 못한다. 포화·SMTP 지연 조건은 이번에 재지 않으며, 결과 문서의 미측정 항목과 후속 후보로 남긴다. 이번 결론은 전체 열거 위험이 아니라 이 조건에서의 결론이다.

## 작업 단계

1. 잠금 획득과 사전 부재 확인(전례 D2), 합성 `.env.prod`, 재정의 파일 생성.
2. 스택 기동, DB 설정값과 이미지 digest·커밋 기록, 시드.
3. 사전 점검: E 1건 → 가짜 SMTP 수신·DB 토큰 확인, N 1건, 레이트리밋이 꺼졌는지(6회 연속 200) 확인.
4. 본 측정 9블록(JVM 3회), 원시값 CSV를 scratchpad에 저장.
5. 원인 분리 3블록.
6. 분석·판정, 결과를 이 문서에 기록.
7. 토큰 대조 후 `down -v`, `.env.prod`·잠금 제거. dev 자원이 그대로인지 확인.
8. 사용자에게 보고하고 보정 여부 결정.

## 성공 기준

- [ ] S1 D3의 그룹별 표본 수 충족, 유효성 조건 통과(무효 블록은 사유와 함께 기록)
- [ ] S2 사전 고정한 표로 판정, JVM별·블록별 값을 함께 기록
- [ ] S3 실제 외부 SMTP·HIBP 호출 없음(메일은 수신 건수로 대조, HIBP는 최초 기동부터 검사 끔을 컨테이너 환경으로 확인)
- [ ] S4 제품 코드(주석 포함)·스키마·인가 정책 변경 없음, 합성 값이 저장소에 없음. 서비스 주석의 "무시할 수준" 판단은 이번에 고치지 않고 결과와 후속으로 기록한다(v2)
- [ ] S5 정리 후 prod 자원 없음, dev 자원 그대로
- [ ] S6 재현 전문(소스·명령·SQL)을 부록에 수록

## 놓치기 쉬운 실패 모드

이번 범위에는 제품 코드가 없어 자동 시험 대신 측정 유효성 검사로 고정한다.

| # | 실패 모드 | 고정 수단 |
|---|---|---|
| F1 | 발송이 밀려 요청 스레드에서 토큰 정리가 돌아 E가 부풀려짐 | 로그 `제출 실패`·`발송 실패` 0건, DB E 토큰 전원 존재, SMTP 수신 건수 대조 |
| F2 | 레이트리밋이 켜져 있어 429가 섞이거나 빠른 거부가 표본이 됨 | 사전 점검 6회 연속 200, 모든 표본 200 단언 |
| F3 | 쿨다운·토큰 상태가 의도와 달라 E가 쓰기를 안 하거나 C가 쓰기를 함 | 블록 뒤 DB 대조(E 전원 토큰, C 만료 시각 불변) |
| F4 | 환경 잡음을 신호로 오판 | N1−N2 A/A 대조, 무작위 순서, 교차 검증 |
| F5 | 측정이 dev 또는 다른 실행의 자원을 덮어씀 | 잠금 컨테이너, 사전 부재 확인, 정리 전 토큰 대조 |

## 리스크

- Docker Desktop CPU 경합: 측정 중 다른 무거운 작업을 돌리지 않는다. A/A 대조가 어기면 드러난다.
- 소요 시간 약 70분(12블록 + 기동).
- `eclipse-temurin:21-jdk` digest 이미지가 로컬에 없으면 받는다(외부 전송 없음).

## 측정 결과·판정 (2026-11-07)

**판정: 강한 신호.** 재설정 메일이 실제로 발급되는 계정(E)은 없는 이메일과 요청 1건으로 구분된다. JVM 3회 모두 같은 판정이다. 이 결론은 이 기계·저부하·발송 성공·Docker 내부망 조건의 결과다.

### 환경

- 커밋 `f5d3fd3`, prod 이미지 + 합성 스택, MariaDB 10.11.19, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=0`, `log_bin=0`, `innodb_flush_method=O_DIRECT`, Docker Desktop(WSL2, CPU 12, 메모리 약 7.7GB).
- 14:24~15:34 KST에 측정했다. 12블록, 판정 대상 9블록.

### 유효성

| 항목 | 결과 |
|---|---|
| HTTP 200 | 전 블록 비200 0건 |
| 블록 뒤 DB 대조(E·BE 토큰, D 토큰 없음, C 토큰 불변) | 무효 블록 0개 |
| 가짜 SMTP 수신 | 3,256건 = 기대값(12 × 240 + 워밍업 375 + 사전 점검 1), 비대상 수신 0건 |
| 앱 로그 발급·발송 실패 | 0건 |
| 대조 N1−N2 | AUC 0.500, 중앙값 차 0.000ms, 균형 정확도 0.498, TPR@1% 0.000, 합산 FPR 1.17% |

### 그룹별 응답 시간 (ms, 9블록)

| 그룹 | n | p10 | 중앙값 | p90 | p99 | 최대 |
|---|---:|---:|---:|---:|---:|---:|
| N1 | 1,800 | 2.402 | 2.630 | 3.483 | 4.234 | 11.825 |
| N2 | 1,800 | 2.411 | 2.630 | 3.433 | 4.269 | 9.186 |
| E | 1,800 | 6.668 | 9.192 | 17.491 | 100.720 | 134.863 |
| C | 1,800 | 2.568 | 2.794 | 3.726 | 4.502 | 14.467 |
| DL | 900 | 2.560 | 2.789 | 3.676 | 4.322 | 9.877 |
| DD | 900 | 2.553 | 2.791 | 3.728 | 4.590 | 7.778 |
| DX | 900 | 2.556 | 2.792 | 3.801 | 4.465 | 5.857 |
| DU | 900 | 2.549 | 2.794 | 3.743 | 4.489 | 5.520 |
| BE 1번째 | 360 | 6.638 | 8.246 | 17.059 | 98.439 | 126.268 |
| BE 2번째 | 360 | 2.542 | 2.790 | 3.848 | 4.433 | 4.840 |
| BE 5번째 | 360 | 2.552 | 2.786 | 3.810 | 4.386 | 4.949 |
| BN 전체 | 1,800 | 2.391 | 2.621 | 3.510 | 4.050 | 10.848 |

### 판정 지표 (미존재 대비, 폴드 9개)

| 쌍 | AUC | 중앙값 차 ms | BA 중앙값 | BA 최소 | TPR@1% 중앙값 | 합산 FPR | 판정 | JVM별 |
|---|---:|---:|---:|---:|---:|---:|---|---|
| P1 E−N | 0.999 | 6.567 | 0.999 | 0.998 | 1.000 | 1.28% | **강한 신호** | 강함×3 |
| P2 C−N | 0.673 | 0.163 | 0.680 | 0.518 | 0.000 | 1.28% | 약한 신호 | 약함×3 |
| P3 DL−N | 0.659 | 0.159 | 0.660 | 0.503 | 0.000 | 1.28% | 약한 신호 | 약함×3 |
| P4 DD−N | 0.668 | 0.160 | 0.670 | 0.516 | 0.010 | 1.28% | 약한 신호 | 약함×3 |
| P5 DX−N | 0.667 | 0.163 | 0.661 | 0.509 | 0.000 | 1.28% | 약한 신호 | 약함×3 |
| P6 DU−N | 0.660 | 0.164 | 0.665 | 0.520 | 0.000 | 1.28% | 약한 신호 | 약함×3 |
| 묶음 최댓값 BE−BN | 0.996 | 5.496 | 1.000 | 0.988 | 1.000 | 1.39% | **강한 신호** | 강함×3 |
| 묶음 중앙값 BE−BN | 0.746 | 0.206 | 0.738 | 0.500 | 0.000 | 1.94% | 약한 신호 | 약함×3 |

### 해석

- **쓰기 경로는 단건으로 드러난다.** E의 p10(6.668ms)이 N의 p90(약 3.45ms)보다 크다. 서비스 주석의 "무시할 수준" 판단은 이 조건에서 성립하지 않는다.
- **토큰이 있는 쿨다운·무자격 계정의 신호는 작지만 대조군과 다르다.** 중앙값이 약 0.16ms 느리고 네 무자격 상태가 서로 비슷하다. 최소 폴드 BA가 0.50~0.52이고 TPR@1%가 약 0이라 단건 판별은 어렵다.
- **메일 없는 탐침의 반복 관찰은 미해결이다.** 재표집 값(k=3·5·15)은 k가 늘수록 BA가 내려가고 FPR이 최대 33%까지 오르는데, 블록 사이 N 중앙값이 2.47~3.19ms로 움직여 다른 시간대에서 정한 임계값이 옮겨 가지 않기 때문이다. **같은 시간대에 없는 이메일로 기준을 함께 재는 공격자는 이 수치로 평가되지 않았다.** C−N 차이는 모든 블록에서 양수(0.151~0.351ms)였으므로 위험이 작다고 결론 내릴 수 없다.
- **처음 관찰한 100ms 차이의 대부분은 콜드 스타트로 보인다.** JVM별 첫 요청은 98.2~114.0ms, 20번째는 6.2~7.2ms였다. 다만 E의 p99는 워밍 후에도 약 100ms이며 이 꼬리의 원인은 확인하지 못했다.
- **원인 분리(참고, 순서 미통제)**: `innodb_flush_log_at_trx_commit=2`로 3블록을 재면 E−N 중앙값 차가 6.567ms에서 1.322ms로 줄었고 E의 p99는 51.350ms였다. 커밋 내구성 설정이 기여했을 가능성이 있다. 다만 설정을 바꿔도 판정은 강한 신호(AUC 0.997, BA 0.995)로 남고, 그 3블록은 N1−N2 합산 FPR도 2.83%로 전이 조건을 넘었다. **운영에서 이 설정을 바꾸는 것은 처방이 아니다.**

### 미측정·한계

- 인터넷 경로의 지터, 운영 디스크·DB 부하. 인터넷을 거친 구분력은 재지 않았다.
- 큐 포화·SMTP 지연(D7), 만료 자동 잠금 경로(D3), 동시 부하.
- FPR 1% 미만의 구분력. TPR@0.1%는 E−N 0.490, 묶음 최댓값 0.175이고 표본으로 검증할 수 없는 참고값이다.
- 레이트리밋을 끄고 쟀다. 켜진 운영에서 한 IP는 시간당 5회가 상한이지만 E는 단건으로 드러나므로 한도가 이 신호를 막지 못한다.

### 성공 기준

- [x] S1 그룹별 표본 수 충족, 무효 블록 0개
- [x] S2 사전 고정한 표로 판정, JVM별·블록별 값 기록
- [x] S3 외부 SMTP·HIBP 호출 없음 — 메일 수신 대조 일치, 컨테이너 환경에서 `CMS_PASSWORD_BREACH_CHECK_ENABLED=false`, 메일 호스트 `fake-smtp` 확인
- [x] S4 제품 코드·스키마·인가 정책 변경 없음, 합성 값은 저장소 밖에 두었고 끝나고 지움
- [x] S5 정리 후 prod 컨테이너·볼륨·잠금 없음, dev 자원 그대로(소유권 토큰 대조 후 `down -v`)
- [x] S6 재현 전문을 부록에 수록

### 계획과 달라진 점

- 합성 환경을 저장소의 `.env.prod`가 아니라 scratchpad 파일로 두고 `--env-file`로 넘겼다. 비밀 값이 저장소에 들어갈 위험을 줄이기 위해서다. 사전 부재 확인은 그대로 했다.
- 블록당 건수를 1,400에서 1,600으로 바로잡았다(그룹 합계 계산 오류). 판정 기준은 그대로다.
- 첫 사전 점검이 `curl -o /dev/null`과 `MSYS_NO_PATHCONV=1`의 충돌로 멈춰 셸 리다이렉트로 바꿨다. 측정값에는 영향이 없다.

## 개정 이력

- v1: 최초 작성.
- v2 변경: 1라운드 지적 5건 모두 수용. ① 쿨다운은 쓰기만 막는다는 사실로 공격자 예산을 정정하고 실측 5회 묶음(BE·BN)과 k별 재표집을 추가. ② 판정에 TPR@FPR 1%·Wilson 구간을 넣고 FPR 1% 미만은 검증 불가로 표시, 최저 구간을 "사전 기준 미달"로 개명. ③ 무자격을 하위 집단 4개로 나눠 각각 판정하고 만료 자동 잠금 경로는 미측정으로 명시. ④ 조건을 저부하·발송 성공으로 한정(D7). ⑤ 주석 변경을 범위에서 제거.
- v3 변경: 2라운드 지적 반영. ① 합산 평가 FPR 2% 초과 시 TPR@FPR 1%를 판정에서 빼고 "임계값 전이 실패"로 보고, 묶음은 BN으로 따로 보정. ② 블록마다 새 계정·새 이메일을 쓰고 Wilson 구간을 폐기, 폴드 중앙값·최솟값과 JVM별 값으로 판정. ③ 방향·임계값·동률·집계 알고리즘을 고정. 기각: 계정·시간 의존성을 보존하는 부트스트랩 — 대상이 겹치지 않고 폴드 9개 분포를 그대로 보이므로 판정에 필요 없고 분석 코드만 는다.
- v4 변경: 3라운드 지적 2건 수용. ① 빈 DB 기동의 부트스트랩이 HIBP를 호출하므로 합성 자격증명과 검사 끄기를 최초 기동부터 적용. ② 방향을 중앙값으로 고정하지 않고 두 규칙이 학습 데이터에서 각각 양방향을 비교해 고르며, N1−N2에도 같은 알고리즘을 적용해 바닥값을 보고.
- v5 변경: 4라운드 지적 1건 수용. D5의 인과 해석을 "기여 가능성 참고"로 낮췄다. 교차 설계는 참고 측정 범위를 넘어 추가하지 않았다. 리뷰가 이 수정을 조건으로 ship 가능하다고 해 5라운드 없이 ship 처리.

## 부록: 재현 전문

커밋하지 않은 측정 도구의 전문이다. `setup.sh` → `precheck.sh` → `run.sh` 순서로 돌리고 `Analyze.java`를 `<CSV 디렉터리> <블록 목록> 3` 인자로 실행한다. 경로 변수 `REPO`·`T`는 실행 환경에 맞게 바꾼다. 합성 값은 실행 때 무작위로 만들며 문서에 없다.

### lib.sh

```bash
#!/usr/bin/env bash
# 측정 공용 함수. 합성 값은 출력하지 않는다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
REPO='C:/Users/user/IdeaProjects/CMS'
T='C:/Users/user/AppData/Local/Temp/claude/66209185406baa47/867bd316-1581-449e-8375-b16919ad0f59/scratchpad/timing'
ENVF="$T/secret.env"
OVR="$T/override.yml"
JDK='eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a'
NET='cms-prod_default'

compose() { (cd "$REPO" && docker compose -f docker-compose.prod.yml -f "$OVR" --env-file "$ENVF" "$@"); }

env_val() { grep "^$1=" "$ENVF" | cut -d= -f2-; }

sql() {
  docker exec -i -e MYSQL_PWD="$(env_val MYSQL_ROOT_PASSWORD)" cms-db-prod mariadb -uroot -N "$(env_val MYSQL_DATABASE)"
}

probe() { # 모드 번호 결과파일명
  docker run --rm --network "$NET" -v "$T:/tools" "$JDK" java -XX:+UseSerialGC /tools/Probe.java "$1" "$2" "/tools/out/$3"
}

wait_health() {
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo '앱 health 실패' >&2; return 1
}

# 그룹 시드: 접두사 번호 건수 상태 역할 [토큰 있음=1]
seed() {
  local p=$1 b=$2 n=$3 st=$4 role=$5 tok=${6:-0}
  local token='NULL' expiry='NULL'
  if [ "$tok" = 1 ]; then
    token="SHA2(CONCAT('$p',seq),256)"
    expiry="DATE_ADD(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 9 HOUR), INTERVAL 40 MINUTE)"
  fi
  local nb; nb=$(printf '%02d' "$b")
  echo "INSERT INTO member(create_date,email,pwd,status,update_date,user_id,user_name,user_type,password_changed_at,reset_token,reset_token_expiry_at)
SELECT NOW(6),CONCAT('$p$nb',LPAD(seq,4,'0'),'@measure.example'),'{noop}unused','$st',NOW(6),CONCAT('$p$nb',LPAD(seq,4,'0')),'m','$role',NOW(6),$token,$expiry FROM seq_1_to_$n;"
}

seed_block() {
  local b=$1
  { seed ee "$b" 200 ACTIVE ROLE_MANAGER
    seed dl "$b" 100 LOCKED ROLE_MANAGER
    seed dd "$b" 100 DISABLED ROLE_MANAGER
    seed dx "$b" 100 DELETED ROLE_MANAGER
    seed du "$b" 100 ACTIVE ROLE_USER
    seed cc "$b" 200 ACTIVE ROLE_MANAGER 1
    seed be "$b" 40 ACTIVE ROLE_MANAGER; } | sql
}

seed_warm() {
  local j=$1
  { seed we "$j" 125 ACTIVE ROLE_MANAGER
    seed wd "$j" 125 DISABLED ROLE_MANAGER
    seed wc "$j" 125 ACTIVE ROLE_MANAGER 1; } | sql
}

# 블록 유효성: 위반이 있으면 사유를 출력하고 1을 돌려준다
check_block() {
  local b=$1 nb bad=0 r
  nb=$(printf '%02d' "$b")
  cnt() { echo "SELECT COUNT(*) FROM member WHERE email LIKE '$1$nb%' AND $2;" | sql; }
  r=$(cnt ee 'reset_token IS NOT NULL'); [ "$r" = 200 ] || { echo "E 토큰 $r/200"; bad=1; }
  r=$(cnt be 'reset_token IS NOT NULL'); [ "$r" = 40 ] || { echo "BE 토큰 $r/40"; bad=1; }
  for g in dl dd dx du; do
    r=$(cnt $g 'reset_token IS NULL'); [ "$r" = 100 ] || { echo "$g 토큰 없음 $r/100"; bad=1; }
  done
  r=$(cnt cc "reset_token=SHA2(CONCAT('cc',CAST(SUBSTRING(email,5,4) AS UNSIGNED)),256)"); [ "$r" = 200 ] || { echo "C 토큰 불변 $r/200"; bad=1; }
  r=$(docker logs cms-timing-smtp 2>&1 | grep -c "^RCPT ee$nb" || true); [ "$r" = 200 ] || { echo "SMTP E $r/200"; bad=1; }
  r=$(docker logs cms-timing-smtp 2>&1 | grep -c "^RCPT be$nb" || true); [ "$r" = 40 ] || { echo "SMTP BE $r/40"; bad=1; }
  r=$(docker logs cms-timing-smtp 2>&1 | grep -cE "^RCPT (dl|dd|dx|du|cc|na|nb|bn)$nb" || true); [ "$r" = 0 ] || { echo "SMTP 비대상 $r"; bad=1; }
  return $bad
}

# 앱 로그의 발급·발송 실패 건수(현재 JVM 기준)
app_failures() {
  docker logs cms-app-prod 2>&1 | grep -cE '제출 실패|발송 실패|잠금 실패|토큰 정리 실패' || true
}
```

### setup.sh

```bash
#!/usr/bin/env bash
# 잠금 → 부재 확인 → 합성 환경 → 기동 → 사전 점검
source "$(dirname "$0")/lib.sh"
mkdir -p "$T/out"
[ ! -e "$ENVF" ] || { echo '합성 환경 파일이 이미 있다 — 중단'; exit 1; }
[ ! -e "$REPO/.env.prod" ] || { echo '.env.prod 존재 — 중단'; exit 1; }

TOKEN="$(date +%s)$RANDOM"
LOCK_IMG='mariadb:10.11@sha256:d769e37960821dfdcf096954f6e7ac89f747e116c1bba98e48edd265dfc7deae'
docker create --name cms-ci-prod-lock --label "cms.ci.run=$TOKEN" "$LOCK_IMG" true >/dev/null
echo "$TOKEN" > "$T/lock.token"

names=$(docker ps -a --format '{{.Names}}'); vols=$(docker volume ls --format '{{.Name}}')
for c in cms-app-prod cms-db-prod cms-timing-smtp; do ! grep -qx "$c" <<<"$names" || { echo "컨테이너 $c 존재 — 중단"; exit 1; }; done
for v in cms_db_data_prod cms_notice_attachments_prod; do ! grep -qx "$v" <<<"$vols" || { echo "볼륨 $v 존재 — 중단"; exit 1; }; done
if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then echo '8080 사용 중 — 중단'; exit 1; fi

rnd() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
umask 077
cat > "$ENVF" <<EOF
MYSQL_ROOT_PASSWORD=$(rnd)
MYSQL_DATABASE=cms_timing_$TOKEN
MYSQL_USER=cms_timing
MYSQL_PASSWORD=$(rnd)
MAIL_USER=measure@measure.example
MAIL_PASS=dummy
APP_BASE_URL=http://127.0.0.1:8080
CMS_PASSWORD_BREACH_CHECK_ENABLED=false
ADMIN_BOOTSTRAP_USER_ID=timingadmin
ADMIN_BOOTSTRAP_PASSWORD=$(rnd)
ADMIN_BOOTSTRAP_EMAIL=admin@measure.example
EOF

cat > "$OVR" <<EOF
services:
  app:
    environment:
      CMS_RATE_LIMIT_ENABLED: "false"
      SPRING_MAIL_HOST: fake-smtp
      SPRING_MAIL_PORT: "2525"
  fake-smtp:
    image: $JDK
    container_name: cms-timing-smtp
    command: ["java", "/tools/FakeSmtp.java"]
    volumes:
      - "$T:/tools:ro"
EOF

compose up -d --build 2>&1 | tail -n 5
wait_health
echo "app health OK"
docker exec cms-app-prod printenv CMS_PASSWORD_BREACH_CHECK_ENABLED CMS_RATE_LIMIT_ENABLED SPRING_MAIL_HOST
echo "SELECT @@version, @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@log_bin, @@innodb_flush_method; SELECT COUNT(*) FROM member;" | sql
docker images --no-trunc --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep cms-prod || true
git -C "$REPO" rev-parse --short HEAD

# 사전 점검: 실제 쓰기 경로 1건 + 미존재 1건 + 레이트리밋 꺼짐(6회 연속 200)
seed ck 0 1 ACTIVE ROLE_MANAGER | sql
probe check 0 check.csv
cat "$T/out/check.csv"
sleep 3
echo "SELECT email, reset_token IS NOT NULL FROM member WHERE email LIKE 'ck%';" | sql
docker logs cms-timing-smtp 2>&1 | tail -n 3
for i in 1 2 3 4 5 6; do
  sed -i 's/x/x/' /dev/null 2>/dev/null || true
  probe check $((10 + i)) "rl-$i.csv" >/dev/null
  cut -d, -f3,8 "$T/out/rl-$i.csv" | tr '\n' ' '
done; echo
echo "앱 로그 실패 건수: $(app_failures)"
```

### precheck.sh

```bash
#!/usr/bin/env bash
# 기동된 스택의 사전 점검: 설정 확인, 쓰기 경로 1건, 미존재 1건, 레이트리밋 꺼짐(6회 연속 200)
source "$(dirname "$0")/lib.sh"
wait_health
echo "app health OK"
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'cms-(app|db)-prod|cms-timing-smtp'
docker exec cms-app-prod printenv CMS_PASSWORD_BREACH_CHECK_ENABLED CMS_RATE_LIMIT_ENABLED SPRING_MAIL_HOST
echo "SELECT @@version, @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@log_bin, @@innodb_flush_method; SELECT COUNT(*) FROM member;" | sql
git -C "$REPO" rev-parse --short HEAD
docker image inspect cms-prod-app:latest --format '{{.Id}} {{.Created}}' 2>/dev/null || docker ps --filter name=cms-app-prod --format '{{.Image}}'

seed ck 0 1 ACTIVE ROLE_MANAGER | sql
probe check 0 check.csv
cat "$T/out/check.csv"
sleep 3
echo "SELECT email, reset_token IS NOT NULL FROM member WHERE email LIKE 'ck%';" | sql
docker logs cms-timing-smtp 2>&1 | tail -n 3
for i in 1 2 3 4 5 6; do
  probe check $((10 + i)) "rl-$i.csv" >/dev/null
  cut -d, -f3,8 "$T/out/rl-$i.csv" | tr '\n' ' '
done; echo
echo "앱 로그 실패 건수: $(app_failures)"
docker logs cms-app-prod 2>&1 | grep -E '재설정' | tail -n 4 | cut -c1-160
```

### run.sh

```bash
#!/usr/bin/env bash
# 본 측정 9블록(JVM 3회 × 3블록) + 원인 분리 3블록. 진행 상황은 표준 출력으로 남긴다.
source "$(dirname "$0")/lib.sh"
fail=0
measure() { # 블록 번호
  local b=$1
  seed_block "$b"
  probe block "$b" "block-$b.csv"
  sleep 3
  if ! check_block "$b"; then echo "블록 $b 무효"; fail=1; fi
  echo "$(date +%H:%M:%S) 블록 $b 완료, 앱 실패 로그 $(app_failures)건"
}
jvm_start() { # 번호
  local j=$1
  docker restart cms-app-prod >/dev/null
  sleep 5
  wait_health
  seed_warm "$j"
  probe warm "$j" "warm-$j.csv"
  sleep 3
  echo "$(date +%H:%M:%S) JVM $j 워밍업 완료, 앱 실패 로그 $(app_failures)건"
}
b=1
for j in 21 22 23; do
  jvm_start "$j"
  for _ in 1 2 3; do measure "$b"; b=$((b + 1)); done
done
echo 'SET GLOBAL innodb_flush_log_at_trx_commit=2;' | sql
echo "SELECT @@innodb_flush_log_at_trx_commit;" | sql
for b in 10 11 12; do measure "$b"; done
echo 'SET GLOBAL innodb_flush_log_at_trx_commit=1;' | sql
echo "측정 종료 fail=$fail"
exit $fail
```

### FakeSmtp.java

```java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** 측정 전용 가짜 SMTP: 모든 메일을 즉시 수락·폐기하고 수신자만 로그로 남긴다. 외부로 나가지 않는다. */
public class FakeSmtp {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(2525)) {
            System.out.println("READY");
            while (true) {
                Socket s = server.accept();
                Thread.startVirtualThread(() -> handle(s));
            }
        }
    }

    private static void handle(Socket s) {
        try (s) {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1);
            send(out, "220 fake ESMTP");
            String line;
            while ((line = in.readLine()) != null) {
                String u = line.toUpperCase(java.util.Locale.ROOT);
                if (u.startsWith("EHLO")) {
                    send(out, "250-fake\r\n250-AUTH PLAIN LOGIN\r\n250 OK");
                } else if (u.startsWith("HELO")) {
                    send(out, "250 fake");
                } else if (u.startsWith("AUTH LOGIN")) {
                    send(out, "334 VXNlcm5hbWU6");
                    in.readLine();
                    send(out, "334 UGFzc3dvcmQ6");
                    in.readLine();
                    send(out, "235 ok");
                } else if (u.startsWith("AUTH")) {
                    send(out, "235 ok");
                } else if (u.startsWith("RCPT")) {
                    System.out.println("RCPT " + line.substring(line.indexOf('<') + 1, line.indexOf('>')));
                    send(out, "250 ok");
                } else if (u.startsWith("DATA")) {
                    send(out, "354 go");
                    String d;
                    while ((d = in.readLine()) != null && !d.equals(".")) {
                        // 본문(토큰 포함)은 저장·출력하지 않는다.
                    }
                    send(out, "250 ok");
                } else if (u.startsWith("QUIT")) {
                    send(out, "221 bye");
                    return;
                } else {
                    send(out, "250 ok");
                }
            }
        } catch (Exception e) {
            System.out.println("ERR " + e.getClass().getSimpleName());
        }
    }

    private static void send(Writer out, String msg) throws IOException {
        out.write(msg + "\r\n");
        out.flush();
    }
}
```

### Probe.java

```java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 측정 전용 탐침: 사용법 java Probe.java <block|warm|check> <번호> <결과 CSV>.
 * 이메일은 같은 길이(<그룹 2자><번호 2자리><순번 4자리>@measure.example)로 맞춘다.
 */
public class Probe {
    record Unit(String group, List<String> emails, int bundleId) {}

    static Socket socket;
    static InputStream in;
    static OutputStream out;
    static String cookie;
    static String csrf;

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        int no = Integer.parseInt(a[1]);
        Path csv = Path.of(a[2]);
        List<Unit> units = mode.equals("warm") ? warmUnits(no) : blockUnits(no);
        long seed = 20261107L * 31 + no + (mode.equals("warm") ? 100 : 0);
        if (mode.equals("check")) {
            units = new ArrayList<>(List.of(
                    new Unit("EE", List.of(email("ck", no, 1)), -1),
                    new Unit("NA", List.of(email("cn", no, 1)), -1)));
        } else {
            Collections.shuffle(units, new Random(seed));
        }

        session();
        StringBuilder sb = new StringBuilder();
        int seq = 0;
        int bad = 0;
        for (Unit u : units) {
            int pos = 0;
            for (String e : u.emails()) {
                Thread.sleep(200);
                if (socket == null) {
                    connect();
                }
                byte[] req = post(e);
                long t0 = System.nanoTime();
                out.write(req);
                out.flush();
                int status = readResponse();
                long dt = System.nanoTime() - t0;
                if (status != 200) {
                    bad++;
                }
                sb.append(no).append(',').append(seq++).append(',').append(u.group()).append(',')
                        .append(u.bundleId()).append(',').append(pos++).append(',').append(e).append(',')
                        .append(dt).append(',').append(status).append('\n');
            }
        }
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("requests=" + seq + " non200=" + bad);
        if (bad > 0) {
            System.exit(3);
        }
    }

    static String email(String g, int no, int i) {
        return String.format("%s%02d%04d@measure.example", g, no, i);
    }

    static void add(List<Unit> l, String g, String prefix, int no, int n) {
        for (int i = 1; i <= n; i++) {
            l.add(new Unit(g, List.of(email(prefix, no, i)), -1));
        }
    }

    static List<Unit> blockUnits(int no) {
        List<Unit> l = new ArrayList<>();
        add(l, "EE", "ee", no, 200);
        add(l, "NA", "na", no, 200);
        add(l, "NB", "nb", no, 200);
        add(l, "DL", "dl", no, 100);
        add(l, "DD", "dd", no, 100);
        add(l, "DX", "dx", no, 100);
        add(l, "DU", "du", no, 100);
        add(l, "CC", "cc", no, 200);
        for (int i = 1; i <= 40; i++) {
            String be = email("be", no, i);
            String bn = email("bn", no, i);
            l.add(new Unit("BE", List.of(be, be, be, be, be), i));
            l.add(new Unit("BN", List.of(bn, bn, bn, bn, bn), i));
        }
        return l;
    }

    static List<Unit> warmUnits(int no) {
        List<Unit> l = new ArrayList<>();
        add(l, "WE", "we", no, 125);
        add(l, "WN", "wn", no, 125);
        add(l, "WD", "wd", no, 125);
        add(l, "WC", "wc", no, 125);
        return l;
    }

    static void connect() throws IOException {
        if (socket != null) {
            socket.close();
        }
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress("app", 8080), 5000);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    static void session() throws IOException {
        connect();
        out.write(("GET /admin/password-reset HTTP/1.1\r\nHost: app:8080\r\nAccept: text/html\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        lastBody = true;
        int status = readResponse();
        if (status != 200 || cookie == null) {
            throw new IllegalStateException("세션 획득 실패 status=" + status);
        }
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+content=\"([^\"]+)\"").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("CSRF 토큰을 찾지 못했다");
        }
        csrf = m.group(1);
        if (socket == null) {
            connect();
        }
    }

    static byte[] post(String email) {
        String b = "{\"email\":\"" + email + "\"}";
        return ("POST /admin/api/password-reset-requests HTTP/1.1\r\nHost: app:8080\r\n"
                + "Content-Type: application/json\r\nCookie: " + cookie + "\r\nX-CSRF-TOKEN: " + csrf
                + "\r\nContent-Length: " + b.length() + "\r\n\r\n" + b).getBytes(StandardCharsets.US_ASCII);
    }

    static boolean lastBody = false;
    static String body = "";

    static String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.isEmpty()) {
            throw new EOFException();
        }
        return sb.toString();
    }

    /** 응답을 끝까지 읽는다. 서버가 연결을 닫겠다고 하면 다음 요청의 시간 측정 전에 다시 연결한다. */
    static int readResponse() throws IOException {
        int status = Integer.parseInt(readLine().split(" ")[1]);
        int len = 0;
        boolean chunked = false;
        boolean close = false;
        String line;
        while (!(line = readLine()).isEmpty()) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("content-length:")) {
                len = Integer.parseInt(l.substring(15).trim());
            } else if (l.startsWith("transfer-encoding:") && l.contains("chunked")) {
                chunked = true;
            } else if (l.equals("connection: close")) {
                close = true;
            } else if (l.startsWith("set-cookie: jsessionid=")) {
                cookie = line.substring(12).split(";")[0];
            }
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (chunked) {
            int n;
            while ((n = Integer.parseInt(readLine().trim(), 16)) > 0) {
                bo.write(in.readNBytes(n));
                readLine();
            }
            readLine();
        } else if (len > 0) {
            bo.write(in.readNBytes(len));
        }
        if (lastBody) {
            body = bo.toString(StandardCharsets.UTF_8);
            lastBody = false;
        }
        if (close) {
            socket.close();
            socket = null;
        }
        return status;
    }
}
```

### Analyze.java

```java
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** 측정 전용 분석: java Analyze.java <CSV 디렉터리> <블록 번호 쉼표목록> <JVM당 블록 수>. 계획 D4 알고리즘을 그대로 구현한다. */
public class Analyze {
    record Row(int block, int seq, String group, int bundle, int pos, long nanos) {}
    record Rule(int dir, double thr, double trainMetric) {
        boolean pos(double t) { return dir * t > thr; }
    }
    record Fold(double ba, double tpr, double fpr, int fp, int negN, double tpr01, int fp01, int dirBa, int dirFpr) {}

    static List<Integer> blocks;
    static int perJvm;

    public static void main(String[] a) throws Exception {
        blocks = Arrays.stream(a[1].split(",")).map(Integer::valueOf).toList();
        perJvm = Integer.parseInt(a[2]);
        List<Row> rows = new ArrayList<>();
        for (int b : blocks) {
            for (String l : Files.readAllLines(Path.of(a[0], "block-" + b + ".csv"))) {
                String[] c = l.split(",");
                rows.add(new Row(Integer.parseInt(c[0]), Integer.parseInt(c[1]), c[2], Integer.parseInt(c[3]),
                        Integer.parseInt(c[4]), Long.parseLong(c[6])));
            }
        }

        System.out.println("## 그룹별 분포 (ms)\n");
        System.out.println("| 그룹 | n | p10 | 중앙값 | p90 | p99 | 최대 |\n|---|---:|---:|---:|---:|---:|---:|");
        for (String g : List.of("NA", "NB", "EE", "CC", "DL", "DD", "DX", "DU")) {
            double[] v = all(rows, g);
            dist(g, v);
        }
        for (int p = 0; p < 5; p++) {
            final int pp = p;
            dist("BE 위치" + (p + 1), rows.stream().filter(r -> r.group.equals("BE") && r.pos == pp).mapToDouble(r -> r.nanos / 1e6).toArray());
        }
        dist("BN 전체", all(rows, "BN"));

        Map<Integer, double[]> n = merged(rows, "NA", "NB");
        System.out.println("\n## 쌍별 판정 지표 (미존재 대비, 폴드 " + blocks.size() + "개)\n");
        System.out.println("| 쌍 | AUC | 중앙값 차 ms | BA 중앙값 | BA 최소 | TPR@1% 중앙값 | TPR@1% 최소 | 합산 FPR | TPR@0.1%(검증 불가·참고) | 판정 | JVM별 판정 |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|");
        pair("대조 N1−N2", byBlock(rows, "NA"), byBlock(rows, "NB"), false);
        List<String> strong = new ArrayList<>();
        strong.add(pair("P1 E−N", byBlock(rows, "EE"), n, true));
        strong.add(pair("P2 C−N", byBlock(rows, "CC"), n, true));
        strong.add(pair("P3 DL−N", byBlock(rows, "DL"), n, true));
        strong.add(pair("P4 DD−N", byBlock(rows, "DD"), n, true));
        strong.add(pair("P5 DX−N", byBlock(rows, "DX"), n, true));
        strong.add(pair("P6 DU−N", byBlock(rows, "DU"), n, true));
        strong.add(pair("묶음 최댓값 BE−BN", bundleStat(rows, "BE", true), bundleStat(rows, "BN", true), true));
        strong.add(pair("묶음 중앙값 BE−BN", bundleStat(rows, "BE", false), bundleStat(rows, "BN", false), true));
        pair("참고 E−C", byBlock(rows, "EE"), byBlock(rows, "CC"), false);
        System.out.println("\n**종합(판정 쌍 중 가장 강한 값): " + (strong.contains("강한 신호") ? "강한 신호" : strong.contains("약한 신호") ? "약한 신호" : "사전 기준 미달") + "**");
        System.out.println("(개별 JVM 판정이 전체 판정과 갈리면 유보 대상이다 — 표의 JVM별 판정 열 참조)");

        System.out.println("\n## 참고: k개 재표집 중앙값(다수 IP 가정, 표본 독립 가정 — 판정에 쓰지 않음)\n");
        System.out.println("| 쌍 | k | BA 중앙값 | TPR@1% 중앙값 | 합산 FPR |\n|---|---:|---:|---:|---:|");
        for (String g : List.of("CC", "DL", "DD", "DX", "DU")) {
            for (int k : List.of(3, 5, 15)) {
                resampled(g, byBlock(rows, g), n, k);
            }
        }

        System.out.println("\n## 블록별 중앙값 차(E−N, C−N, ms)\n");
        for (int b : blocks) {
            double m = med(n.get(b));
            System.out.printf("- 블록 %d: E−N %.3f, C−N %.3f, N %.3f%n", b, med(byBlock(rows, "EE").get(b)) - m,
                    med(byBlock(rows, "CC").get(b)) - m, m);
        }
    }

    static void dist(String g, double[] v) {
        Arrays.sort(v);
        System.out.printf("| %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f |%n", g, v.length, q(v, .1), q(v, .5), q(v, .9), q(v, .99), v[v.length - 1]);
    }

    static double q(double[] sorted, double p) {
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, i)];
    }

    static double med(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2;
    }

    static double[] all(List<Row> rows, String g) {
        return rows.stream().filter(r -> r.group.equals(g)).mapToDouble(r -> r.nanos / 1e6).toArray();
    }

    static Map<Integer, double[]> byBlock(List<Row> rows, String g) {
        Map<Integer, double[]> m = new TreeMap<>();
        for (int b : blocks) {
            m.put(b, rows.stream().filter(r -> r.block == b && r.group.equals(g)).mapToDouble(r -> r.nanos / 1e6).toArray());
        }
        return m;
    }

    static Map<Integer, double[]> merged(List<Row> rows, String g1, String g2) {
        Map<Integer, double[]> m = new TreeMap<>();
        for (int b : blocks) {
            m.put(b, rows.stream().filter(r -> r.block == b && (r.group.equals(g1) || r.group.equals(g2))).mapToDouble(r -> r.nanos / 1e6).toArray());
        }
        return m;
    }

    static Map<Integer, double[]> bundleStat(List<Row> rows, String g, boolean max) {
        Map<Integer, double[]> m = new TreeMap<>();
        for (int b : blocks) {
            Map<Integer, List<Row>> byB = rows.stream().filter(r -> r.block == b && r.group.equals(g)).collect(Collectors.groupingBy(Row::bundle));
            m.put(b, byB.values().stream().mapToDouble(l -> {
                double[] v = l.stream().mapToDouble(r -> r.nanos / 1e6).toArray();
                return max ? Arrays.stream(v).max().getAsDouble() : med(v);
            }).toArray());
        }
        return m;
    }

    static double[] concatExcept(Map<Integer, double[]> m, int ex) {
        return m.entrySet().stream().filter(e -> e.getKey() != ex).flatMapToDouble(e -> Arrays.stream(e.getValue())).toArray();
    }

    /** 균형 정확도 규칙: 학습 점수의 서로 다른 값 사이 중간점 중 최대, 동률이면 가장 작은 후보. */
    static Rule baRule(double[] pos, double[] neg, int dir) {
        double[] ps = Arrays.stream(pos).map(t -> dir * t).sorted().toArray();
        double[] ns = Arrays.stream(neg).map(t -> dir * t).sorted().toArray();
        double[] uniq = DoubleStream.concat(Arrays.stream(ps), Arrays.stream(ns)).sorted().distinct().toArray();
        double best = -1;
        double thr = 0;
        int pi = 0;
        int ni = 0;
        for (int i = 0; i + 1 < uniq.length; i++) {
            double c = (uniq[i] + uniq[i + 1]) / 2;
            while (pi < ps.length && ps[pi] <= c) pi++;
            while (ni < ns.length && ns[ni] <= c) ni++;
            double ba = ((ps.length - pi) / (double) ps.length + ni / (double) ns.length) / 2;
            if (ba > best) {
                best = ba;
                thr = c;
            }
        }
        return new Rule(dir, thr, best);
    }

    static Rule fprRule(double[] pos, double[] neg, int dir, double fpr) {
        double[] ns = Arrays.stream(neg).map(t -> dir * t).sorted().toArray();
        double thr = q(ns, 1 - fpr);
        double tpr = Arrays.stream(pos).filter(t -> dir * t > thr).count() / (double) pos.length;
        return new Rule(dir, thr, tpr);
    }

    /** 학습 지표가 높은 방향을 고르고, 동률이면 점수 = 시간(+1)을 고른다. */
    static Rule best(Rule plus, Rule minus) {
        return minus.trainMetric > plus.trainMetric ? minus : plus;
    }

    static Fold fold(double[] trainP, double[] trainN, double[] evalP, double[] evalN) {
        Rule ba = best(baRule(trainP, trainN, 1), baRule(trainP, trainN, -1));
        Rule f1 = best(fprRule(trainP, trainN, 1, .01), fprRule(trainP, trainN, -1, .01));
        Rule f01 = best(fprRule(trainP, trainN, 1, .001), fprRule(trainP, trainN, -1, .001));
        double tprBa = Arrays.stream(evalP).filter(ba::pos).count() / (double) evalP.length;
        double fprBa = Arrays.stream(evalN).filter(ba::pos).count() / (double) evalN.length;
        int fp = (int) Arrays.stream(evalN).filter(f1::pos).count();
        int fp01 = (int) Arrays.stream(evalN).filter(f01::pos).count();
        return new Fold((tprBa + 1 - fprBa) / 2, Arrays.stream(evalP).filter(f1::pos).count() / (double) evalP.length,
                fp / (double) evalN.length, fp, evalN.length,
                Arrays.stream(evalP).filter(f01::pos).count() / (double) evalP.length, fp01, ba.dir, f1.dir);
    }

    static String verdict(List<Fold> fs) {
        double[] ba = fs.stream().mapToDouble(Fold::ba).toArray();
        double[] tp = fs.stream().mapToDouble(Fold::tpr).toArray();
        int fp = fs.stream().mapToInt(Fold::fp).sum();
        int nn = fs.stream().mapToInt(Fold::negN).sum();
        boolean ok = fp / (double) nn <= .02;
        double baMin = Arrays.stream(ba).min().getAsDouble();
        double tpMin = Arrays.stream(tp).min().getAsDouble();
        if ((med(ba) >= .80 && baMin >= .70) || (ok && med(tp) >= .50 && tpMin >= .30)) return "강한 신호";
        if (med(ba) >= .60 || (ok && med(tp) >= .10 && tpMin >= .05)) return "약한 신호";
        return "사전 기준 미달";
    }

    static double auc(double[] p, double[] n) {
        double[] sn = n.clone();
        Arrays.sort(sn);
        double sum = 0;
        for (double x : p) {
            int lo = lower(sn, x);
            int hi = upper(sn, x);
            sum += lo + (hi - lo) / 2.0;
        }
        return sum / (p.length * (double) n.length);
    }

    static int lower(double[] s, double x) {
        int l = 0, h = s.length;
        while (l < h) { int m = (l + h) >>> 1; if (s[m] < x) l = m + 1; else h = m; }
        return l;
    }

    static int upper(double[] s, double x) {
        int l = 0, h = s.length;
        while (l < h) { int m = (l + h) >>> 1; if (s[m] <= x) l = m + 1; else h = m; }
        return l;
    }

    static String pair(String name, Map<Integer, double[]> pos, Map<Integer, double[]> neg, boolean judged) {
        List<Fold> fs = new ArrayList<>();
        for (int b : blocks) {
            fs.add(fold(concatExcept(pos, b), concatExcept(neg, b), pos.get(b), neg.get(b)));
        }
        double[] p = pos.values().stream().flatMapToDouble(Arrays::stream).toArray();
        double[] n = neg.values().stream().flatMapToDouble(Arrays::stream).toArray();
        int fp = fs.stream().mapToInt(Fold::fp).sum();
        int nn = fs.stream().mapToInt(Fold::negN).sum();
        boolean ok = fp / (double) nn <= .02;
        String v = verdict(fs);
        StringBuilder jv = new StringBuilder();
        for (int i = 0; i + perJvm <= fs.size(); i += perJvm) {
            if (i > 0) jv.append(" / ");
            jv.append(verdict(fs.subList(i, i + perJvm)));
        }
        double[] ba = fs.stream().mapToDouble(Fold::ba).toArray();
        double[] tp = fs.stream().mapToDouble(Fold::tpr).toArray();
        System.out.printf("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.4f%s | %.3f | %s | %s |%n", name, auc(p, n), med(p) - med(n),
                med(ba), Arrays.stream(ba).min().getAsDouble(), med(tp), Arrays.stream(tp).min().getAsDouble(), fp / (double) nn,
                ok ? "" : " (전이 실패)", med(fs.stream().mapToDouble(Fold::tpr01).toArray()), judged ? v : "대조·참고", judged ? jv : "");
        return v;
    }

    static void resampled(String g, Map<Integer, double[]> pos, Map<Integer, double[]> neg, int k) {
        Random r = new Random(20261107L + k + g.hashCode());
        List<Fold> fs = new ArrayList<>();
        for (int b : blocks) {
            fs.add(fold(sample(concatExcept(pos, b), k, 3000, r), sample(concatExcept(neg, b), k, 3000, r),
                    sample(pos.get(b), k, 1000, r), sample(neg.get(b), k, 1000, r)));
        }
        int fp = fs.stream().mapToInt(Fold::fp).sum();
        int nn = fs.stream().mapToInt(Fold::negN).sum();
        System.out.printf("| %s−N | %d | %.3f | %.3f | %.4f |%n", g, k, med(fs.stream().mapToDouble(Fold::ba).toArray()),
                med(fs.stream().mapToDouble(Fold::tpr).toArray()), fp / (double) nn);
    }

    static double[] sample(double[] v, int k, int m, Random r) {
        double[] out = new double[m];
        double[] s = new double[k];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < k; j++) s[j] = v[r.nextInt(v.length)];
            out[i] = med(s);
        }
        return out;
    }
}
```
