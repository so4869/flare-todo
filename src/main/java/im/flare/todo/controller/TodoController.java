package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.TodoRequest;
import im.flare.todo.dto.TodoResponse;
import im.flare.todo.dto.TodoSummaryResponse;
import im.flare.todo.entity.User;
import im.flare.todo.service.TodoService;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TodoSummaryResponse>>> getAll(
            Authentication auth,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) List<Long> categoryIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completedTo,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        User user = userService.findByUsername(auth.getName());
        List<TodoSummaryResponse> list = todoService.getTodos(user, filter, categoryIds,
                        createdFrom, createdTo, completedFrom, completedTo, sortBy, sortDir)
                .stream().map(TodoSummaryResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TodoResponse>> get(@PathVariable Long id, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(TodoResponse.from(todoService.getTodo(id, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TodoResponse>> create(
            @RequestBody TodoRequest request, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(TodoResponse.from(todoService.createTodo(request, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TodoResponse>> update(
            @PathVariable Long id, @RequestBody TodoRequest request, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(TodoResponse.from(todoService.updateTodo(id, request, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            todoService.deleteTodo(id, user);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<TodoResponse>> toggle(@PathVariable Long id, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(TodoResponse.from(todoService.toggleComplete(id, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }
}
