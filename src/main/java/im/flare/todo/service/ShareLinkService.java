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
    private final SettingService settingService;

    /**
     * 생성된 공유 링크. direct=false 면 앱 링크(/s/{token}, 취소 가능)이고 id 가 있다.
     * direct=true 면 S3 서명 URL 그 자체라 DB에 남지 않고 취소할 수 없다(id=null).
     */
    public record Created(Long id, String url, Instant expiresAt, boolean direct) {
    }

    /** 공유 링크 관리 목록 항목. todo 는 첨부 파일일 때만 있다(본문 이미지는 여러 할 일에서 참조될 수 있음). */
    public record Item(Long id, String url, Long attachmentId, String attachmentName, String attachmentKind,
                       Long todoId, String todoTitle, Instant createdAt, Instant expiresAt) {
    }

    /** 첨부의 외부 공유 링크를 만든다. days 는 실수(1.5 = 1일 12시간). */
    @Transactional
    public Created create(Long attachmentId, double days, User user) {
        Duration validity = toValidity(days, props.shareMaxDays());
        Attachment attachment = attachmentService.getOwnedAttachment(attachmentId, user);
        ShareLink link = shareLinkRepository.save(ShareLink.builder()
                .token(newToken())
                .attachment(attachment)
                .user(user)
                .expiresAt(Instant.now().plus(validity))
                .build());
        return new Created(link.getId(), urlOf(link), link.getExpiresAt(), false);
    }

    /**
     * 앱을 거치지 않는 S3 서명 URL을 바로 만든다. days 가 null 이면 DB 설정(share.presigned-days, 기본 7일)을 쓴다.
     * S3 SigV4 한계로 최대 7일이며, DB에 저장하지 않으므로 취소할 수 없다.
     */
    @Transactional(readOnly = true)
    public Created createDirect(Long attachmentId, Double days, User user) {
        double d = days == null ? settingService.getSharePresignedDays() : days;
        Duration validity = toValidity(d, SettingService.SHARE_PRESIGNED_MAX_DAYS);
        Attachment attachment = attachmentService.getOwnedAttachment(attachmentId, user);
        Instant expiresAt = Instant.now().plus(validity);
        return new Created(null, presign(attachment, validity), expiresAt, true);
    }

    private static Duration toValidity(double days, double maxDays) {
        if (!Double.isFinite(days) || days <= 0) {
            throw new IllegalArgumentException("유효기간은 0보다 커야 합니다.");
        }
        if (days > maxDays) {
            String max = maxDays == Math.rint(maxDays) ? String.valueOf((long) maxDays) : String.valueOf(maxDays);
            throw new IllegalArgumentException("유효기간은 최대 %s일까지 설정할 수 있습니다.".formatted(max));
        }
        Duration validity = Duration.ofSeconds(Math.round(days * 86400));
        if (validity.toMinutes() < 1) throw new IllegalArgumentException("유효기간은 1분 이상이어야 합니다.");
        return validity;
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
                .map(l -> presign(l.getAttachment(), props.shareRedirectTtl()));
    }

    /** MCP 등에서 쓰는 1회성 다운로드 URL. DB에 남지 않고 S3 SigV4 한계(7일) 안에서 ttl 만큼만 유효하다. */
    @Transactional(readOnly = true)
    public String downloadUrl(Long attachmentId, Duration ttl, User user) {
        if (ttl == null || ttl.toMinutes() < 1) throw new IllegalArgumentException("유효기간은 1분 이상이어야 합니다.");
        if (ttl.toDays() > SettingService.SHARE_PRESIGNED_MAX_DAYS) {
            throw new IllegalArgumentException("유효기간은 최대 %d일까지 설정할 수 있습니다.".formatted((long) SettingService.SHARE_PRESIGNED_MAX_DAYS));
        }
        return presign(attachmentService.getOwnedAttachment(attachmentId, user), ttl);
    }

    private String presign(Attachment a, Duration ttl) {
        // 파일은 원래 이름으로 다운로드, 이미지는 브라우저에서 바로 보이도록
        String disposition = a.getKind() == Attachment.Kind.FILE
                ? "attachment; filename*=UTF-8''" + URLEncoder.encode(a.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20")
                : "inline";
        return s3Presigner.presignGetObject(r -> r
                .signatureDuration(ttl)
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
