package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.entity.User;
import im.flare.todo.service.ShareLinkService;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 공유 링크 관리 (목록 / 취소). 링크 생성은 POST /api/attachments/{id}/share */
@RestController
@RequestMapping("/api/share-links")
@RequiredArgsConstructor
public class ShareLinkController {

    private final ShareLinkService shareLinkService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShareLinkService.Item>>> list(Authentication auth) {
        User user = userService.findByUsername(auth.getName());
        return ResponseEntity.ok(ApiResponse.success(shareLinkService.listActive(user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable Long id, Authentication auth) {
        try {
            User user = userService.findByUsername(auth.getName());
            shareLinkService.revoke(id, user);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
        }
    }
}
