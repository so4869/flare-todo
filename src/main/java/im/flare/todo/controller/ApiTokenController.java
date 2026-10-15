package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.ApiTokenRequest;
import im.flare.todo.dto.ApiTokenResponse;
import im.flare.todo.entity.User;
import im.flare.todo.security.ApiScope;
import im.flare.todo.security.ApiTokenAuthentication;
import im.flare.todo.service.ApiTokenService;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 개인 액세스 토큰(PAT) 관리. 웹 로그인(JWT)으로만 접근할 수 있다 — SecurityConfig 에서 ROLE_WEB 을 요구하고,
 * 혹시 다른 경로로 들어와도 아래에서 다시 막는다 (PAT 로 PAT 를 만드는 권한 상승 방지).
 */
@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService apiTokenService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ApiTokenResponse>>> list(Authentication auth) {
        if (auth instanceof ApiTokenAuthentication) return forbidden();
        User user = userService.findByUsername(auth.getName());
        return ResponseEntity.ok(ApiResponse.success(
                apiTokenService.list(user).stream().map(ApiTokenResponse::from).toList()));
    }

    @GetMapping("/scopes")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> scopes() {
        return ResponseEntity.ok(ApiResponse.success(ApiScope.all().stream()
                .map(s -> Map.of("value", s.value(), "description", s.description()))
                .toList()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ApiTokenResponse>> create(@RequestBody ApiTokenRequest request,
                                                                Authentication auth) {
        if (auth instanceof ApiTokenAuthentication) return forbidden();
        try {
            User user = userService.findByUsername(auth.getName());
            Set<ApiScope> scopes = request.getScopes() == null ? ApiScope.all()
                    : request.getScopes().stream().map(ApiScope::of)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            ApiTokenService.Created created = apiTokenService.create(
                    user, request.getName(), request.getExpiresInDays(), scopes);
            return ResponseEntity.ok(ApiResponse.success(
                    "토큰이 발급되었습니다. 이 값은 다시 볼 수 없으니 지금 복사해 두세요.", ApiTokenResponse.from(created)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable Long id, Authentication auth) {
        if (auth instanceof ApiTokenAuthentication) return forbidden();
        try {
            User user = userService.findByUsername(auth.getName());
            apiTokenService.revoke(id, user);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> forbidden() {
        return ResponseEntity.status(403).body(ApiResponse.error("API 토큰으로는 토큰을 관리할 수 없습니다. 웹에서 로그인해 주세요."));
    }
}
