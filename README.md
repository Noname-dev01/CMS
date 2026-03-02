# CMS (Content Management System)

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen)
![Spring Security](https://img.shields.io/badge/Spring%20Security-6-green)
![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-Hibernate%206-yellowgreen)
![Thymeleaf](https://img.shields.io/badge/Thymeleaf-Template-darkgreen)
![MariaDB](https://img.shields.io/badge/MariaDB-Database-orange)
![Gradle](https://img.shields.io/badge/Gradle-Build-blueviolet)
![Docker](https://img.shields.io/badge/Docker-Container-blue)
![Docker Compose](https://img.shields.io/badge/Docker%20Compose-Orchestration-2496ED)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-lightgreen)

Spring Boot 기반 관리자 CMS 프로젝트입니다.  
Docker를 활용하여 개발 환경과 통합 실행 환경을 분리하였으며,  
Spring Security 및 Swagger 문서화를 적용했습니다.

---

# 🚀 Quick Start (Windows + WSL2 기준)

> Docker Desktop 설치 필요  
> Windows 사용자는 WSL2 기반 Ubuntu 환경에서 실행 권장

---

## 1️⃣ Ubuntu (WSL2) 설치

PowerShell에서 실행:

```powershell
wsl --install -d Ubuntu
```

설치 확인:

```powershell
wsl -l -v
```

Ubuntu가 VERSION 2로 표시되면 정상.

---

## 2️⃣ Ubuntu 실행

```powershell
wsl -d Ubuntu
```

---

## 3️⃣ make 설치

Ubuntu 터미널에서:

```bash
sudo apt update
sudo apt install -y make
```

---

## 4️⃣ 스크립트 실행 권한 부여 (최초 1회)

```bash
sudo chmod +x scripts/*.sh
```
---

## 6️⃣ 명령어 목록 확인

```bash
make help
```

---

# 🐳 주요 실행 명령어

### DB만 실행 (개발용)

```bash
make dev-db
```

### 통합 실행 (app + db)

```bash
make dev-up
```

### 종료

```bash
make dev-down
```

### 운영 유사 환경 실행

```bash
make prod-up
make prod-down
```

---

# 🔎 Utility Commands

```bash
make logs        # 전체 로그
make logs-app    # app 로그
make logs-db     # db 로그
make ps          # 컨테이너 상태 확인
make rebuild     # 캐시 없이 재빌드
make prune       # docker build 캐시 정리
```

---

## 개발환경 (IntelliJ + Docker DB)

1. DB 실행
   ```
   make dev-db
   ```

2. IntelliJ 실행
    - Active Profile: `dev`
    - `CmsApplication` 실행

접속:
- http://localhost:8080/swagger-ui.html
- http://localhost:8080/admin

---

## Security

- `/admin/**` 경로 보호
- Role 기반 접근 제어
- Custom Login Page 적용
- Spring Security Filter Chain 구성

---

## API Documentation

Swagger UI:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```
/v3/api-docs
```

---