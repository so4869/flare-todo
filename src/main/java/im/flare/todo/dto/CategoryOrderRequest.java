package im.flare.todo.dto;

import lombok.Data;

import java.util.List;

@Data
public class CategoryOrderRequest {
    private List<Long> categoryIds;
}
