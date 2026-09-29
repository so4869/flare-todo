package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * S3에 저장된 첨부 파일.
 * <ul>
 *   <li>FILE  : 할 일에 첨부한 파일. 저장 전 업로드되므로 todo 는 저장 시점에 연결된다.</li>
 *   <li>IMAGE : 본문에 삽입된 이미지. 내용 해시로 키를 만들어 같은 이미지는 한 번만 저장하며,
 *               여러 할 일 본문에서 참조될 수 있으므로 todo 와 연결하지 않고 본문 참조 여부로 정리한다.</li>
 * </ul>
 */
@Entity
@Table(
    name = "attachments",
    uniqueConstraints = @UniqueConstraint(name = "uk_attachment_s3_key", columnNames = "s3_key"),
    indexes = {
        @Index(name = "idx_attachment_todo", columnList = "todo_id"),
        // 정리 배치: 종류별 오래된 첨부 조회
        @Index(name = "idx_attachment_kind_created", columnList = "kind, created_at"),
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {

    public enum Kind { FILE, IMAGE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "todo_id")
    private Todo todo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false, length = 150)
    private String contentType;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
