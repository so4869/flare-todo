package im.flare.todo.security;

import im.flare.todo.entity.ApiToken;
import im.flare.todo.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Authorization: Bearer 헤더를 해석한다.
 * <ul>
 *   <li>"ft_..." → 개인 액세스 토큰(PAT). 토큰에 부여된 스코프만 권한으로 가진다.</li>
 *   <li>그 외 → 웹 로그인 JWT. 모든 스코프 + ROLE_WEB(토큰 관리 등 웹 전용 기능)을 가진다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 웹 로그인(JWT)으로 인증된 요청에만 붙는 권한. PAT 관리 API 가 이것을 요구한다. */
    public static final String ROLE_WEB = "ROLE_WEB";

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final ApiTokenService apiTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Authentication auth = ApiTokenService.looksLikeApiToken(token)
                    ? authenticateApiToken(token)
                    : authenticateJwt(token, request);
            if (auth != null) SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }

    private Authentication authenticateApiToken(String token) {
        Optional<ApiToken> found = apiTokenService.authenticate(token);
        if (found.isEmpty()) return null;
        ApiToken t = found.get();
        return new ApiTokenAuthentication(t.getUser().getUsername(), t.getId(), ApiScope.parse(t.getScopes()));
    }

    private Authentication authenticateJwt(String token, HttpServletRequest request) {
        if (!jwtUtil.isValid(token)) return null;
        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(jwtUtil.extractUsername(token));
        } catch (UsernameNotFoundException e) {
            return null;
        }
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, webAuthorities(userDetails));
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    static List<GrantedAuthority> webAuthorities(UserDetails userDetails) {
        List<GrantedAuthority> authorities = new ArrayList<>(userDetails.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(ROLE_WEB));
        for (ApiScope scope : ApiScope.values()) {
            authorities.add(new SimpleGrantedAuthority(scope.authority()));
        }
        return authorities;
    }
}
