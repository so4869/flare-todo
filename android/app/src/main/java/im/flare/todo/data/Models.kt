package im.flare.todo.data

import kotlinx.serialization.Serializable

/** 서버의 ApiResponse<T> */
@Serializable
class ApiEnvelope<T>(
    val success: Boolean = false,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
data class User(
    val id: Long,
    val username: String,
    val email: String? = null,
    val timezone: String? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(val username: String, val email: String, val password: String)

@Serializable
data class LoginResult(val accessToken: String, val user: User)

@Serializable
data class Category(
    val id: Long,
    val name: String,
    val todoCount: Long = 0,
)

@Serializable
data class CategoryRequest(val name: String)

@Serializable
data class CategoryOrderRequest(val categoryIds: List<Long>)

// 시각은 서버가 ISO-8601(UTC) 문자열로 내려준다.
@Serializable
data class TodoSummary(
    val id: Long,
    val title: String,
    val completed: Boolean = false,
    val categories: List<Category> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class Todo(
    val id: Long,
    val title: String,
    val body: String? = null,
    val completed: Boolean = false,
    val categories: List<Category> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val completedAt: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class TodoRequest(
    val title: String,
    val body: String?,
    val categoryIds: List<Long>,
    val completed: Boolean?,
    // null 이면 서버가 기존 첨부를 유지한다.
    val attachmentIds: List<Long>?,
)

@Serializable
data class Attachment(
    val id: Long,
    val kind: String = KIND_FILE,
    val name: String,
    val size: Long = 0,
    val contentType: String? = null,
    val url: String,
) {
    val isFile get() = kind == KIND_FILE

    companion object {
        const val KIND_FILE = "FILE"
        const val KIND_IMAGE = "IMAGE"
    }
}

@Serializable
data class AttachmentConfig(
    val maxFileSize: Long,
    val maxFileSizeLabel: String,
    val shareMaxDays: Int = 365,
    /** S3 직접 링크 기본 유효기간(일). 서버 DB 설정(share.presigned-days) */
    val sharePresignedDays: Double = 7.0,
    val sharePresignedMaxDays: Double = 7.0,
)

@Serializable
data class CdnSession(val enabled: Boolean = false, val expiresAt: String? = null)

@Serializable
/** days 가 null 이면(direct 일 때만 허용) 서버 DB 설정값을 쓴다. direct=true 는 S3 서명 URL을 바로 받는다(최대 7일, 취소 불가). */
data class ShareRequest(val days: Double? = null, val direct: Boolean = false)

@Serializable
/** direct=true 면 S3 URL 그대로라 id 가 없고 취소할 수 없다. */
data class ShareCreated(val id: Long? = null, val url: String, val expiresAt: String, val direct: Boolean = false)

@Serializable
data class ShareLink(
    val id: Long,
    val url: String,
    val attachmentId: Long,
    val attachmentName: String,
    val attachmentKind: String,
    val todoId: Long? = null,
    val todoTitle: String? = null,
    val createdAt: String? = null,
    val expiresAt: String,
)

@Serializable
data class TimezoneRequest(val timezone: String)

/** 목록 검색 조건. 웹과 같이 마지막 상태를 기억한다. */
@Serializable
data class ListFilter(
    val status: String = "all",            // all | active | completed
    val categoryIds: List<Long> = emptyList(),
    val sort: String = "createdAt_desc",   // {createdAt|completedAt}_{asc|desc}
    val createdFrom: String? = null,       // yyyy-MM-dd
    val createdTo: String? = null,
    val completedFrom: String? = null,
    val completedTo: String? = null,
) {
    val hasDates get() = createdFrom != null || createdTo != null || completedFrom != null || completedTo != null
}
