package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.CategoryOrderRequest;
import im.flare.todo.dto.CategoryRequest;
import im.flare.todo.dto.CategoryResponse;
import im.flare.todo.entity.User;
import im.flare.todo.service.CategoryService;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAll(Authentication auth) {
        User user = userService.findByUsername(auth.getName());
        Map<Long, Long> counts = categoryService.getTodoCountByCategory(user);
        List<CategoryResponse> list = categoryService.getCategories(user)
                .stream().map(c -> CategoryResponse.from(c, counts.getOrDefault(c.getId(), 0L))).toList();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> add(
            @RequestBody CategoryRequest request, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            CategoryResponse resp = CategoryResponse.from(categoryService.addCategory(request, user));
            return ResponseEntity.ok(ApiResponse.success(resp));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/order")
    public ResponseEntity<ApiResponse<Void>> updateOrder(
            @RequestBody CategoryOrderRequest request, Authentication auth) {
        User user = userService.findByUsername(auth.getName());
        categoryService.updateOrder(request, user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            categoryService.deleteCategory(id, user);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
