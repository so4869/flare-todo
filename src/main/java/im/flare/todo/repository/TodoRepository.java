package im.flare.todo.repository;

import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface TodoRepository extends JpaRepository<Todo, Long>, JpaSpecificationExecutor<Todo> {
    Optional<Todo> findByIdAndUser(Long id, User user);
}
