package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 첨부 외부 공유 링크. https://todo.flare.im/s/{token} 으로 접근하면
 * 만료 전일 때 짧은 S3 서명 URL을 만들어 리다이렉트한다.
 */
@Entity
@Table(
    name = "share_links",
    uniqueConstraints = @UniqueConstraint(name = "uk_share_link_token", columnNames = "token"),
    indexes = {
        @Index(name = "idx_share_link_attachment", columnList = "attachment_id"),
        @Index(name = "idx_share_link_expires_at", columnList = "expires_at"),
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id", nullable = false)
    private Attachment attachment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
