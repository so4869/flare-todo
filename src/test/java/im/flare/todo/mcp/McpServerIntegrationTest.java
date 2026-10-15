package im.flare.todo.mcp;

import im.flare.todo.dto.RegisterRequest;
import im.flare.todo.entity.User;
import im.flare.todo.security.ApiScope;
import im.flare.todo.service.ApiTokenService;
import im.flare.todo.service.UserService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * 실제 /mcp 엔드포인트를 MCP Java 클라이언트로 호출해 PAT 인증·스코프·사용자 격리가 끝까지 동작하는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    UserService userService;
    @Autowired
    ApiTokenService apiTokenService;

    String fullToken;      // 읽기+쓰기
    String readOnlyToken;  // 읽기만
    String otherUserToken; // 다른 사용자
    String jwtBearer;      // 웹 로그인 JWT

    @BeforeAll
    void setUp() {
        User alice = register("alice");
        User bob = register("bob");
        fullToken = apiTokenService.create(alice, "full", null, ApiScope.all()).token();
        readOnlyToken = apiTokenService.create(alice, "ro", 30, Set.of(ApiScope.TODOS_READ)).token();
        otherUserToken = apiTokenService.create(bob, "bob", null, ApiScope.all()).token();

        Map<?, ?> login = rest().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "alice", "password", "password-1234"))
                .retrieve().body(Map.class);
        jwtBearer = (String) ((Map<?, ?>) login.get("data")).get("accessToken");
    }

    private User register(String name) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(name);
        r.setEmail(name + "@test.local");
        r.setPassword("password-1234");
        return userService.register(r);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private RestClient rest() {
        return RestClient.builder().baseUrl(baseUrl()).build();
    }

    private McpSyncClient mcpClient(String bearer) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
                    if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 도구 결과(JSON 텍스트 콘텐츠)를 Map 으로 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult r) {
        assertThat(r.isError()).as("tool error: " + r.content()).isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) r.content().get(0)).text();
        return JSON.readValue(text, Map.class);
    }

    @Test
    void mcpWithoutTokenIs401WithWwwAuthenticate() {
        assertThatThrownBy(() -> rest().post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
                });
    }

    @Test
    void fullTokenCanListAndCreateAndReadBack() {
        try (McpSyncClient client = mcpClient(fullToken)) {
            List<String> tools = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertThat(tools).contains("list_todos", "get_todo", "create_todo", "update_todo", "complete_todo",
                    "delete_todo", "list_categories", "create_category");

            Map<String, Object> cat = structured(client.callTool(
                    new McpSchema.CallToolRequest("create_category", Map.of("name", "업무"))));
            Number catId = (Number) cat.get("id");

            Map<String, Object> created = structured(client.callTool(new McpSchema.CallToolRequest("create_todo",
                    Map.of("title", "MCP 로 만든 할 일", "body", "첫 줄\n\n둘째 단락 <b>", "categoryIds", List.of(catId)))));
            Number todoId = (Number) created.get("id");
            assertThat(created.get("body")).isEqualTo("첫 줄\n둘째 단락 <b>");
            assertThat((List<Object>) created.get("categories")).containsExactly("업무");

            Map<String, Object> done = structured(client.callTool(
                    new McpSchema.CallToolRequest("complete_todo", Map.of("id", todoId))));
            assertThat(done.get("completed")).isEqualTo(true);

            Map<String, Object> detail = structured(client.callTool(
                    new McpSchema.CallToolRequest("get_todo", Map.of("id", todoId))));
            assertThat(detail.get("title")).isEqualTo("MCP 로 만든 할 일");
            assertThat(detail.get("completed")).isEqualTo(true);

            // 다른 사용자는 이 할 일을 볼 수 없다
            try (McpSyncClient bob = mcpClient(otherUserToken)) {
                McpSchema.CallToolResult r = bob.callTool(new McpSchema.CallToolRequest("get_todo", Map.of("id", todoId)));
                assertThat(r.isError()).isTrue();
                List<String> bobTitles = bob.callTool(new McpSchema.CallToolRequest("list_todos", Map.of()))
                        .content().stream().map(Object::toString).toList();
                assertThat(String.join("", bobTitles)).doesNotContain("MCP 로 만든 할 일");
            }
        }
    }

    @Test
    void readOnlyTokenCannotWrite() {
        try (McpSyncClient client = mcpClient(readOnlyToken)) {
            McpSchema.CallToolResult ok = client.callTool(new McpSchema.CallToolRequest("list_todos", Map.of()));
            assertThat(ok.isError()).isNotEqualTo(Boolean.TRUE);

            McpSchema.CallToolResult denied = client.callTool(
                    new McpSchema.CallToolRequest("create_todo", Map.of("title", "안 됨")));
            assertThat(denied.isError()).isTrue();
            assertThat(denied.content().toString()).contains("todos:write");
        }
    }

    @Test
    void patWorksOnRestApiByScopeButCannotManageTokens() {
        // 읽기 전용 PAT: GET 은 되고 POST 는 403
        ResponseEntity<String> list = rest().get().uri("/api/todos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnlyToken)
                .retrieve().toEntity(String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> rest().post().uri("/api/categories")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnlyToken)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "x"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // 모든 스코프를 가진 PAT 라도 토큰 관리는 불가 (권한 상승 방지)
        assertThatThrownBy(() -> rest().get().uri("/api/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fullToken)
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // 웹 JWT 로는 가능
        ResponseEntity<String> tokens = rest().get().uri("/api/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtBearer)
                .retrieve().toEntity(String.class);
        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokens.getBody()).contains("\"name\":\"full\"").doesNotContain(fullToken);
    }

    @Test
    void revokedTokenIsRejectedImmediately() {
        User alice = userService.findByUsername("alice");
        ApiTokenService.Created temp = apiTokenService.create(alice, "temp", null, ApiScope.all());
        ResponseEntity<String> ok = rest().get().uri("/api/todos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + temp.token())
                .retrieve().toEntity(String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        apiTokenService.revoke(temp.id(), alice);

        assertThatThrownBy(() -> rest().get().uri("/api/todos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + temp.token())
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
