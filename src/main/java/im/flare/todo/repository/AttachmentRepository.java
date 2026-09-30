package im.flare.todo.repository;

import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByS3Key(String s3Key);

    Optional<Attachment> findByIdAndUser(Long id, User user);

    List<Attachment> findByS3KeyInAndUserAndKind(Collection<String> s3Keys, User user, Attachment.Kind kind);

    List<Attachment> findByTodoAndKindOrderByIdAsc(Todo todo, Attachment.Kind kind);

    List<Attachment> findByIdInAndUserAndKind(Collection<Long> ids, User user, Attachment.Kind kind);

    @Modifying
    @Query("update Attachment a set a.todo = null where a.todo = :todo")
    int unlinkTodo(@Param("todo") Todo todo);

    // 정리 배치: 유예기간이 지난 미연결 파일
    List<Attachment> findByKindAndTodoIsNullAndCreatedAtBefore(Attachment.Kind kind, Instant before);

    // 정리 배치: 유예기간이 지난 본문 이미지 (본문 참조 여부는 별도 확인)
    List<Attachment> findByKindAndCreatedAtBefore(Attachment.Kind kind, Instant before);
}
