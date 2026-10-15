package im.flare.todo.service;

import im.flare.todo.entity.ApiToken;
import im.flare.todo.entity.User;
import im.flare.todo.repository.ApiTokenRepository;
import im.flare.todo.security.ApiScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiTokenServiceTest {

    private final ApiTokenRepository repo = mock(ApiTokenRepository.class);
    private final User user = User.builder().id(7L).username("hyunjin").build();
    private ApiTokenService service;

    @BeforeEach
    void setUp() {
        service = new ApiTokenService(repo);
        when(repo.save(any())).thenAnswer(inv -> {
            ApiToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(1L);
            return t;
        });
        when(repo.findByUserAndRevokedAtIsNullOrderByCreatedAtDesc(user)).thenReturn(List.of());
    }

    @Test
    void create_returnsPlaintextOnceAndStoresOnlyHash() {
        ApiTokenService.Created created = service.create(user, "맥북", 90, Set.of(ApiScope.TODOS_READ));

        assertThat(created.token()).startsWith("ft_").hasSize(46);
        assertThat(created.prefix()).isEqualTo(created.token().substring(0, 11));

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(repo).save(captor.capture());
        ApiToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).hasSize(64).doesNotContain(created.token());
        assertThat(saved.getTokenHash()).isEqualTo(ApiTokenService.hash(created.token()));
        assertThat(saved.getScopes()).isEqualTo("todos:read");
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plusSeconds(89 * 86400L));
    }

    @Test
    void create_rejectsBadInput() {
        assertThatThrownBy(() -> service.create(user, " ", null, ApiScope.all()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(user, "x", 0, ApiScope.all()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("만료");
        assertThatThrownBy(() -> service.create(user, "x", 366, ApiScope.all()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(user, "x", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("스코프");
    }

    @Test
    void create_enforcesPerUserLimit() {
        List<ApiToken> many = java.util.stream.IntStream.range(0, ApiTokenService.MAX_TOKENS_PER_USER)
                .mapToObj(i -> ApiToken.builder().id((long) i).build()).toList();
        when(repo.findByUserAndRevokedAtIsNullOrderByCreatedAtDesc(user)).thenReturn(many);
        assertThatThrownBy(() -> service.create(user, "x", null, ApiScope.all()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최대");
    }

    @Test
    void authenticate_findsActiveTokenByHashAndTouchesLastUsed() {
        String raw = ApiTokenService.newRawToken();
        ApiToken stored = ApiToken.builder().id(3L).user(user).tokenHash(ApiTokenService.hash(raw))
                .scopes("todos:read todos:write").build();
        when(repo.findByTokenHashWithUser(ApiTokenService.hash(raw))).thenReturn(Optional.of(stored));

        Optional<ApiToken> found = service.authenticate(raw);

        assertThat(found).containsSame(stored);
        assertThat(stored.getLastUsedAt()).isNotNull();
        verify(repo).save(stored);
    }

    @Test
    void authenticate_doesNotRewriteLastUsedWithinInterval() {
        String raw = ApiTokenService.newRawToken();
        Instant recent = Instant.now().minusSeconds(10);
        ApiToken stored = ApiToken.builder().id(3L).user(user).tokenHash(ApiTokenService.hash(raw))
                .scopes("todos:read").lastUsedAt(recent).build();
        when(repo.findByTokenHashWithUser(any())).thenReturn(Optional.of(stored));

        service.authenticate(raw);

        assertThat(stored.getLastUsedAt()).isEqualTo(recent);
        verify(repo, never()).save(any());
    }

    @Test
    void authenticate_rejectsRevokedExpiredUnknownAndNonPatStrings() {
        String raw = ApiTokenService.newRawToken();
        ApiToken revoked = ApiToken.builder().user(user).tokenHash(ApiTokenService.hash(raw))
                .scopes("todos:read").revokedAt(Instant.now()).build();
        when(repo.findByTokenHashWithUser(ApiTokenService.hash(raw))).thenReturn(Optional.of(revoked));
        assertThat(service.authenticate(raw)).isEmpty();

        ApiToken expired = ApiToken.builder().user(user).tokenHash(ApiTokenService.hash(raw))
                .scopes("todos:read").expiresAt(Instant.now().minusSeconds(1)).build();
        when(repo.findByTokenHashWithUser(ApiTokenService.hash(raw))).thenReturn(Optional.of(expired));
        assertThat(service.authenticate(raw)).isEmpty();

        when(repo.findByTokenHashWithUser(any())).thenReturn(Optional.empty());
        assertThat(service.authenticate(ApiTokenService.newRawToken())).isEmpty();

        // JWT 처럼 보이는 문자열은 조회조차 하지 않는다
        clearInvocations(repo);
        assertThat(service.authenticate("eyJhbGciOiJIUzI1NiJ9.x.y")).isEmpty();
        verify(repo, never()).findByTokenHashWithUser(any());
    }

    @Test
    void revoke_marksRevokedOnlyForOwner() {
        ApiToken t = ApiToken.builder().id(5L).user(user).scopes("todos:read").build();
        when(repo.findByIdAndUser(5L, user)).thenReturn(Optional.of(t));
        service.revoke(5L, user);
        assertThat(t.getRevokedAt()).isNotNull();

        when(repo.findByIdAndUser(6L, user)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(6L, user)).isInstanceOf(IllegalArgumentException.class);
        // 이미 폐기된 토큰은 다시 폐기할 수 없다
        assertThatThrownBy(() -> service.revoke(5L, user)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopes_roundTrip() {
        assertThat(ApiScope.parse(ApiScope.join(ApiScope.all()))).containsExactlyElementsOf(ApiScope.all());
        assertThat(ApiScope.parse("  ")).isEmpty();
        assertThatThrownBy(() -> ApiScope.parse("admin")).isInstanceOf(IllegalArgumentException.class);
    }
}
