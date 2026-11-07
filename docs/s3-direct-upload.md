# 첨부 파일 S3 직접 업로드 (멀티파트)

할 일 첨부 파일은 웹·앱이 **S3로 직접** 올린다. 파일 내용은 앱 서버(EC2)와 로드밸런서를 거치지 않으므로
큰 파일도 로드밸런서 유휴 타임아웃이나 요청 크기 제한에 걸리지 않는다. 서버는 시작·서명·완료만 처리한다.

```
클라이언트                          서버 (/api/attachments/uploads)          S3
  │ POST /uploads {name,size,type} ──▶ 크기 검사, CreateMultipartUpload ──▶
  │ ◀── {uploadId, partSize, partCount}
  │ POST /uploads/{id}/parts {partNumbers:[1..20]} ──▶ 파트별 PUT 서명 URL (1시간)
  │ PUT <서명 URL> (파트 바이트, 동시 3~4개) ─────────────────────────────▶
  │ POST /uploads/{id}/complete ──▶ ListParts 로 파트·크기 확인, CompleteMultipartUpload ──▶
  │ ◀── 첨부(AttachmentResponse) — 이후는 기존과 같이 할 일 저장 시 attachmentIds 로 연결
  │ (실패/취소) DELETE /uploads/{id} ──▶ AbortMultipartUpload
```

- 파트 크기: 8MB (파트가 1만 개를 넘으면 1MB 단위로 키움). 파트는 실패 시 새 서명 URL로 최대 4번 시도한다.
- `Content-Type`·`Content-Disposition: attachment`·`Cache-Control` 은 시작할 때 서버가 고정한다.
- 완료 시 서버가 S3 `ListParts` 로 ETag 를 직접 읽으므로, 클라이언트가 ETag 헤더를 읽을 필요가 없다.
- 시작 후 하루가 지나도 완료되지 않은 업로드는 매일 04:45 정리 배치가 취소한다 (`attachment_uploads` 테이블).
- 본문 이미지(`/api/attachments/images`)와 MCP 업로드는 크기가 작아 기존처럼 서버를 거친다.
- 웹은 첫 파트부터 네트워크 오류(버킷 CORS 미설정 등)가 나면 서버 경유 업로드로 자동 전환한다.
- `app.attachment.direct-upload: false` (환경변수 `ATTACHMENT_DIRECT_UPLOAD=false`) 로 끄면 웹·앱 모두 서버 경유로 올린다.

## 운영 설정

### 1. S3 버킷 CORS (웹 브라우저용, 필수)

`flare-todo-attach` 버킷 → 권한 → CORS:

```json
[
  {
    "AllowedOrigins": ["https://todo.flare.im"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

CLI: `aws s3api put-bucket-cors --bucket flare-todo-attach --cors-configuration file://cors.json`
(위 배열을 `{"CORSRules": [...]}` 로 감싼 파일). 안드로이드 앱은 CORS 와 무관하다.

### 2. EC2 인스턴스 역할 권한

파트 서명은 장기 다운로드 키(`S3_PRESIGN_*`, s3:GetObject 전용)가 아니라 **인스턴스 역할**로 한다.
역할 정책에 `arn:aws:s3:::flare-todo-attach/*` 대상으로 다음이 있어야 한다.

- `s3:PutObject` (CreateMultipartUpload / UploadPart / CompleteMultipartUpload 포함)
- `s3:AbortMultipartUpload`
- `s3:ListMultipartUploadParts`

### 3. 버킷 수명 주기 규칙 (권장)

서버 정리 배치가 놓친 미완료 업로드의 파트가 과금되지 않도록:
수명 주기 규칙 → "만료된 객체 삭제 마커 또는 완료되지 않은 멀티파트 업로드 삭제" → 완료되지 않은 멀티파트 업로드 삭제, 2일.
