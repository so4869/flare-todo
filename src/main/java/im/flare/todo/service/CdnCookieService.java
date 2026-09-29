package im.flare.todo.service;

import im.flare.todo.config.AttachmentProperties;
import im.flare.todo.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/**
 * CloudFront 서명 쿠키 발급.
 * 쿠키 정책은 사용자 본인의 경로(/u/{userId}/*)로 한정하므로, 다른 사용자의 첨부 URL을 알아도 열람할 수 없다.
 */
@Slf4j
@Service
public class CdnCookieService {

    private final AttachmentProperties props;
    private final PrivateKey privateKey;
    private final CloudFrontUtilities cloudFrontUtilities = CloudFrontUtilities.create();

    public CdnCookieService(AttachmentProperties props) {
        this.props = props;
        this.privateKey = loadPrivateKey(props.privateKeyPath());
        if (privateKey == null) {
            log.warn("app.attachment.private-key-path 가 설정되지 않아 CloudFront 서명 쿠키를 발급하지 않습니다.");
        }
    }

    public boolean isEnabled() {
        return privateKey != null;
    }

    public Instant expiresAt() {
        return Instant.now().plus(props.cookieTtl());
    }

    public List<ResponseCookie> issue(User user, Instant expiresAt) {
        String resource = props.cdnBaseUrl() + "/u/" + user.getId() + "/*";
        CookiesForCustomPolicy cookies = cloudFrontUtilities.getCookiesForCustomPolicy(r -> r
                .resourceUrl(resource)
                .resourceUrlPattern(resource)
                .privateKey(privateKey)
                .keyPairId(props.keyPairId())
                .expirationDate(expiresAt));
        return Stream.of(cookies.policyHeaderValue(), cookies.signatureHeaderValue(), cookies.keyPairIdHeaderValue())
                .map(this::toCookie)
                .toList();
    }

    // SDK는 "CloudFront-Policy=값" 형태의 헤더 값을 돌려준다.
    private ResponseCookie toCookie(String headerValue) {
        int eq = headerValue.indexOf('=');
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(headerValue.substring(0, eq), headerValue.substring(eq + 1))
                .path("/")
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .maxAge(props.cookieTtl());
        if (props.cookieDomain() != null && !props.cookieDomain().isBlank()) b.domain(props.cookieDomain());
        return b.build();
    }

    // PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----") 만 지원
    private static PrivateKey loadPrivateKey(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            String pem = Files.readString(Path.of(path))
                    .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (IOException e) {
            throw new UncheckedIOException("CloudFront 개인키를 읽을 수 없습니다: " + path, e);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("CloudFront 개인키 형식이 올바르지 않습니다 (PKCS#8 PEM 필요): " + path, e);
        }
    }
}
