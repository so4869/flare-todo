package im.flare.todo.dto;

import im.flare.todo.entity.Todo;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Data
public class TodoResponse {
    private Long id;
    private String title;
    private String body;
    private boolean completed;
    private List<CategoryResponse> categories;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public static TodoResponse from(Todo todo) {
        TodoResponse r = new TodoResponse();
        r.setId(todo.getId());
        r.setTitle(todo.getTitle());
        r.setBody(todo.getBody());
        r.setCompleted(todo.isCompleted());
        r.setCreatedAt(todo.getCreatedAt());
        r.setUpdatedAt(todo.getUpdatedAt());
        r.setCompletedAt(todo.getCompletedAt());
        r.setCategories(todo.getCategories().stream()
                .map(CategoryResponse::from)
                .sorted(Comparator.comparing(CategoryResponse::getName))
                .toList());
        return r;
    }
}
