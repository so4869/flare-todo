package im.flare.todo.service;

import im.flare.todo.entity.AppSetting;
import im.flare.todo.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/**
 * app_settings 테이블 기반 설정. 값을 DB에서 바꾸면 재시작 없이 다음 요청부터 반영된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingService {

    public static final String MAX_ATTACHMENT_SIZE = "attachment.max-file-size";
    private static final DataSize DEFAULT_MAX_ATTACHMENT_SIZE = DataSize.ofMegabytes(500);

    /** S3 서명 URL을 직접 발급하는 공유의 기본 유효기간(일). S3 SigV4 한계인 7일을 넘길 수 없다. */
    public static final String SHARE_PRESIGNED_DAYS = "share.presigned-days";
    public static final double SHARE_PRESIGNED_MAX_DAYS = 7;
    private static final double DEFAULT_SHARE_PRESIGNED_DAYS = 7;

    private final AppSettingRepository appSettingRepository;

    /** 기본 설정 행이 없으면 만든다 (기존 값은 건드리지 않음). */
    @Transactional
    public void initDefaults() {
        if (!appSettingRepository.existsById(MAX_ATTACHMENT_SIZE)) {
            appSettingRepository.save(AppSetting.builder()
                    .key(MAX_ATTACHMENT_SIZE)
                    .value("500MB")
                    .description("첨부파일·본문 이미지 1개당 최대 크기 (예: 500MB, 1GB). 업로드 요청 상한(1GB)을 넘길 수 없음")
                    .build());
        }
        if (!appSettingRepository.existsById(SHARE_PRESIGNED_DAYS)) {
            appSettingRepository.save(AppSetting.builder()
                    .key(SHARE_PRESIGNED_DAYS)
                    .value("7")
                    .description("S3 서명 URL 직접 공유의 기본 유효기간(일, 소수 가능). S3 한계로 최대 7일")
                    .build());
        }
    }

    /** S3 서명 URL 직접 공유의 기본 유효기간(일). 값이 없거나 잘못되면 7일, 7일을 넘으면 7일로 제한. */
    public double getSharePresignedDays() {
        return appSettingRepository.findById(SHARE_PRESIGNED_DAYS)
                .map(s -> {
                    try {
                        double d = Double.parseDouble(s.getValue().strip());
                        if (!Double.isFinite(d) || d <= 0) throw new NumberFormatException();
                        return Math.min(d, SHARE_PRESIGNED_MAX_DAYS);
                    } catch (NumberFormatException e) {
                        log.warn("잘못된 설정값 {}={}, 기본값 사용", SHARE_PRESIGNED_DAYS, s.getValue());
                        return DEFAULT_SHARE_PRESIGNED_DAYS;
                    }
                })
                .orElse(DEFAULT_SHARE_PRESIGNED_DAYS);
    }

    /** 첨부 1개당 최대 크기. 값이 없거나 잘못되면 기본값(500MB). */
    public DataSize getMaxAttachmentSize() {
        return appSettingRepository.findById(MAX_ATTACHMENT_SIZE)
                .map(s -> {
                    try {
                        return DataSize.parse(s.getValue().strip());
                    } catch (IllegalArgumentException e) {
                        log.warn("잘못된 설정값 {}={}, 기본값 사용", MAX_ATTACHMENT_SIZE, s.getValue());
                        return DEFAULT_MAX_ATTACHMENT_SIZE;
                    }
                })
                .orElse(DEFAULT_MAX_ATTACHMENT_SIZE);
    }
}
