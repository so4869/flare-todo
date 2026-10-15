package im.flare.todo.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API 토큰 스코프. Spring Security 권한으로는 "SCOPE_" 접두어를 붙여 표현한다.
 * 웹 로그인(JWT)은 모든 스코프를 가진다.
 */
public enum ApiScope {
    TODOS_READ("todos:read", "할 일·카테고리 읽기"),
    TODOS_WRITE("todos:write", "할 일·카테고리 추가·수정·삭제");

    public static final String AUTHORITY_PREFIX = "SCOPE_";

    private final String value;
    private final String description;

    ApiScope(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String value() {
        return value;
    }

    public String description() {
        return description;
    }

    public String authority() {
        return AUTHORITY_PREFIX + value;
    }

    public static ApiScope of(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 스코프입니다: " + value));
    }

    public static Set<ApiScope> all() {
        return new LinkedHashSet<>(Arrays.asList(values()));
    }

    /** "todos:read todos:write" 형태의 문자열로 */
    public static String join(Set<ApiScope> scopes) {
        return scopes.stream().map(ApiScope::value).collect(Collectors.joining(" "));
    }

    public static Set<ApiScope> parse(String joined) {
        if (joined == null || joined.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(joined.trim().split("\\s+")).map(ApiScope::of)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
