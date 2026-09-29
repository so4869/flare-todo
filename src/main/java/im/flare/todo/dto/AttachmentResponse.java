package im.flare.todo.dto;

import im.flare.todo.entity.Attachment;
import lombok.Data;

@Data
public class AttachmentResponse {
    private Long id;
    private String name;
    private long size;
    private String contentType;
    private String url;

    public static AttachmentResponse from(Attachment a, String url) {
        AttachmentResponse r = new AttachmentResponse();
        r.setId(a.getId());
        r.setName(a.getOriginalName());
        r.setSize(a.getSize());
        r.setContentType(a.getContentType());
        r.setUrl(url);
        return r;
    }
}
