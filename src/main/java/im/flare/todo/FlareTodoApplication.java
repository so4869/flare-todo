package im.flare.todo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlareTodoApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlareTodoApplication.class, args);
    }

}
