package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.AttachmentResponse;
import im.flare.todo.dto.TodoRequest;
import im.flare.todo.dto.TodoResponse;
import im.flare.todo.dto.TodoSummaryResponse;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import im.flare.todo.service.AttachmentService;
import im.flare.todo.service.AttachmentZipService;
import im.flare.todo.service.TodoService;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.core.exception.SdkException;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;
    private final UserService userService;
    private final AttachmentService attachmentService;
    private final AttachmentZipService attachmentZipService;

    private TodoResponse toResponse(Todo todo) {
        return TodoResponse.from(todo, attachmentService.getTodoAttachments(todo).stream()
                .map(a -> AttachmentResponse.from(a, attachmentService.urlOf(a)))
                .toList());
    }

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
            return ResponseEntity.ok(ApiResponse.success(toResponse(todoService.getTodo(id, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TodoResponse>> create(
            @RequestBody TodoRequest request, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(toResponse(todoService.createTodo(request, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TodoResponse>> update(
            @PathVariable Long id, @RequestBody TodoRequest request, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(toResponse(todoService.updateTodo(id, request, user))));
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
            return ResponseEntity.ok(ApiResponse.success(toResponse(todoService.toggleComplete(id, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }

    /** 첨부 전체 zip 다운로드: 최신 zip 이 있으면 바로, 없으면 만들어서 CDN 주소를 돌려준다. */
    @GetMapping("/{id}/attachments/zip")
    public ResponseEntity<ApiResponse<AttachmentZipService.ZipInfo>> downloadZip(@PathVariable Long id, Authentication auth) {
        return prepareZip(id, auth);
    }

    /** 첨부 전체 zip 미리 만들기 (최신이면 그대로 둔다) */
    @PostMapping("/{id}/attachments/zip")
    public ResponseEntity<ApiResponse<AttachmentZipService.ZipInfo>> buildZip(@PathVariable Long id, Authentication auth) {
        return prepareZip(id, auth);
    }

    private ResponseEntity<ApiResponse<AttachmentZipService.ZipInfo>> prepareZip(Long id, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(attachmentZipService.prepare(todoService.getTodo(id, user))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (SdkException e) {
            log.error("첨부 zip 생성 실패 todo={}", id, e);
            return ResponseEntity.status(502).body(ApiResponse.error("압축 파일을 만들지 못했습니다."));
        }
    }
}
