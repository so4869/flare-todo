package im.flare.todo.controller;

import im.flare.todo.dto.ApiResponse;
import im.flare.todo.dto.TimezoneRequest;
import im.flare.todo.dto.UserResponse;
import im.flare.todo.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PutMapping("/timezone")
    public ResponseEntity<ApiResponse<UserResponse>> updateTimezone(
            @RequestBody TimezoneRequest request, Authentication auth) {
        try {
            var user = userService.updateTimezone(auth.getName(), request.getTimezone());
            return ResponseEntity.ok(ApiResponse.success(UserResponse.from(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
