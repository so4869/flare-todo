package im.flare.todo.service;

import im.flare.todo.entity.ApiToken;
import im.flare.todo.entity.User;
import im.flare.todo.repository.ApiTokenRepository;
import im.flare.todo.security.ApiScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 개인 액세스 토큰(PAT) 발급·검증·폐기.
 * 토큰 형식: "ft_" + SecureRandom 32바이트 Base64url (총 46자). 접두어로 JWT와 즉시 구분된다.
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService {

    public static final String TOKEN_PREFIX = "ft_";
    public static final int MAX_EXPIRES_DAYS = 365;
    public static final int MAX_TOKENS_PER_USER = 20;
    /** last_used_at 은 이 간격보다 자주 갱신하지 않는다 (요청마다 UPDATE 를 피함) */
    static final Duration LAST_USED_UPDATE_INTERVAL = Duration.ofMinutes(1);
    private static final int PREFIX_LENGTH = TOKEN_PREFIX.length() + 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository apiTokenRepository;

    /** 발급 결과. token(평문)은 이 객체로만 전달되고 저장되지 않는다. */
    public record Created(Long id, String token, String name, String prefix, Set<ApiScope> scopes,
                          Instant expiresAt, Instant createdAt) {
    }

    public static boolean looksLikeApiToken(String bearer) {
        return bearer != null && bearer.startsWith(TOKEN_PREFIX);
    }

    @Transactional
    public Created create(User user, String name, Integer expiresInDays, Set<ApiScope> scopes) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("토큰 이름을 입력해 주세요.");
        if (name.length() > 100) throw new IllegalArgumentException("토큰 이름은 100자 이하여야 합니다.");
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("스코프를 하나 이상 선택해 주세요.");
        Instant expiresAt = null;
        if (expiresInDays != null) {
            if (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_DAYS) {
                throw new IllegalArgumentException("만료 기간은 1~%d일 사이여야 합니다.".formatted(MAX_EXPIRES_DAYS));
            }
            expiresAt = Instant.now().plus(Duration.ofDays(expiresInDays));
        }
        if (apiTokenRepository.findByUserAndRevokedAtIsNullOrderByCreatedAtDesc(user).size() >= MAX_TOKENS_PER_USER) {
            throw new IllegalArgumentException("토큰은 최대 %d개까지 만들 수 있습니다. 쓰지 않는 토큰을 먼저 삭제해 주세요."
                    .formatted(MAX_TOKENS_PER_USER));
        }
        String raw = newRawToken();
        ApiToken saved = apiTokenRepository.save(ApiToken.builder()
                .user(user)
                .name(name.trim())
                .tokenHash(hash(raw))
                .prefix(raw.substring(0, PREFIX_LENGTH))
                .scopes(ApiScope.join(scopes))
                .expiresAt(expiresAt)
                .build());
        return new Created(saved.getId(), raw, saved.getName(), saved.getPrefix(), scopes,
                saved.getExpiresAt(), saved.getCreatedAt());
    }

    /**
     * Bearer 토큰 평문으로 유효한 토큰을 찾는다. 폐기·만료된 토큰은 비어 있다.
     * 찾으면 last_used_at 을 (간격 제한을 두고) 갱신한다.
     */
    @Transactional
    public Optional<ApiToken> authenticate(String raw) {
        if (!looksLikeApiToken(raw)) return Optional.empty();
        Instant now = Instant.now();
        return apiTokenRepository.findByTokenHashWithUser(hash(raw))
                .filter(t -> t.isActive(now))
                .map(t -> {
                    Instant last = t.getLastUsedAt();
                    if (last == null || last.plus(LAST_USED_UPDATE_INTERVAL).isBefore(now)) {
                        t.setLastUsedAt(now);
                        apiTokenRepository.save(t);
                    }
                    return t;
                });
    }

    @Transactional(readOnly = true)
    public List<ApiToken> list(User user) {
        return apiTokenRepository.findByUserAndRevokedAtIsNullOrderByCreatedAtDesc(user);
    }

    @Transactional
    public void revoke(Long id, User user) {
        ApiToken token = apiTokenRepository.findByIdAndUser(id, user)
                .filter(t -> !t.isRevoked())
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다."));
        token.setRevokedAt(Instant.now());
        apiTokenRepository.save(token);
    }

    static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
