package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.AttachmentZip;
import im.flare.todo.entity.Todo;
import im.flare.todo.repository.AttachmentZipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 할 일 첨부 전체 zip.
 * <p>
 * 첨부 S3 키는 내용이 바뀌지 않으므로(파일 UUID, 이미지 내용 해시) zip 에 들어갈 목록(키·크기·파일명)과 제목을
 * 해시한 manifestHash 가 같으면 zip 내용도 같다. 저장된 해시와 같으면 만들어 둔 zip 을 그대로 쓰고,
 * 다르면(첨부 추가·삭제, 본문 이미지 변경 등) 새로 만들어 올린 뒤 이전 zip 을 지운다.
 * 키에 해시가 들어가 CloudFront 캐시가 이전 zip 을 내려줄 일도 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentZipService {

    private static final String CACHE_CONTROL = "max-age=31536000, immutable";

    private final AttachmentService attachmentService;
    private final AttachmentZipRepository zipRepository;
    private final S3Client s3Client;
    private final AttachmentProperties props;

    // 같은 zip 을 동시에 두 번 만들지 않도록
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public record ZipInfo(String url, String fileName, long size, int fileCount, boolean cached) {
    }

    /** 최신 zip 이 있으면 그대로, 없으면 만들어 올린 뒤 CDN 주소를 돌려준다. */
    public ZipInfo prepare(Todo todo) {
        List<Attachment> attachments = attachmentService.getTodoAttachments(todo);
        if (attachments.isEmpty()) throw new IllegalArgumentException("첨부파일이 없습니다.");
        List<String> names = entryNames(attachments);
        String fileName = zipFileName(todo);
        String hash = manifestHash(fileName, attachments, names);

        Object lock = locks.computeIfAbsent(todo.getId(), k -> new Object());
        synchronized (lock) {
            try {
                Optional<AttachmentZip> existing = zipRepository.findById(todo.getId());
                if (existing.isPresent() && existing.get().getManifestHash().equals(hash)) {
                    return toInfo(existing.get(), true);
                }
                AttachmentZip zip = build(todo, attachments, names, fileName, hash);
                existing.ifPresent(old -> deleteObject(old.s3Key()));
                return toInfo(zip, false);
            } finally {
                locks.remove(todo.getId());
            }
        }
    }

    /** 할 일 삭제 시 zip 도 지운다. */
    public void delete(Todo todo) {
        zipRepository.findById(todo.getId()).ifPresent(zip -> {
            deleteObject(zip.s3Key());
            zipRepository.delete(zip);
        });
    }

    private AttachmentZip build(Todo todo, List<Attachment> attachments, List<String> names, String fileName, String hash) {
        Long userId = todo.getUser().getId();
        String key = AttachmentZip.keyOf(userId, todo.getId(), hash);
        Path tmp = null;
        try {
            tmp = Files.createTempFile("todo-zip-", ".zip");
            try (OutputStream out = Files.newOutputStream(tmp);
                 ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                // 첨부는 대부분 이미 압축된 형식(이미지·PDF·오피스)이라 압축률보다 속도를 택한다
                zos.setLevel(Deflater.BEST_SPEED);
                for (int i = 0; i < attachments.size(); i++) {
                    zos.putNextEntry(new ZipEntry(names.get(i)));
                    String s3Key = attachments.get(i).getS3Key();
                    try (InputStream in = s3Client.getObject(b -> b.bucket(props.bucket()).key(s3Key))) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
            long size = Files.size(tmp);
            s3Client.putObject(b -> b.bucket(props.bucket()).key(key)
                            .contentType("application/zip")
                            .contentDisposition(AttachmentService.contentDisposition("attachment", fileName))
                            .cacheControl(CACHE_CONTROL),
                    RequestBody.fromFile(tmp));
            log.info("첨부 zip 생성 todo={} files={} size={}", todo.getId(), attachments.size(), size);
            return zipRepository.save(AttachmentZip.builder()
                    .todoId(todo.getId()).userId(userId).manifestHash(hash)
                    .fileName(fileName).size(size).fileCount(attachments.size())
                    .createdAt(Instant.now())
                    .build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("임시 zip 삭제 실패 {}", tmp);
                }
            }
        }
    }

    private ZipInfo toInfo(AttachmentZip zip, boolean cached) {
        return new ZipInfo(props.cdnBaseUrl() + "/" + zip.s3Key(), zip.getFileName(), zip.getSize(), zip.getFileCount(), cached);
    }

    private void deleteObject(String key) {
        try {
            s3Client.deleteObject(b -> b.bucket(props.bucket()).key(key));
        } catch (SdkException e) {
            log.warn("첨부 zip 삭제 실패 key={}: {}", key, e.getMessage());
        }
    }

    private static String manifestHash(String fileName, List<Attachment> attachments, List<String> names) {
        StringBuilder sb = new StringBuilder(fileName).append('\n');
        for (int i = 0; i < attachments.size(); i++) {
            Attachment a = attachments.get(i);
            sb.append(a.getS3Key()).append('\t').append(a.getSize()).append('\t').append(names.get(i)).append('\n');
        }
        return AttachmentService.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // zip 안에서 이름이 겹치면 "이름 (2).확장자" 로 구분한다
    private static List<String> entryNames(List<Attachment> attachments) {
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (Attachment a : attachments) {
            String name = a.getOriginalName().replaceAll("[\\\\/]", "_");
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            String candidate = name;
            for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
                candidate = "%s (%d)%s".formatted(base, n, ext);
            }
            names.add(candidate);
        }
        return names;
    }

    private static String zipFileName(Todo todo) {
        String title = Optional.ofNullable(todo.getTitle()).orElse("")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (title.length() > 100) title = title.substring(0, 100).strip();
        return (title.isEmpty() ? "todo-" + todo.getId() : title) + ".zip";
    }
}
