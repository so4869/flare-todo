# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`flare-todo` is a Spring Boot 4.0.6 / Java 21 todo application using Spring Data JPA, Spring Data JDBC, Spring Security, and MySQL.

- Group: `im.flare`, root package: `im.flare.todo`
- Build tool: Gradle (wrapper at `./gradlew`)

## Commands

```bash
# Java 21 (Amazon Corretto) 경로 지정 필요
export JAVA_HOME=/Volumes/d/-default_library/amazon-corretto-21.jdk/Contents/Home

# Build
./gradlew build

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "im.flare.todo.FlareTodoApplicationTests"

# Run a single test method
./gradlew test --tests "im.flare.todo.FlareTodoApplicationTests.contextLoads"

# Clean build
./gradlew clean build
```

## Architecture

REST API backend + static HTML/JS frontend. All pages (`login.html`, `register.html`, `index.html`, `detail.html`) live in `src/main/resources/static/` and call the API via `fetch`. Spring Security uses session-based auth (cookies) — no JWT.

```
im.flare.todo/
├── config/SecurityConfig.java         # Security filter chain, CSRF disabled
├── controller/                        # AuthController, CategoryController, TodoController
├── dto/                               # Request DTOs (LoginRequest, RegisterRequest, TodoRequest, CategoryRequest)
│                                        Response DTOs (UserResponse, TodoResponse, CategoryResponse, ApiResponse<T>)
├── entity/                            # User, Category, Todo (JPA entities)
├── repository/                        # JpaRepository interfaces
├── security/UserDetailsServiceImpl.java
└── service/                           # UserService, CategoryService, TodoService
```

**API routes:**
- `POST /api/auth/register|login|logout`, `GET /api/auth/me`
- `GET|POST /api/categories`, `DELETE /api/categories/{id}`
- `GET|POST /api/todos`, `GET|PUT|DELETE /api/todos/{id}`, `PATCH /api/todos/{id}/toggle`

**Key decisions:**
- `Todo.categories` is `EAGER` fetched (Category is small) to avoid `LazyInitializationException` with `open-in-view: false`
- Security: `/api/auth/**` public, `/api/**` requires auth, static pages handled by JS redirects
- **인증: JWT (stateless)**. 로그인 시 `accessToken` 반환 → 클라이언트 `localStorage` 저장 → 모든 요청에 `Authorization: Bearer <token>` 헤더 첨부. `authFetch()` 헬퍼가 헤더 주입 및 401 처리 담당. JWT secret은 `JWT_SECRET` env var 또는 `application.yaml` 기본값 (운영 시 반드시 교체).
- `ddl-auto: update` — schema auto-created/updated at startup
- TinyMCE 5.10.9 self-hosted — `src/main/resources/static/tinymce/`에 파일 포함, 외부 의존 없음

## Stack

- **Spring Boot 4.0.6** / **Java 21** with Spring MVC, Spring Data JPA, Spring Security
- **Lombok** — `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` on entities (avoid `@Data` on JPA entities)
- **MySQL** — configure credentials via env vars `DB_USERNAME` / `DB_PASSWORD` or edit `application.yaml`
- **Bootstrap 5.3** + **Bootstrap Icons** via CDN in all HTML pages

## Configuration

DB 설정은 프로파일로 분리되어 있습니다. `application-local.yaml`은 `.gitignore`에 포함되므로 커밋되지 않습니다.

- `application.yaml` — 공통 설정 (포트, JPA), `spring.profiles.active: local`
- `application-local.yaml` — DB 크레덴셜 (git 제외)

실행 전 `src/main/resources/application-local.yaml`에서 username/password를 직접 수정하세요. 다른 프로파일(예: `prod`)을 사용할 경우 `application-prod.yaml`을 만들고 `--spring.profiles.active=prod`로 실행하면 됩니다.
