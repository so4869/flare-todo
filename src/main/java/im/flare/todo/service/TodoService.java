package im.flare.todo.service;

import im.flare.todo.dto.TodoRequest;
import im.flare.todo.entity.Category;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import im.flare.todo.repository.CategoryRepository;
import im.flare.todo.repository.TodoRepository;
import im.flare.todo.spec.TodoSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TodoService {

    private final TodoRepository todoRepository;
    private final CategoryRepository categoryRepository;

    public List<Todo> getTodos(User user, String filter, List<Long> categoryIds,
                               LocalDate createdFrom, LocalDate createdTo,
                               LocalDate completedFrom, LocalDate completedTo,
                               String sortBy, String sortDir) {

        Specification<Todo> spec = TodoSpec.ofUser(user);
        String tz = user.getTimezone();
        ZoneId zone = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.of("Asia/Seoul");

        if (categoryIds != null && !categoryIds.isEmpty()) {
            List<Category> cats = categoryIds.stream()
                    .map(id -> categoryRepository.findByIdAndUser(id, user).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            if (!cats.isEmpty()) spec = spec.and(TodoSpec.hasAnyCategory(cats));
        }

        if ("completed".equals(filter)) spec = spec.and(TodoSpec.isCompleted(true));
        else if ("active".equals(filter)) spec = spec.and(TodoSpec.isCompleted(false));

        if (createdFrom   != null) spec = spec.and(TodoSpec.createdFrom(createdFrom, zone));
        if (createdTo     != null) spec = spec.and(TodoSpec.createdTo(createdTo, zone));
        if (completedFrom != null) spec = spec.and(TodoSpec.completedFrom(completedFrom, zone));
        if (completedTo   != null) spec = spec.and(TodoSpec.completedTo(completedTo, zone));

        return todoRepository.findAll(spec, buildSort(sortBy, sortDir));
    }

    public Todo getTodo(Long id, User user) {
        return todoRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Todo를 찾을 수 없습니다."));
    }

    @Transactional
    public Todo createTodo(TodoRequest request, User user) {
        boolean completed = Boolean.TRUE.equals(request.getCompleted());
        Todo todo = Todo.builder()
                .title(request.getTitle())
                .body(request.getBody())
                .completed(completed)
                .completedAt(completed ? Instant.now() : null)
                .user(user)
                .categories(resolveCategories(request.getCategoryIds(), user))
                .build();
        return todoRepository.save(todo);
    }

    @Transactional
    public Todo updateTodo(Long id, TodoRequest request, User user) {
        Todo todo = todoRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Todo를 찾을 수 없습니다."));
        todo.setTitle(request.getTitle());
        todo.setBody(request.getBody());
        todo.getCategories().clear();
        todo.getCategories().addAll(resolveCategories(request.getCategoryIds(), user));
        if (request.getCompleted() != null) {
            if (request.getCompleted() && !todo.isCompleted()) {
                todo.setCompletedAt(Instant.now());
            } else if (!request.getCompleted()) {
                todo.setCompletedAt(null);
            }
            todo.setCompleted(request.getCompleted());
        }
        return todoRepository.save(todo);
    }

    @Transactional
    public void deleteTodo(Long id, User user) {
        Todo todo = todoRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Todo를 찾을 수 없습니다."));
        todoRepository.delete(todo);
    }

    @Transactional
    public Todo toggleComplete(Long id, User user) {
        Todo todo = todoRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Todo를 찾을 수 없습니다."));
        boolean nowCompleted = !todo.isCompleted();
        todo.setCompleted(nowCompleted);
        todo.setCompletedAt(nowCompleted ? Instant.now() : null);
        return todoRepository.save(todo);
    }

    private Sort buildSort(String sortBy, String sortDir) {
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if ("completedAt".equals(sortBy)) {
            return Sort.by(new Sort.Order(dir, "completedAt").nullsLast());
        }
        return Sort.by(dir, "createdAt");
    }

    private Set<Category> resolveCategories(List<Long> ids, User user) {
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        return ids.stream()
                .map(cid -> categoryRepository.findByIdAndUser(cid, user)
                        .orElseThrow(() -> new IllegalArgumentException("카테고리를 찾을 수 없습니다.")))
                .collect(Collectors.toSet());
    }
}
