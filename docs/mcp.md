# MCP 서버 연결 안내

Flare Todo 는 MCP(Model Context Protocol) 서버를 내장하고 있어 Claude Desktop, Claude Code, Cursor 같은
MCP 클라이언트에서 할 일과 카테고리를 읽고 관리할 수 있다.

- 엔드포인트: `https://todo.flare.im/mcp` (Streamable HTTP)
- 인증: `Authorization: Bearer <API 토큰>` 헤더. 토큰은 웹 UI 상단의 **API 토큰** 메뉴에서 발급한다.
- 도구: `list_todos`, `get_todo`, `create_todo`, `update_todo`, `complete_todo`, `delete_todo`,
  `list_categories`, `create_category`, `get_attachment_url`, `upload_attachment`
- 첨부: `get_todo` 가 첨부 목록(ID·이름·크기)을 함께 돌려주고, `get_attachment_url` 이 짧게 유효한 S3 서명
  다운로드 URL(기본 15분, 최대 7일)을 만든다. `upload_attachment` 는 base64 로 받은 파일(최대 20MB)을 S3 에 올려
  할 일에 연결한다. CloudFront 서명 쿠키는 MCP 클라이언트가 쓸 수 없어 서명 URL 방식을 쓴다.

## API 토큰(PAT)

- 형식은 `ft_` + 43자, 서버에는 SHA-256 해시만 저장되므로 발급 직후 한 번만 볼 수 있다.
- 스코프: `todos:read`(읽기), `todos:write`(추가·수정·삭제). MCP 도구와 REST API(`/api/**`) 모두 스코프를 따른다.
- 토큰 관리(`/api/tokens`)는 웹 로그인(JWT)으로만 가능하다. API 토큰으로는 다른 토큰을 만들거나 지울 수 없다.
- 만료일을 두거나 만료 없음으로 발급할 수 있고, 언제든 삭제(폐기)하면 즉시 무효가 된다.

## 클라이언트 설정 예시

Claude Code:

```bash
claude mcp add --transport http flare-todo https://todo.flare.im/mcp \
  --header "Authorization: Bearer ft_..."
```

Claude Desktop / Cursor (`mcpServers` 항목):

```json
{
  "flare-todo": {
    "type": "http",
    "url": "https://todo.flare.im/mcp",
    "headers": { "Authorization": "Bearer ft_..." }
  }
}
```

## 운영 시 필수 설정

- `JWT_SECRET` 환경변수: 32바이트 이상의 무작위 문자열(예: `openssl rand -base64 48`).
  예전의 코드 내 기본값은 제거되었고, 비어 있으면 애플리케이션이 기동하지 않는다.
- `api_tokens` 테이블은 JPA `ddl-auto: update` 로 자동 생성된다.

## 구조

- `security/JwtAuthenticationFilter`: Bearer 값이 `ft_` 로 시작하면 PAT, 아니면 JWT 로 해석
- `service/ApiTokenService`: 발급·검증·폐기 (해시 조회, `last_used_at` 갱신)
- `mcp/McpServerConfig`: 요청 스레드의 인증 정보를 MCP 전송 컨텍스트에 실어 도구 스레드로 전달
- `mcp/TodoMcpTools`: 도구 구현. 컨트롤러와 똑같이 `User` 를 구해 기존 서비스 계층을 호출
- 다음 단계(Claude.ai 웹 커넥터 등 OAuth 전용 클라이언트 지원): Spring Authorization Server 로 OAuth 2.1
  (PKCE, 동적 클라이언트 등록, 보호 리소스 메타데이터)을 얹는다. 현재 401 응답에 이미 `WWW-Authenticate` 를 보낸다.
