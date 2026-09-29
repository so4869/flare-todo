package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import im.flare.todo.repository.AttachmentRepository;
import im.flare.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final Pattern DATA_URI = Pattern.compile("^data:image/[\\w.+-]+;base64,(.+)$", Pattern.DOTALL);
    // 내용 해시 기반 키라 내용이 바뀌지 않으므로 오래 캐시해도 된다.
    private static final String IMAGE_CACHE_CONTROL = "max-age=31536000, immutable";
    private static final String FILE_CACHE_CONTROL = "max-age=86400";

    private final AttachmentRepository attachmentRepository;
    private final TodoRepository todoRepository;
    private final S3Client s3Client;
    private final AttachmentProperties props;

    public String urlOf(Attachment a) {
        return props.cdnBaseUrl() + "/" + a.getS3Key();
    }

    /** 할 일에 첨부할 파일 업로드. 할 일 저장 시 {@link #syncTodoFiles}로 연결된다. */
    @Transactional
    public Attachment uploadFile(User user, MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("빈 파일은 첨부할 수 없습니다.");
        String name = sanitizeFileName(file.getOriginalFilename());
        String contentType = Optional.ofNullable(file.getContentType())
                .filter(t -> !t.isBlank()).orElse("application/octet-stream");
        String key = "u/%d/f/%s".formatted(user.getId(), UUID.randomUUID());

        // 파일은 형식과 무관하게 항상 다운로드로 내려보내 CDN 도메인에서 HTML/SVG 등이 실행되지 않게 한다.
        try (InputStream in = file.getInputStream()) {
            s3Client.putObject(b -> b.bucket(props.bucket()).key(key)
                            .contentType(contentType)
                            .contentDisposition(contentDisposition("attachment", name))
                            .cacheControl(FILE_CACHE_CONTROL),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return attachmentRepository.save(Attachment.builder()
                .user(user).kind(Attachment.Kind.FILE).s3Key(key)
                .originalName(name).contentType(contentType).size(file.getSize())
                .build());
    }

    /** 본문 이미지 업로드. 같은 사용자의 같은 이미지는 한 번만 저장된다(자동 저장 반복 시 중복 방지). */
    @Transactional
    public Attachment uploadImage(User user, byte[] data, String originalName) {
        if (data.length > props.maxInlineImageSize().toBytes()) {
            throw new IllegalArgumentException("이미지는 %dMB 이하만 첨부할 수 있습니다."
                    .formatted(props.maxInlineImageSize().toMegabytes()));
        }
        ImageType type = ImageType.detect(data)
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 이미지 형식입니다. (PNG, JPEG, GIF, WebP)"));
        String key = "u/%d/img/%s.%s".formatted(user.getId(), sha256Hex(data), type.ext);

        return attachmentRepository.findByS3Key(key).orElseGet(() -> {
            s3Client.putObject(b -> b.bucket(props.bucket()).key(key)
                            .contentType(type.mime)
                            .contentDisposition("inline")
                            .cacheControl(IMAGE_CACHE_CONTROL),
                    RequestBody.fromBytes(data));
            return attachmentRepository.save(Attachment.builder()
                    .user(user).kind(Attachment.Kind.IMAGE).s3Key(key)
                    .originalName(sanitizeFileName(originalName)).contentType(type.mime).size(data.length)
                    .build());
        });
    }

    /**
     * 본문에 base64(data URI)로 붙여넣은 이미지를 S3에 올리고 src 를 CDN URL로 바꾼다.
     * 파일 첨부를 막고 본문 붙여넣기는 허용하는 사내망에서도 이미지를 올릴 수 있게 하기 위함.
     */
    @Transactional
    public String processBodyImages(User user, String html) {
        if (html == null || !html.contains("data:image/")) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        for (Element img : doc.select("img[src^=data:]")) {
            Matcher m = DATA_URI.matcher(img.attr("src"));
            if (!m.matches()) continue;
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(m.group(1));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("본문 이미지 데이터가 올바르지 않습니다.");
            }
            String name = img.hasAttr("alt") && !img.attr("alt").isBlank() ? img.attr("alt") : "pasted-image";
            img.attr("src", urlOf(uploadImage(user, bytes, name)));
        }
        return doc.body().html();
    }

    public List<Attachment> getTodoFiles(Todo todo) {
        return attachmentRepository.findByTodoAndKindOrderByIdAsc(todo, Attachment.Kind.FILE);
    }

    /**
     * 할 일의 첨부 파일 목록을 요청 값으로 맞춘다. ids 가 null 이면 변경하지 않는다.
     * 목록에서 빠진 파일은 연결만 끊고, 실제 삭제는 정리 배치가 유예기간 뒤에 한다.
     */
    @Transactional
    public void syncTodoFiles(Todo todo, List<Long> ids, User user) {
        if (ids == null) return;
        Set<Long> keep = new HashSet<>(ids);
        getTodoFiles(todo).stream()
                .filter(a -> !keep.contains(a.getId()))
                .forEach(a -> a.setTodo(null));
        if (keep.isEmpty()) return;
        for (Attachment a : attachmentRepository.findByIdInAndUserAndKind(keep, user, Attachment.Kind.FILE)) {
            if (a.getTodo() == null || a.getTodo().getId().equals(todo.getId())) a.setTodo(todo);
        }
    }

    @Transactional
    public void unlinkTodo(Todo todo) {
        attachmentRepository.unlinkTodo(todo);
    }

    /** 유예기간이 지난 미연결 파일과, 어떤 본문에서도 참조하지 않는 이미지를 S3와 DB에서 삭제한다. */
    @Scheduled(cron = "${app.attachment.cleanup-cron:0 30 4 * * *}")
    public void cleanupOrphans() {
        Instant before = Instant.now().minus(props.orphanRetention());
        int deleted = 0;
        for (Attachment a : attachmentRepository.findByKindAndTodoIsNullAndCreatedAtBefore(Attachment.Kind.FILE, before)) {
            if (delete(a)) deleted++;
        }
        for (Attachment a : attachmentRepository.findByKindAndCreatedAtBefore(Attachment.Kind.IMAGE, before)) {
            if (!todoRepository.existsByUserAndBodyContaining(a.getUser(), a.getS3Key()) && delete(a)) deleted++;
        }
        if (deleted > 0) log.info("미사용 첨부 {}건 삭제", deleted);
    }

    private boolean delete(Attachment a) {
        try {
            s3Client.deleteObject(b -> b.bucket(props.bucket()).key(a.getS3Key()));
            attachmentRepository.delete(a);
            return true;
        } catch (SdkException e) {
            log.warn("첨부 삭제 실패 id={} key={}: {}", a.getId(), a.getS3Key(), e.getMessage());
            return false;
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "file";
        // 경로 구분자 제거 (일부 브라우저는 전체 경로를 보냄) 및 제어문자 제거
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        base = base.replaceAll("\\p{Cntrl}", "").strip();
        if (base.isEmpty()) return "file";
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    // RFC 6266/5987: 한글 파일명은 filename*, 구형 클라이언트용 ASCII filename 을 함께 지정
    private static String contentDisposition(String type, String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_").replace("\\", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "%s; filename=\"%s\"; filename*=UTF-8''%s".formatted(type, ascii, encoded);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 확장자·Content-Type을 신뢰하지 않고 파일 시그니처로 판별한다. SVG 등 스크립트가 가능한 형식은 받지 않는다. */
    private enum ImageType {
        PNG("png", "image/png"), JPEG("jpg", "image/jpeg"), GIF("gif", "image/gif"), WEBP("webp", "image/webp");

        final String ext;
        final String mime;

        ImageType(String ext, String mime) {
            this.ext = ext;
            this.mime = mime;
        }

        static Optional<ImageType> detect(byte[] d) {
            if (startsWith(d, 0, 0x89, 'P', 'N', 'G')) return Optional.of(PNG);
            if (startsWith(d, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(JPEG);
            if (startsWith(d, 0, 'G', 'I', 'F', '8')) return Optional.of(GIF);
            if (startsWith(d, 0, 'R', 'I', 'F', 'F') && startsWith(d, 8, 'W', 'E', 'B', 'P')) return Optional.of(WEBP);
            return Optional.empty();
        }

        private static boolean startsWith(byte[] d, int offset, int... sig) {
            if (d.length < offset + sig.length) return false;
            for (int i = 0; i < sig.length; i++) {
                if ((d[offset + i] & 0xFF) != sig[i]) return false;
            }
            return true;
        }
    }
}
