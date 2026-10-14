package im.flare.todo.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 외부 공유 링크 생성 요청.
 *
 * days   유효기간(일, 실수). direct=true 이고 비어 있으면 DB 설정(share.presigned-days)을 쓴다.
 * direct true 면 앱 링크(/s/{token}) 대신 S3 서명 URL을 바로 만든다 (최대 7일, 취소 불가).
 */
@Getter
@Setter
@NoArgsConstructor
public class ShareRequest {
    private Double days;
    private boolean direct;
}
