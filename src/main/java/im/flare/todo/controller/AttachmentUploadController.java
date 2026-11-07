package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.AttachmentResponse;
import im.flare.todo.entity.User;
import im.flare.todo.service.AttachmentService;
import im.flare.todo.service.DirectUploadService;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 첨부 파일 S3 직접 업로드 (멀티파트). 파일 내용은 서명 URL로 S3에 바로 올라가고, 이 API 는 시작·서명·완료만 처리한다.
 * 흐름: POST /uploads → POST /uploads/{id}/parts (필요한 만큼) → 각 URL로 PUT → POST /uploads/{id}/complete
 */
@Slf4j
@RestController
@RequestMapping("/api/attachments/uploads")
@RequiredArgsConstructor
public class AttachmentUploadController {

    private final DirectUploadService directUploadService;
    private final AttachmentService attachmentService;
    private final UserService userService;

    public record StartRequest(String name, Long size, String contentType) {
    }

    public record SignRequest(List<Integer> partNumbers) {
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DirectUploadService.Started>> start(@RequestBody StartRequest body, Authentication auth) {
        return handle(auth, user -> {
            if (body.size() == null) throw new IllegalArgumentException("파일 크기가 없습니다.");
            return directUploadService.start(user, body.name(), body.size(), body.contentType());
        });
    }

    @PostMapping("/{id}/parts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sign(
            @PathVariable Long id, @RequestBody SignRequest body, Authentication auth) {
        return handle(auth, user -> Map.of("parts", directUploadService.signParts(id, body.partNumbers(), user)));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<AttachmentResponse>> complete(@PathVariable Long id, Authentication auth) {
        return handle(auth, user -> {
            var a = directUploadService.complete(id, user);
            return AttachmentResponse.from(a, attachmentService.urlOf(a));
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> abort(@PathVariable Long id, Authentication auth) {
        return handle(auth, user -> {
            directUploadService.abort(id, user);
            return null;
        });
    }

    private <T> ResponseEntity<ApiResponse<T>> handle(Authentication auth, Function<User, T> action) {
        try {
            User user = userService.findByUsername(auth.getName());
            return ResponseEntity.ok(ApiResponse.success(action.apply(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (SdkException e) {
            log.error("직접 업로드 처리 실패", e);
            return ResponseEntity.status(502).body(ApiResponse.error("파일 저장소와 통신하지 못했습니다."));
        }
    }
}
