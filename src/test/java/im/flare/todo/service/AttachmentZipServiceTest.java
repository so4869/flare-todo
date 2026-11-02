package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.AttachmentZip;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import im.flare.todo.repository.AttachmentZipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class AttachmentZipServiceTest {

    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final AttachmentZipRepository zipRepository = mock(AttachmentZipRepository.class);
    private final S3Client s3 = mock(S3Client.class);
    private final AttachmentProperties props = new AttachmentProperties("bucket", "ap-northeast-2", "https://cdn",
            null, null, null, false, Duration.ofHours(1), Duration.ofDays(1), null, Duration.ofMinutes(5), 365);
    private final AttachmentZipService service = new AttachmentZipService(attachmentService, zipRepository, s3, props);

    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, byte[]> uploaded = new HashMap<>();
    private final Map<Long, AttachmentZip> rows = new HashMap<>();
    private Todo todo;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(7L).build();
        todo = Todo.builder().id(42L).title("견적/비교").user(user).build();

        when(s3.getObject(any(Consumer.class))).thenAnswer(inv -> {
            GetObjectRequest.Builder b = GetObjectRequest.builder();
            ((Consumer<GetObjectRequest.Builder>) inv.getArgument(0)).accept(b);
            return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(objects.get(b.build().key()))));
        });
        when(s3.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(inv -> {
            PutObjectRequest.Builder b = PutObjectRequest.builder();
            ((Consumer<PutObjectRequest.Builder>) inv.getArgument(0)).accept(b);
            try (InputStream in = ((RequestBody) inv.getArgument(1)).contentStreamProvider().newStream()) {
                uploaded.put(b.build().key(), in.readAllBytes());
            }
            return null;
        });
        when(zipRepository.findById(42L)).thenAnswer(inv -> Optional.ofNullable(rows.get(42L)));
        when(zipRepository.save(any())).thenAnswer(inv -> {
            AttachmentZip z = inv.getArgument(0);
            rows.put(z.getTodoId(), z);
            return z;
        });
    }

    private Attachment file(long id, String key, String name, String content) {
        objects.put(key, content.getBytes(StandardCharsets.UTF_8));
        return Attachment.builder().id(id).kind(Attachment.Kind.FILE).s3Key(key)
                .originalName(name).size(content.length()).build();
    }

    @Test
    void buildsOnceThenReusesUntilAttachmentsChange() throws Exception {
        List<Attachment> list = new ArrayList<>(List.of(
                file(1, "u/7/f/a", "보고서.pdf", "AAA"),
                file(2, "u/7/f/b", "보고서.pdf", "BBB")));
        when(attachmentService.getTodoAttachments(todo)).thenAnswer(inv -> list);

        var first = service.prepare(todo);
        assertThat(first.cached()).isFalse();
        assertThat(first.fileName()).isEqualTo("견적_비교.zip");
        assertThat(first.url()).startsWith("https://cdn/u/7/zip/42/").endsWith(".zip");
        String firstKey = first.url().substring("https://cdn/".length());
        assertThat(entries(uploaded.get(firstKey))).containsExactly(
                Map.entry("보고서.pdf", "AAA"), Map.entry("보고서 (2).pdf", "BBB"));

        var second = service.prepare(todo);
        assertThat(second.cached()).isTrue();
        assertThat(second.url()).isEqualTo(first.url());
        verify(s3, times(1)).putObject(any(Consumer.class), any(RequestBody.class));

        list.add(file(3, "u/7/f/c", "new.txt", "C"));
        var third = service.prepare(todo);
        assertThat(third.cached()).isFalse();
        assertThat(third.url()).isNotEqualTo(first.url());
        assertThat(third.fileCount()).isEqualTo(3);
        verify(s3).deleteObject(any(Consumer.class));
    }

    private static Map<String, String> entries(byte[] zip) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                result.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
