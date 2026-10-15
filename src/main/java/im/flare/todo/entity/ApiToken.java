package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 개인 액세스 토큰(PAT). MCP 클라이언트나 외부 스크립트가 로그인 JWT 대신 쓰는 장기 Bearer 토큰.
 * 평문은 발급 응답에 한 번만 내려가고 DB에는 SHA-256 해시만 남는다.
 */
@Entity
@Table(
    name = "api_tokens",
    uniqueConstraints = @UniqueConstraint(name = "uk_api_token_hash", columnNames = "token_hash"),
    indexes = @Index(name = "idx_api_token_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 표시용 이름 (예: "맥북 Claude Desktop") */
    @Column(nullable = false, length = 100)
    private String name;

    /** 토큰 평문의 SHA-256 (hex 64자) */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** 목록에서 식별용으로 보여주는 앞부분 (예: ft_a1b2c3d4) */
    @Column(nullable = false, length = 16)
    private String prefix;

    /** 공백으로 구분한 스코프 목록 (예: "todos:read todos:write") */
    @Column(nullable = false, length = 200)
    private String scopes;

    /** null 이면 만료 없음 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }
}
