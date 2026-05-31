package im.flare.todo.dto;

import lombok.Data;

import java.util.List;

@Data
public class TodoRequest {
    private String title;
    private String body;
    private List<Long> categoryIds;
    private Boolean completed;
}
