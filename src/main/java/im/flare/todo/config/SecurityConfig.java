package im.flare.todo.config;

import im.flare.todo.security.ApiScope;
import im.flare.todo.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** MCP(Streamable HTTP) 엔드포인트. application.yaml 의 spring.ai.mcp.server.streamable-http.mcp-endpoint 와 같아야 한다. */
    public static final String MCP_ENDPOINT = "/mcp";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        String read = ApiScope.TODOS_READ.authority();
        String write = ApiScope.TODOS_WRITE.authority();
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // PAT 관리는 웹 로그인(JWT)으로만. PAT 로 PAT 를 만드는 권한 상승 방지
                        .requestMatchers("/api/tokens/**").hasAuthority(JwtAuthenticationFilter.ROLE_WEB)
                        // REST API: PAT 는 스코프대로, JWT 는 모든 스코프를 가지므로 기존 동작 그대로
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAuthority(read)
                        .requestMatchers(HttpMethod.HEAD, "/api/**").hasAuthority(read)
                        .requestMatchers("/api/**").hasAuthority(write)
                        // MCP: 인증만 요구하고 도구별 스코프는 도구 안에서 검사
                        .requestMatchers(MCP_ENDPOINT, MCP_ENDPOINT + "/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            // MCP 인증 스펙: 401 에는 WWW-Authenticate 를 함께 보낸다 (OAuth 도입 시 resource_metadata 추가)
                            response.setHeader("WWW-Authenticate", "Bearer realm=\"flare-todo\"");
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized\",\"data\":null}");
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"message\":\"이 토큰에는 해당 작업 권한(스코프)이 없습니다.\",\"data\":null}");
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
