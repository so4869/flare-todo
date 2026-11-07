package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 클라이언트가 S3로 직접 올리는 중인 멀티파트 업로드.
 * 서버는 업로드를 시작(CreateMultipartUpload)하고 파트별 서명 URL만 내주며, 파일 내용은 서버를 거치지 않는다.
 * 완료되면 {@link Attachment}(FILE)로 바뀌고 이 행은 지워진다. 끝나지 않은 업로드는 정리 배치가 취소한다.
 */
@Entity
@Table(
    name = "attachment_uploads",
    indexes = @Index(name = "idx_attachment_upload_created", columnList = "created_at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** S3 가 발급한 멀티파트 업로드 ID */
    @Column(name = "s3_upload_id", nullable = false, length = 1024)
    private String s3UploadId;

    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false, length = 150)
    private String contentType;

    /** 클라이언트가 알린 전체 크기. 완료 시 실제로 올라간 파트 크기 합과 같아야 한다. */
    @Column(nullable = false)
    private long size;

    @Column(nullable = false)
    private long partSize;

    @Column(nullable = false)
    private int partCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
