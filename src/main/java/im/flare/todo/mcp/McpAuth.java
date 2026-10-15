package im.flare.todo.mcp;

import im.flare.todo.entity.User;
import im.flare.todo.security.ApiScope;
import im.flare.todo.security.ApiTokenAuthentication;
import im.flare.todo.service.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** MCP 도구에서 호출 사용자와 스코프를 확인한다. */
@Component
@RequiredArgsConstructor
public class McpAuth {

    private final UserService userService;

    /** 인증 사용자를 돌려준다. 요구 스코프가 없으면 예외. */
    public User requireUser(McpTransportContext ctx, ApiScope required) {
        Authentication auth = ctx == null ? null : (Authentication) ctx.get(McpServerConfig.AUTH_KEY);
        if (auth == null || !auth.isAuthenticated()) {
            throw new McpAuthException("인증이 필요합니다. Authorization: Bearer <API 토큰> 헤더를 보내 주세요.");
        }
        boolean allowed = auth instanceof ApiTokenAuthentication pat
                ? pat.hasScope(required)
                : auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(required.authority()));
        if (!allowed) {
            throw new McpAuthException("이 토큰에는 '%s' 스코프가 없습니다. 웹의 API 토큰 관리에서 스코프를 포함해 다시 발급해 주세요."
                    .formatted(required.value()));
        }
        return userService.findByUsername(auth.getName());
    }

    public static class McpAuthException extends RuntimeException {
        public McpAuthException(String message) {
            super(message);
        }
    }
}
