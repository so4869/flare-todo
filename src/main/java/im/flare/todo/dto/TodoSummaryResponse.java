package im.flare.todo.dto;

import im.flare.todo.entity.Todo;
import lombok.Data;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Data
public class TodoSummaryResponse {
    private Long id;
    private String title;
    private boolean completed;
    private List<CategoryResponse> categories;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public static TodoSummaryResponse from(Todo todo) {
        TodoSummaryResponse r = new TodoSummaryResponse();
        r.setId(todo.getId());
        r.setTitle(todo.getTitle());
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
