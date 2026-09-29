package im.flare.todo.dto;

import lombok.Data;

import java.util.List;

@Data
public class TodoRequest {
    private String title;
    private String body;
    private List<Long> categoryIds;
    private Boolean completed;
    // 첨부 파일 ID 목록. null 이면 기존 첨부를 유지한다.
    private List<Long> attachmentIds;
}
