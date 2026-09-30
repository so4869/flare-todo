package im.flare.todo.repository;

import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.ShareLink;
import im.flare.todo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    @Query("select s from ShareLink s join fetch s.attachment where s.token = :token")
    Optional<ShareLink> findByTokenWithAttachment(@Param("token") String token);

    // 공유 링크 관리 메뉴: 사용자의 유효한 링크 (첨부 파일이 속한 할 일 포함)
    @Query("select s from ShareLink s join fetch s.attachment a left join fetch a.todo " +
           "where s.user = :user and s.expiresAt > :now order by s.createdAt desc")
    List<ShareLink> findActiveByUser(@Param("user") User user, @Param("now") Instant now);

    Optional<ShareLink> findByIdAndUser(Long id, User user);

    @Transactional
    @Modifying
    @Query("delete from ShareLink s where s.attachment = :attachment")
    int deleteByAttachment(@Param("attachment") Attachment attachment);

    @Transactional
    @Modifying
    @Query("delete from ShareLink s where s.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
