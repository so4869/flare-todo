package im.flare.todo.dto;

import im.flare.todo.entity.User;
import lombok.Data;

import java.time.Instant;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private String timezone;
    private Instant createdAt;

    public static UserResponse from(User user) {
        UserResponse r = new UserResponse();
        r.setId(user.getId());
        r.setUsername(user.getUsername());
        r.setEmail(user.getEmail());
        r.setTimezone(user.getTimezone());
        r.setCreatedAt(user.getCreatedAt());
        return r;
    }
}
