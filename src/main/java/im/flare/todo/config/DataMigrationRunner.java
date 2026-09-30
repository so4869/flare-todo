package im.flare.todo.config;

import im.flare.todo.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SettingService settingService;

    @Override
    public void run(ApplicationArguments args) {
        // timezone 컬럼이 비어 있는 기존 row를 기본값으로 채움
        jdbcTemplate.update(
            "UPDATE users SET timezone = 'Asia/Seoul' WHERE timezone IS NULL OR timezone = ''"
        );
        // app_settings 기본값 (최대 첨부 크기 등)
        settingService.initDefaults();
    }
}
