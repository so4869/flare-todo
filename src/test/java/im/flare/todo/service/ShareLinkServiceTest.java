package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.ShareLink;
import im.flare.todo.entity.User;
import im.flare.todo.repository.ShareLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShareLinkServiceTest {

    private final ShareLinkRepository shareLinkRepository = mock(ShareLinkRepository.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final S3Presigner s3Presigner = mock(S3Presigner.class);
    private final User user = User.builder().id(7L).build();
    private ShareLinkService service;

    @BeforeEach
    void setUp() {
        AttachmentProperties props = new AttachmentProperties("bucket", "ap-northeast-2", "https://cdn.test",
                "KID", null, "test", false, Duration.ofHours(12), Duration.ofDays(1),
                "https://app.test", Duration.ofMinutes(5), 365);
        service = new ShareLinkService(shareLinkRepository, attachmentService, s3Presigner, props);
        when(attachmentService.getOwnedAttachment(1L, user)).thenReturn(Attachment.builder().id(1L).build());
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 실수_일수를_그대로_유효기간으로_쓴다() {
        Instant before = Instant.now();
        ShareLinkService.Created c = service.create(1L, 1.5, user);

        assertThat(Duration.between(before, c.expiresAt())).isBetween(
                Duration.ofHours(36).minusSeconds(2), Duration.ofHours(36).plusSeconds(2));
        assertThat(c.url()).matches("https://app\\.test/s/[A-Za-z0-9_-]{43}");
    }

    @Test
    void 잘못된_유효기간은_거부한다() {
        assertThatThrownBy(() -> service.create(1L, 0, user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(1L, -1, user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(1L, Double.NaN, user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(1L, 366, user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(1L, 0.0001, user)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만료된_링크는_열리지_않는다() {
        when(shareLinkRepository.findByTokenWithAttachment("t")).thenReturn(Optional.of(ShareLink.builder()
                .token("t").attachment(Attachment.builder().build()).expiresAt(Instant.now().minusSeconds(1)).build()));

        assertThat(service.resolve("t")).isEmpty();
        verifyNoInteractions(s3Presigner);
    }

    @Test
    void 남의_링크는_취소할_수_없다() {
        when(shareLinkRepository.findByIdAndUser(9L, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(9L, user)).isInstanceOf(IllegalArgumentException.class);
        verify(shareLinkRepository, never()).delete(any());
    }

    @Test
    void 목록은_첨부_파일이_속한_할_일을_함께_보여준다() {
        var todo = im.flare.todo.entity.Todo.builder().id(3L).title("보고").build();
        Attachment file = Attachment.builder().id(1L).kind(Attachment.Kind.FILE).originalName("a.txt").todo(todo).build();
        when(shareLinkRepository.findActiveByUser(any(), any())).thenReturn(List.of(ShareLink.builder()
                .id(5L).token("tok").attachment(file).expiresAt(Instant.now().plusSeconds(60)).build()));

        ShareLinkService.Item item = service.listActive(user).getFirst();

        assertThat(item.url()).isEqualTo("https://app.test/s/tok");
        assertThat(item.todoTitle()).isEqualTo("보고");
        assertThat(item.attachmentKind()).isEqualTo("FILE");
    }
}
