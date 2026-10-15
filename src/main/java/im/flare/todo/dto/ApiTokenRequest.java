package im.flare.todo.dto;

import lombok.Data;

import java.util.List;

@Data
public class ApiTokenRequest {
    private String name;
    /** null 이면 만료 없음 */
    private Integer expiresInDays;
    /** 예: ["todos:read", "todos:write"]. null 이면 전체 */
    private List<String> scopes;
}
