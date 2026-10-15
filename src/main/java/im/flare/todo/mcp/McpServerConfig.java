package im.flare.todo.mcp;

import im.flare.todo.config.SecurityConfig;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * MCP Streamable HTTP 전송 설정.
 * <p>
 * Spring Security 는 요청 스레드의 ThreadLocal 에 인증 정보를 두지만, MCP SDK 는 동기 도구를
 * 별도 스레드(boundedElastic)에서 실행하므로 도구 안에서는 SecurityContextHolder 가 비어 있다.
 * 그래서 요청을 받는 시점에 Authentication 을 꺼내 McpTransportContext 에 실어 두고,
 * 도구는 {@link McpAuth} 로 그 컨텍스트에서 사용자를 꺼낸다.
 */
@Configuration
public class McpServerConfig {

    /** McpTransportContext 에서 Spring Security Authentication 을 꺼내는 키 */
    public static final String AUTH_KEY = "flare.auth";

    @Bean
    public McpTransportContextExtractor<ServerRequest> securityContextExtractor() {
        return request -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return McpTransportContext.EMPTY;
            return McpTransportContext.create(Map.of(AUTH_KEY, auth));
        };
    }

    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper,
            McpServerStreamableHttpProperties props,
            McpTransportContextExtractor<ServerRequest> contextExtractor) {
        String endpoint = props.getMcpEndpoint();
        if (!SecurityConfig.MCP_ENDPOINT.equals(endpoint)) {
            throw new IllegalStateException("MCP 엔드포인트(%s)가 SecurityConfig.MCP_ENDPOINT(%s)와 다릅니다. 보호 범위가 어긋납니다."
                    .formatted(endpoint, SecurityConfig.MCP_ENDPOINT));
        }
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(endpoint)
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(contextExtractor)
                .build();
    }
}
