package im.flare.todo.controller;

import im.flare.todo.service.ShareLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 외부 공유 링크 (로그인 불필요). 유효하면 짧은 S3 서명 URL로 리다이렉트한다. */
@RestController
@RequiredArgsConstructor
public class ShareController {

    private static final String EXPIRED_PAGE = """
            <!DOCTYPE html>
            <html lang="ko"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>링크를 열 수 없습니다 - Flare Todo</title>
            <style>
              body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
                     background: #f8f9fa; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; color: #495057; }
              .box { text-align: center; padding: 2rem; }
              h1 { font-size: 1.25rem; margin: 0 0 .5rem; color: #212529; }
              p { margin: 0; font-size: .95rem; }
            </style></head>
            <body><div class="box">
              <h1>링크를 열 수 없습니다</h1>
              <p>공유 기간이 만료되었거나 존재하지 않는 링크입니다.</p>
            </div></body></html>
            """;

    private final ShareLinkService shareLinkService;

    @GetMapping("/s/{token}")
    public ResponseEntity<String> open(@PathVariable String token) {
        return shareLinkService.resolve(token)
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, url)
                        .cacheControl(CacheControl.noStore())
                        .<String>build())
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                        .cacheControl(CacheControl.noStore())
                        .body(EXPIRED_PAGE));
    }
}
