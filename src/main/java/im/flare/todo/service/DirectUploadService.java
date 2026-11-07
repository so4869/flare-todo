package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.PendingUpload;
import im.flare.todo.entity.User;
import im.flare.todo.repository.PendingUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 첨부 파일을 클라이언트가 S3로 직접 올리는 멀티파트 업로드.
 * <ol>
 *   <li>{@link #start}: 크기 검사 후 CreateMultipartUpload. 파트 크기·개수를 돌려준다.</li>
 *   <li>{@link #signParts}: 파트별 PUT 서명 URL (1시간). 클라이언트는 파일 조각을 이 URL로 바로 PUT 한다.</li>
 *   <li>{@link #complete}: S3 ListParts 로 실제 올라간 파트를 확인해 CompleteMultipartUpload 후 첨부로 등록한다.
 *       ETag 를 서버가 직접 읽으므로 버킷 CORS 에 ETag 노출이 없어도 된다.</li>
 *   <li>{@link #abort}: 사용자가 취소하거나 실패했을 때. 오래 방치된 업로드는 정리 배치가 취소한다.</li>
 * </ol>
 * 파일 내용이 앱 서버와 로드밸런서를 거치지 않으므로 큰 파일도 유휴 타임아웃·요청 크기 제한에 걸리지 않는다.
 */
@Slf4j
@Service
public class DirectUploadService {

    static final long MIN_PART_SIZE = 8L << 20;       // S3 최소 5MB(마지막 파트 제외). 재시도 단위를 작게 유지
    static final int MAX_PARTS = 10_000;              // S3 한계
    static final int MAX_SIGN_BATCH = 100;
    static final Duration PART_URL_TTL = Duration.ofHours(1);
    static final Duration ABANDON_AFTER = Duration.ofDays(1);

    private final PendingUploadRepository pendingUploadRepository;
    private final AttachmentService attachmentService;
    private final S3Client s3Client;
    private final S3Presigner uploadPresigner;
    private final AttachmentProperties props;

    public DirectUploadService(PendingUploadRepository pendingUploadRepository,
                               AttachmentService attachmentService,
                               S3Client s3Client,
                               @Qualifier("s3UploadPresigner") S3Presigner uploadPresigner,
                               AttachmentProperties props) {
        this.pendingUploadRepository = pendingUploadRepository;
        this.attachmentService = attachmentService;
        this.s3Client = s3Client;
        this.uploadPresigner = uploadPresigner;
        this.props = props;
    }

    public record Started(Long uploadId, long partSize, int partCount) {
    }

    public record SignedPart(int partNumber, String url) {
    }

    @Transactional
    public Started start(User user, String originalName, long size, String rawContentType) {
        if (size <= 0) throw new IllegalArgumentException("빈 파일은 첨부할 수 없습니다.");
        attachmentService.checkSize(size);
        String name = AttachmentService.sanitizeFileName(originalName);
        String contentType = AttachmentService.normalizeContentType(rawContentType);
        String key = AttachmentService.newFileKey(user);
        long partSize = partSizeFor(size);
        int partCount = (int) ((size + partSize - 1) / partSize);

        // 헤더는 시작 시점에 고정된다. 파일은 형식과 무관하게 항상 다운로드로 내려보낸다 (CDN 도메인에서 HTML/SVG 실행 방지).
        String s3UploadId = s3Client.createMultipartUpload(b -> b.bucket(props.bucket()).key(key)
                .contentType(contentType)
                .contentDisposition(AttachmentService.contentDisposition("attachment", name))
                .cacheControl(AttachmentService.FILE_CACHE_CONTROL)).uploadId();

        PendingUpload upload = pendingUploadRepository.save(PendingUpload.builder()
                .user(user).s3UploadId(s3UploadId).s3Key(key)
                .originalName(name).contentType(contentType).size(size)
                .partSize(partSize).partCount(partCount)
                .build());
        return new Started(upload.getId(), partSize, partCount);
    }

    @Transactional(readOnly = true)
    public List<SignedPart> signParts(Long uploadId, Collection<Integer> partNumbers, User user) {
        PendingUpload upload = get(uploadId, user);
        if (partNumbers == null || partNumbers.isEmpty()) throw new IllegalArgumentException("파트 번호가 없습니다.");
        if (partNumbers.size() > MAX_SIGN_BATCH) {
            throw new IllegalArgumentException("한 번에 %d개 파트까지 요청할 수 있습니다.".formatted(MAX_SIGN_BATCH));
        }
        List<SignedPart> result = new ArrayList<>();
        for (Integer n : partNumbers) {
            if (n == null || n < 1 || n > upload.getPartCount()) throw new IllegalArgumentException("잘못된 파트 번호입니다.");
            String url = uploadPresigner.presignUploadPart(p -> p.signatureDuration(PART_URL_TTL)
                    .uploadPartRequest(r -> r.bucket(props.bucket()).key(upload.getS3Key())
                            .uploadId(upload.getS3UploadId()).partNumber(n)))
                    .url().toString();
            result.add(new SignedPart(n, url));
        }
        return result;
    }

    /** 올라간 파트를 S3에서 확인해 업로드를 마무리하고 첨부(FILE)로 등록한다. */
    @Transactional
    public Attachment complete(Long uploadId, User user) {
        PendingUpload upload = get(uploadId, user);
        List<Part> parts = new ArrayList<>();
        try {
            s3Client.listPartsPaginator(b -> b.bucket(props.bucket()).key(upload.getS3Key())
                    .uploadId(upload.getS3UploadId())).parts().forEach(parts::add);
        } catch (NoSuchUploadException e) {
            pendingUploadRepository.delete(upload);
            throw new IllegalArgumentException("업로드가 만료되었거나 취소되었습니다. 다시 올려 주세요.");
        }
        parts.sort(Comparator.comparing(Part::partNumber));

        long total = parts.stream().mapToLong(Part::size).sum();
        boolean contiguous = parts.size() == upload.getPartCount();
        for (int i = 0; contiguous && i < parts.size(); i++) {
            contiguous = parts.get(i).partNumber() == i + 1;
        }
        if (!contiguous || total != upload.getSize()) {
            // 아직 올라가는 중일 수 있으므로 취소하지 않는다. 클라이언트가 빠진 파트를 다시 올리거나 취소한다.
            throw new IllegalArgumentException("파일 조각이 모두 올라가지 않았습니다. (%d/%d개)"
                    .formatted(parts.size(), upload.getPartCount()));
        }

        List<CompletedPart> completed = parts.stream()
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build())
                .toList();
        s3Client.completeMultipartUpload(b -> b.bucket(props.bucket()).key(upload.getS3Key())
                .uploadId(upload.getS3UploadId())
                .multipartUpload(m -> m.parts(completed)));

        pendingUploadRepository.delete(upload);
        return attachmentService.saveFile(user, upload.getS3Key(), upload.getOriginalName(),
                upload.getContentType(), total);
    }

    @Transactional
    public void abort(Long uploadId, User user) {
        abort(get(uploadId, user));
    }

    /** 하루 넘게 끝나지 않은 업로드를 취소한다 (올라간 파트의 저장 비용 방지). */
    @Scheduled(cron = "${app.attachment.upload-cleanup-cron:0 45 4 * * *}")
    @Transactional
    public void cleanupAbandoned() {
        List<PendingUpload> stale = pendingUploadRepository.findByCreatedAtBefore(Instant.now().minus(ABANDON_AFTER));
        stale.forEach(this::abort);
        if (!stale.isEmpty()) log.info("방치된 직접 업로드 {}건 취소", stale.size());
    }

    private void abort(PendingUpload upload) {
        try {
            s3Client.abortMultipartUpload(b -> b.bucket(props.bucket()).key(upload.getS3Key())
                    .uploadId(upload.getS3UploadId()));
        } catch (NoSuchUploadException e) {
            // 이미 끝났거나 취소됨
        } catch (SdkException e) {
            // 행은 지운다. 남은 파트는 버킷 수명 주기 규칙(AbortIncompleteMultipartUpload)이 정리한다.
            log.warn("직접 업로드 취소 실패 key={}: {}", upload.getS3Key(), e.getMessage());
        }
        pendingUploadRepository.delete(upload);
    }

    private PendingUpload get(Long uploadId, User user) {
        return pendingUploadRepository.findByIdAndUser(uploadId, user)
                .orElseThrow(() -> new IllegalArgumentException("업로드를 찾을 수 없습니다. 다시 올려 주세요."));
    }

    /** 최소 8MB, 파트가 1만 개를 넘지 않도록 1MB 단위로 키운다. */
    static long partSizeFor(long size) {
        long needed = (size + MAX_PARTS - 1) / MAX_PARTS;
        long mib = 1L << 20;
        long rounded = (needed + mib - 1) / mib * mib;
        return Math.max(MIN_PART_SIZE, rounded);
    }
}
