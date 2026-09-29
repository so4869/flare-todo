package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.AttachmentResponse;
import im.flare.todo.entity.Attachment;
import im.flare.todo.entity.User;
import im.flare.todo.service.AttachmentService;
import im.flare.todo.service.CdnCookieService;
import im.flare.todo.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final CdnCookieService cdnCookieService;
    private final UserService userService;

    /** 할 일 첨부 파일 업로드 (파일 선택 / 드래그 앤 드롭) */
    @PostMapping
    public ResponseEntity<ApiResponse<AttachmentResponse>> uploadFile(
            @RequestParam("file") MultipartFile file, Authentication auth) {
        return upload(auth, user -> attachmentService.uploadFile(user, file));
    }

    /** 본문 이미지 업로드 (TinyMCE 이미지 대화상자) */
    @PostMapping("/images")
    public ResponseEntity<ApiResponse<AttachmentResponse>> uploadImage(
            @RequestParam("file") MultipartFile file, Authentication auth) {
        return upload(auth, user -> {
            try {
                return attachmentService.uploadImage(user, file.getBytes(), file.getOriginalFilename());
            } catch (IOException e) {
                throw new IllegalArgumentException("파일을 읽을 수 없습니다.");
            }
        });
    }

    /** CDN(CloudFront) 서명 쿠키 발급. 페이지 로드 시와 만료 전에 주기적으로 호출한다. */
    @PostMapping("/cdn-session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cdnSession(Authentication auth, HttpServletResponse response) {
        if (!cdnCookieService.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("enabled", false)));
        }
        User user = userService.findByUsername(auth.getName());
        Instant expiresAt = cdnCookieService.expiresAt();
        cdnCookieService.issue(user, expiresAt)
                .forEach(c -> response.addHeader(HttpHeaders.SET_COOKIE, c.toString()));
        return ResponseEntity.ok(ApiResponse.success(Map.of("enabled", true, "expiresAt", expiresAt)));
    }

    private ResponseEntity<ApiResponse<AttachmentResponse>> upload(
            Authentication auth, java.util.function.Function<User, Attachment> action) {
        try {
            User user = userService.findByUsername(auth.getName());
            Attachment a = action.apply(user);
            return ResponseEntity.ok(ApiResponse.success(AttachmentResponse.from(a, attachmentService.urlOf(a))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (SdkException e) {
            log.error("첨부 업로드 실패", e);
            return ResponseEntity.status(502).body(ApiResponse.error("파일 저장소에 업로드하지 못했습니다."));
        }
    }
}
