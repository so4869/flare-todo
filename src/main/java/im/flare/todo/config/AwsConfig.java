package im.flare.todo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

    // 외부 공유 링크 → 짧은 S3 서명 URL 생성용
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(AttachmentProperties props) {
        return S3Presigner.builder()
                .region(Region.of(props.region()))
                .build();
    }
}
