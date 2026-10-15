package im.flare.todo.repository;

import im.flare.todo.entity.ApiToken;
import im.flare.todo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    @Query("select t from ApiToken t join fetch t.user where t.tokenHash = :hash")
    Optional<ApiToken> findByTokenHashWithUser(@Param("hash") String hash);

    /** 토큰 관리 목록: 폐기되지 않은 토큰 (만료된 것은 표시용으로 포함) */
    List<ApiToken> findByUserAndRevokedAtIsNullOrderByCreatedAtDesc(User user);

    Optional<ApiToken> findByIdAndUser(Long id, User user);
}
