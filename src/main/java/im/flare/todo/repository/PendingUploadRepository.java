package im.flare.todo.repository;

import im.flare.todo.entity.PendingUpload;
import im.flare.todo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PendingUploadRepository extends JpaRepository<PendingUpload, Long> {

    Optional<PendingUpload> findByIdAndUser(Long id, User user);

    // 정리 배치: 오래도록 완료되지 않은 업로드
    List<PendingUpload> findByCreatedAtBefore(Instant before);
}
