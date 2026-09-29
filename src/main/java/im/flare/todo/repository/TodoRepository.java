package im.flare.todo.repository;

import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TodoRepository extends JpaRepository<Todo, Long>, JpaSpecificationExecutor<Todo> {
    Optional<Todo> findByIdAndUser(Long id, User user);

    @Query("select c.id, count(t) from Todo t join t.categories c where t.user = :user and t.completed = false group by c.id")
    List<Object[]> countTodosPerCategory(@Param("user") User user);

    // 본문 이미지가 아직 어떤 할 일 본문에서 참조되는지 확인 (첨부 정리 배치용)
    boolean existsByUserAndBodyContaining(User user, String text);
}
