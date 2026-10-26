package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 할 일 첨부 전체를 묶은 zip (S3 u/{userId}/zip/{todoId}/{manifestHash}.zip).
 * manifestHash 는 zip 에 들어가는 첨부 목록(키·크기·파일명)과 제목의 해시라, 첨부가 바뀌면 값이 달라져 재생성된다.
 * Todo 의 updatedAt 을 건드리지 않도록 별도 테이블에 둔다.
 */
@Entity
@Table(name = "attachment_zips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttachmentZip {

    @Id
    private Long todoId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String manifestHash;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false)
    private int fileCount;

    @Column(nullable = false)
    private Instant createdAt;

    public String s3Key() {
        return keyOf(userId, todoId, manifestHash);
    }

    // CloudFront 서명 쿠키 정책 경로(/u/{userId}/*) 안에 두어 별도 서명 없이 받을 수 있게 한다.
    public static String keyOf(Long userId, Long todoId, String manifestHash) {
        return "u/%d/zip/%d/%s.zip".formatted(userId, todoId, manifestHash);
    }
}
