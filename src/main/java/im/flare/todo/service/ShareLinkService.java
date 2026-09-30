package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.ShareLink;
import im.flare.todo.entity.User;
import im.flare.todo.repository.ShareLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShareLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository shareLinkRepository;
    private final AttachmentService attachmentService;
    private final S3Presigner s3Presigner;
    private final AttachmentProperties props;

    public record Created(Long id, String url, Instant expiresAt) {
    }

    /** 공유 링크 관리 목록 항목. todo 는 첨부 파일일 때만 있다(본문 이미지는 여러 할 일에서 참조될 수 있음). */
    public record Item(Long id, String url, Long attachmentId, String attachmentName, String attachmentKind,
                       Long todoId, String todoTitle, Instant createdAt, Instant expiresAt) {
    }

    /** 첨부의 외부 공유 링크를 만든다. days 는 실수(1.5 = 1일 12시간). */
    @Transactional
    public Created create(Long attachmentId, double days, User user) {
        if (!Double.isFinite(days) || days <= 0) {
            throw new IllegalArgumentException("유효기간은 0보다 커야 합니다.");
        }
        if (days > props.shareMaxDays()) {
            throw new IllegalArgumentException("유효기간은 최대 %d일까지 설정할 수 있습니다.".formatted(props.shareMaxDays()));
        }
        Duration validity = Duration.ofSeconds(Math.round(days * 86400));
        if (validity.toMinutes() < 1) throw new IllegalArgumentException("유효기간은 1분 이상이어야 합니다.");

        Attachment attachment = attachmentService.getOwnedAttachment(attachmentId, user);
        ShareLink link = shareLinkRepository.save(ShareLink.builder()
                .token(newToken())
                .attachment(attachment)
                .user(user)
                .expiresAt(Instant.now().plus(validity))
                .build());
        return new Created(link.getId(), urlOf(link), link.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<Item> listActive(User user) {
        return shareLinkRepository.findActiveByUser(user, Instant.now()).stream()
                .map(l -> {
                    Attachment a = l.getAttachment();
                    var todo = a.getTodo();
                    return new Item(l.getId(), urlOf(l), a.getId(), a.getOriginalName(), a.getKind().name(),
                            todo == null ? null : todo.getId(), todo == null ? null : todo.getTitle(),
                            l.getCreatedAt(), l.getExpiresAt());
                })
                .toList();
    }

    /** 공유 취소: 링크를 지우면 이후 접속은 만료 안내 페이지로 간다. 이미 발급된 S3 URL은 최대 share-redirect-ttl 동안 유효. */
    @Transactional
    public void revoke(Long id, User user) {
        ShareLink link = shareLinkRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("공유 링크를 찾을 수 없습니다."));
        shareLinkRepository.delete(link);
    }

    private String urlOf(ShareLink link) {
        return props.shareBaseUrl() + "/s/" + link.getToken();
    }

    /** 만료되지 않은 링크면 짧은 S3 서명 URL을 돌려준다. */
    @Transactional(readOnly = true)
    public Optional<String> resolve(String token) {
        return shareLinkRepository.findByTokenWithAttachment(token)
                .filter(l -> l.getExpiresAt().isAfter(Instant.now()))
                .map(l -> presign(l.getAttachment()));
    }

    private String presign(Attachment a) {
        // 파일은 원래 이름으로 다운로드, 이미지는 브라우저에서 바로 보이도록
        String disposition = a.getKind() == Attachment.Kind.FILE
                ? "attachment; filename*=UTF-8''" + URLEncoder.encode(a.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20")
                : "inline";
        return s3Presigner.presignGetObject(r -> r
                .signatureDuration(props.shareRedirectTtl())
                .getObjectRequest(g -> g.bucket(props.bucket()).key(a.getS3Key())
                        .responseContentDisposition(disposition)))
                .url().toString();
    }

    // 256비트 무작위 토큰 (URL-safe base64, 43자)
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
