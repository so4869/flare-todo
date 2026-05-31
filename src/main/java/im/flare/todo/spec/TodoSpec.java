package im.flare.todo.spec;

import im.flare.todo.entity.Category;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

public class TodoSpec {

    public static Specification<Todo> ofUser(User user) {
        return (root, query, cb) -> cb.equal(root.get("user"), user);
    }

    public static Specification<Todo> hasAnyCategory(List<Category> cats) {
        return (root, query, cb) -> {
            query.distinct(true);
            return root.join("categories", JoinType.INNER).get("id")
                    .in(cats.stream().map(Category::getId).toList());
        };
    }

    public static Specification<Todo> isCompleted(boolean completed) {
        return (root, query, cb) -> cb.equal(root.get("completed"), completed);
    }

    public static Specification<Todo> createdFrom(LocalDate date) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), date.atStartOfDay());
    }

    public static Specification<Todo> createdTo(LocalDate date) {
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), date.plusDays(1).atStartOfDay());
    }

    public static Specification<Todo> completedFrom(LocalDate date) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("completedAt"), date.atStartOfDay());
    }

    public static Specification<Todo> completedTo(LocalDate date) {
        return (root, query, cb) -> cb.lessThan(root.get("completedAt"), date.plusDays(1).atStartOfDay());
    }
}
