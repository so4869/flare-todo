package im.flare.todo.dto;

import im.flare.todo.entity.Category;
import lombok.Data;

@Data
public class CategoryResponse {
    private Long id;
    private String name;

    public static CategoryResponse from(Category category) {
        CategoryResponse r = new CategoryResponse();
        r.setId(category.getId());
        r.setName(category.getName());
        return r;
    }
}
