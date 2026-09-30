package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import im.flare.todo.repository.AttachmentRepository;
import im.flare.todo.repository.ShareLinkRepository;
import im.flare.todo.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentServiceTest {

    // 1x1 PNG
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
    private final TodoRepository todoRepository = mock(TodoRepository.class);
    private final ShareLinkRepository shareLinkRepository = mock(ShareLinkRepository.class);
    private final SettingService settingService = mock(SettingService.class);
    private final S3Client s3Client = mock(S3Client.class);
    private final User user = User.builder().id(7L).username("u").build();
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        AttachmentProperties props = new AttachmentProperties("bucket", "ap-northeast-2", "https://cdn.test",
                "KID", null, "test", false, Duration.ofHours(12), Duration.ofDays(1),
                "https://app.test", Duration.ofMinutes(5), 365);
        service = new AttachmentService(attachmentRepository, todoRepository, shareLinkRepository, settingService, s3Client, props);
        when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingService.getMaxAttachmentSize()).thenReturn(DataSize.ofMegabytes(500));
    }

    @Test
    void 본문의_base64_이미지를_업로드하고_CDN_URL로_바꾼다() {
        when(attachmentRepository.findByS3Key(anyString())).thenReturn(Optional.empty());

        String html = service.processBodyImages(user,
                "<p>앞</p><p><img src=\"data:image/png;base64," + PNG_B64 + "\" alt=\"\"></p>");

        assertThat(html).startsWith("<p>앞</p><p><img src=\"https://cdn.test/u/7/img/").endsWith(".png\" alt=\"\"></p>");
        verify(s3Client).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void 이미_올라간_이미지는_다시_업로드하지_않는다() {
        when(attachmentRepository.findByS3Key(anyString())).thenAnswer(inv ->
                Optional.of(Attachment.builder().s3Key(inv.getArgument(0)).build()));

        service.uploadImage(user, Base64.getDecoder().decode(PNG_B64), "a.png");

        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void data_URI가_없으면_본문을_그대로_둔다() {
        String html = "<p>  공백   유지 </p>";
        assertThat(service.processBodyImages(user, html)).isSameAs(html);
    }

    @Test
    void 이미지가_아닌_내용은_거부한다() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes();
        assertThatThrownBy(() -> service.uploadImage(user, svg, "x.svg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void DB에_설정된_최대_크기를_넘는_이미지는_거부한다() {
        when(settingService.getMaxAttachmentSize()).thenReturn(DataSize.ofBytes(10));
        assertThatThrownBy(() -> service.uploadImage(user, Base64.getDecoder().decode(PNG_B64), "a.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10B");
    }

    @Test
    void 첨부_목록은_파일_다음에_본문_이미지를_본문_순서대로_보여준다() {
        Todo todo = Todo.builder().id(1L).user(user).body(
                "<p><img src=\"https://cdn.test/u/7/img/b.png\"><img src=\"https://other.test/x.png\">" +
                "<img src=\"https://cdn.test/u/7/img/a.png\"><img src=\"https://cdn.test/u/7/img/b.png\"></p>").build();
        Attachment file = Attachment.builder().id(1L).kind(Attachment.Kind.FILE).s3Key("u/7/f/x").build();
        Attachment a = Attachment.builder().id(2L).kind(Attachment.Kind.IMAGE).s3Key("u/7/img/a.png").build();
        Attachment b = Attachment.builder().id(3L).kind(Attachment.Kind.IMAGE).s3Key("u/7/img/b.png").build();
        when(attachmentRepository.findByTodoAndKindOrderByIdAsc(todo, Attachment.Kind.FILE)).thenReturn(List.of(file));
        when(attachmentRepository.findByS3KeyInAndUserAndKind(List.of("u/7/img/b.png", "u/7/img/a.png"), user, Attachment.Kind.IMAGE))
                .thenReturn(List.of(a, b));

        assertThat(service.getTodoAttachments(todo)).containsExactly(file, b, a);
    }

    @Test
    void 정리_배치는_미연결_파일과_참조되지_않는_이미지만_삭제한다() {
        Attachment orphanFile = Attachment.builder().id(1L).user(user).kind(Attachment.Kind.FILE).s3Key("u/7/f/a").build();
        Attachment usedImage = Attachment.builder().id(2L).user(user).kind(Attachment.Kind.IMAGE).s3Key("u/7/img/used.png").build();
        Attachment unusedImage = Attachment.builder().id(3L).user(user).kind(Attachment.Kind.IMAGE).s3Key("u/7/img/unused.png").build();
        when(attachmentRepository.findByKindAndTodoIsNullAndCreatedAtBefore(eq(Attachment.Kind.FILE), any(Instant.class)))
                .thenReturn(List.of(orphanFile));
        when(attachmentRepository.findByKindAndCreatedAtBefore(eq(Attachment.Kind.IMAGE), any(Instant.class)))
                .thenReturn(List.of(usedImage, unusedImage));
        when(todoRepository.existsByUserAndBodyContaining(user, "u/7/img/used.png")).thenReturn(true);

        service.cleanupOrphans();

        verify(shareLinkRepository).deleteExpired(any(Instant.class));
        verify(shareLinkRepository).deleteByAttachment(orphanFile);
        verify(attachmentRepository).delete(orphanFile);
        verify(attachmentRepository).delete(unusedImage);
        verify(attachmentRepository, never()).delete(usedImage);
        verify(s3Client, times(2)).deleteObject(any(Consumer.class));
    }
}
