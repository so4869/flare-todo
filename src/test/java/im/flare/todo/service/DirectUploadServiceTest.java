package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.PendingUpload;
import im.flare.todo.entity.User;
import im.flare.todo.repository.PendingUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListPartsIterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DirectUploadServiceTest {

    private static final long MB = 1L << 20;

    private final PendingUploadRepository pendingUploadRepository = mock(PendingUploadRepository.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final S3Client s3Client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final User user = User.builder().id(7L).username("u").build();
    private DirectUploadService service;

    @BeforeEach
    void setUp() {
        AttachmentProperties props = new AttachmentProperties("bucket", "ap-northeast-2", "https://cdn.test",
                "KID", null, "test", false, Duration.ofHours(12), Duration.ofDays(1),
                "https://app.test", Duration.ofMinutes(5), 365);
        service = new DirectUploadService(pendingUploadRepository, attachmentService, s3Client, presigner, props);
        when(pendingUploadRepository.save(any())).thenAnswer(inv -> {
            PendingUpload u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(attachmentService.saveFile(any(), any(), any(), any(), anyLong())).thenAnswer(inv ->
                Attachment.builder().user(inv.getArgument(0)).kind(Attachment.Kind.FILE).s3Key(inv.getArgument(1))
                        .originalName(inv.getArgument(2)).contentType(inv.getArgument(3)).size(inv.getArgument(4)).build());
    }

    @Test
    void 파트_크기는_최소_8MB이고_1만개를_넘지_않는다() {
        assertThat(DirectUploadService.partSizeFor(1)).isEqualTo(8 * MB);
        assertThat(DirectUploadService.partSizeFor(2L << 30)).isEqualTo(8 * MB);   // 2GB → 256개
        long huge = 200L << 30;
        long part = DirectUploadService.partSizeFor(huge);
        assertThat((huge + part - 1) / part).isLessThanOrEqualTo(10_000);
        assertThat(part % MB).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 시작하면_다운로드_헤더를_고정해_멀티파트를_만든다() {
        when(s3Client.createMultipartUpload(any(Consumer.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("UP").build());

        var started = service.start(user, "C:\\보고서.pdf", 20 * MB, "application/pdf");

        assertThat(started.partSize()).isEqualTo(8 * MB);
        assertThat(started.partCount()).isEqualTo(3);
        verify(attachmentService).checkSize(20 * MB);

        ArgumentCaptor<Consumer<CreateMultipartUploadRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).createMultipartUpload(captor.capture());
        CreateMultipartUploadRequest.Builder b = CreateMultipartUploadRequest.builder();
        captor.getValue().accept(b);
        CreateMultipartUploadRequest req = b.build();
        assertThat(req.key()).startsWith("u/7/f/");
        assertThat(req.contentType()).isEqualTo("application/pdf");
        assertThat(req.contentDisposition()).startsWith("attachment;").contains("filename*=UTF-8''%EB%B3%B4");
    }

    @Test
    void 빈_파일은_시작하지_않는다() {
        assertThatThrownBy(() -> service.start(user, "a", 0, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client);
    }

    @Test
    void 범위를_벗어난_파트는_서명하지_않는다() {
        when(pendingUploadRepository.findByIdAndUser(1L, user)).thenReturn(Optional.of(pending(10 * MB, 2)));
        assertThatThrownBy(() -> service.signParts(1L, List.of(3), user)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(presigner);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모든_파트가_올라갔으면_완료하고_첨부로_등록한다() {
        PendingUpload upload = pending(10 * MB, 2);
        when(pendingUploadRepository.findByIdAndUser(1L, user)).thenReturn(Optional.of(upload));
        listParts(part(2, 2 * MB, "e2"), part(1, 8 * MB, "e1"));

        Attachment a = service.complete(1L, user);

        assertThat(a.getS3Key()).isEqualTo("u/7/f/x");
        assertThat(a.getSize()).isEqualTo(10 * MB);
        ArgumentCaptor<Consumer<CompleteMultipartUploadRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).completeMultipartUpload(captor.capture());
        CompleteMultipartUploadRequest.Builder b = CompleteMultipartUploadRequest.builder();
        captor.getValue().accept(b);
        assertThat(b.build().multipartUpload().parts()).extracting(CompletedPart::partNumber, CompletedPart::eTag)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "e1"), org.assertj.core.groups.Tuple.tuple(2, "e2"));
        verify(pendingUploadRepository).delete(upload);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 빠진_파트가_있으면_완료하지_않는다() {
        when(pendingUploadRepository.findByIdAndUser(1L, user)).thenReturn(Optional.of(pending(10 * MB, 2)));
        listParts(part(1, 8 * MB, "e1"));

        assertThatThrownBy(() -> service.complete(1L, user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1/2");
        verify(s3Client, never()).completeMultipartUpload(any(Consumer.class));
        verify(attachmentService, never()).saveFile(any(), any(), any(), any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 크기가_다르면_완료하지_않는다() {
        when(pendingUploadRepository.findByIdAndUser(1L, user)).thenReturn(Optional.of(pending(10 * MB, 2)));
        listParts(part(1, 8 * MB, "e1"), part(2, 3 * MB, "e2"));

        assertThatThrownBy(() -> service.complete(1L, user)).isInstanceOf(IllegalArgumentException.class);
        verify(s3Client, never()).completeMultipartUpload(any(Consumer.class));
    }

    @Test
    void 다른_사용자의_업로드는_찾을_수_없다() {
        when(pendingUploadRepository.findByIdAndUser(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(1L, user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.abort(1L, user)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client);
    }

    private PendingUpload pending(long size, int partCount) {
        return PendingUpload.builder().id(1L).user(user).s3UploadId("UP").s3Key("u/7/f/x")
                .originalName("a.bin").contentType("application/octet-stream")
                .size(size).partSize(8 * MB).partCount(partCount).build();
    }

    private static Part part(int n, long size, String etag) {
        return Part.builder().partNumber(n).size(size).eTag(etag).build();
    }

    @SuppressWarnings("unchecked")
    private void listParts(Part... parts) {
        ListPartsIterable iterable = mock(ListPartsIterable.class);
        when(iterable.parts()).thenReturn(List.of(parts)::iterator);
        when(s3Client.listPartsPaginator(any(Consumer.class))).thenReturn(iterable);
    }
}
