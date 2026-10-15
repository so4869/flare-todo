package im.flare.todo.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

/**
 * 개인 액세스 토큰(PAT)으로 인증된 요청. principal 은 username.
 * 웹 로그인(JWT)과 구분해야 하는 곳(토큰 관리 API 등)에서 instanceof 로 식별한다.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    private final String username;
    private final Long tokenId;
    private final Set<ApiScope> scopes;

    public ApiTokenAuthentication(String username, Long tokenId, Set<ApiScope> scopes) {
        super(toAuthorities(scopes));
        this.username = username;
        this.tokenId = tokenId;
        this.scopes = scopes;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> toAuthorities(Set<ApiScope> scopes) {
        return scopes.stream().<GrantedAuthority>map(s -> new SimpleGrantedAuthority(s.authority())).toList();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    @Override
    public String getName() {
        return username;
    }

    public Long getTokenId() {
        return tokenId;
    }

    public Set<ApiScope> getScopes() {
        return scopes;
    }

    public boolean hasScope(ApiScope scope) {
        return scopes.contains(scope);
    }
}
