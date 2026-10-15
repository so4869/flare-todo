package im.flare.todo.dto;

import im.flare.todo.entity.ApiToken;
import im.flare.todo.security.ApiScope;
import im.flare.todo.service.ApiTokenService;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class ApiTokenResponse {
    private Long id;
    private String name;
    private String prefix;
    private List<String> scopes;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant createdAt;
    private boolean expired;
    /** 발급 직후 응답에만 들어가는 평문 토큰. 목록에서는 null */
    private String token;

    public static ApiTokenResponse from(ApiToken t) {
        ApiTokenResponse r = new ApiTokenResponse();
        r.setId(t.getId());
        r.setName(t.getName());
        r.setPrefix(t.getPrefix());
        r.setScopes(ApiScope.parse(t.getScopes()).stream().map(ApiScope::value).toList());
        r.setExpiresAt(t.getExpiresAt());
        r.setLastUsedAt(t.getLastUsedAt());
        r.setCreatedAt(t.getCreatedAt());
        r.setExpired(t.isExpired(Instant.now()));
        return r;
    }

    public static ApiTokenResponse from(ApiTokenService.Created c) {
        ApiTokenResponse r = new ApiTokenResponse();
        r.setId(c.id());
        r.setName(c.name());
        r.setPrefix(c.prefix());
        r.setScopes(c.scopes().stream().map(ApiScope::value).toList());
        r.setExpiresAt(c.expiresAt());
        r.setCreatedAt(c.createdAt());
        r.setToken(c.token());
        return r;
    }
}
