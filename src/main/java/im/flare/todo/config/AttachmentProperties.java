package im.flare.todo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 첨부파일(S3 + CloudFront 서명 쿠키) 설정. application.yaml 의 app.attachment.* 에 매핑된다.
 *
 * @param bucket             첨부를 저장할 S3 버킷
 * @param region             S3 리전
 * @param cdnBaseUrl         CloudFront 배포 주소 (예: https://todo-att.flare.im)
 * @param keyPairId          CloudFront 공개키 ID (서명 쿠키의 Key-Pair-Id)
 * @param privateKeyPath     CloudFront 서명용 RSA 개인키(PEM) 파일 경로. 비어 있으면 서명 쿠키를 발급하지 않는다.
 * @param cookieDomain       서명 쿠키 Domain (앱과 CDN의 공통 상위 도메인, 예: flare.im)
 * @param cookieSecure       서명 쿠키 Secure 속성 (http로 띄우는 로컬 개발 시 false)
 * @param cookieTtl          서명 쿠키 유효기간
 * @param orphanRetention    어디에도 연결되지 않은 첨부를 삭제하기까지의 유예기간
 * @param shareBaseUrl       외부 공유 링크 주소의 앞부분 (예: https://todo.flare.im)
 * @param shareRedirectTtl   공유 링크 접속 시 발급하는 S3 서명 URL의 유효기간
 * @param shareMaxDays       공유 링크 최대 유효기간(일)
 *
 * 첨부 1개당 최대 크기는 DB(app_settings)에서 관리한다. {@link im.flare.todo.service.SettingService}
 */
@ConfigurationProperties(prefix = "app.attachment")
public record AttachmentProperties(
        String bucket,
        String region,
        String cdnBaseUrl,
        String keyPairId,
        String privateKeyPath,
        String cookieDomain,
        boolean cookieSecure,
        Duration cookieTtl,
        Duration orphanRetention,
        String shareBaseUrl,
        Duration shareRedirectTtl,
        int shareMaxDays
) {
}
