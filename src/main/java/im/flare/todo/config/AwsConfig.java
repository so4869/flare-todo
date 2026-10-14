package im.flare.todo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AwsConfig {

    // 자격증명은 기본 체인을 따른다: EC2에서는 인스턴스 역할, 로컬에서는 AWS_PROFILE 등 환경변수
    @Bean(destroyMethod = "close")
    public S3Client s3Client(AttachmentProperties props) {
        return S3Client.builder()
                .region(Region.of(props.region()))
                .build();
    }

    /**
     * S3 서명 URL 생성용. 외부 공유 링크(/s/{token}) 의 5분짜리 URL 과 "S3 직접 링크"(최대 7일) 둘 다 여기서 만든다.
     * <p>
     * 서명 URL 은 서명에 쓴 자격증명이 만료되면 같이 죽는다. EC2 인스턴스 역할은 임시 자격증명(수 시간)이라
     * 며칠짜리 직접 링크가 중간에 끊기므로, 운영에서는 장기 액세스 키(IAM 사용자, s3:GetObject 만)를
     * {@code S3_PRESIGN_ACCESS_KEY_ID} / {@code S3_PRESIGN_SECRET_ACCESS_KEY} 로 넣는다. 비우면 기본 체인.
     */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(AttachmentProperties props,
                                   @Value("${app.attachment.presign-access-key-id:}") String accessKeyId,
                                   @Value("${app.attachment.presign-secret-access-key:}") String secretAccessKey) {
        var builder = S3Presigner.builder().region(Region.of(props.region()));
        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId.strip(), secretAccessKey.strip())));
        }
        return builder.build();
    }
}
