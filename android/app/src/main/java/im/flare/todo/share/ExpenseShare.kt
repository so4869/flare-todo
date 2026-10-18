package im.flare.todo.share

import android.net.Uri
import im.flare.todo.data.Attachment
import im.flare.todo.data.ListFilter
import im.flare.todo.data.Todo
import im.flare.todo.data.TodoRepository
import im.flare.todo.data.TodoRequest
import im.flare.todo.data.userMessage
import im.flare.todo.ui.zoneOf
import kotlinx.coroutines.CancellationException
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 갤러리 공유("Flare Todo 비용")로 받은 사진을 "비용" 카테고리의 이번 달 할 일에 첨부한다.
 * 이번 달 할 일은 제목이 "yyyyMM"(예: 202610)인 할 일이다. 없으면 만들고, 카테고리가 없으면 카테고리도 만든다.
 */
class ExpenseShare(private val repo: TodoRepository, private val timezone: String) {

    data class Result(val todo: Todo, val added: Int, val failures: List<String>)

    /** 사용자 시간대 기준 이번 달 할 일 제목 */
    fun monthTitle(): String = YearMonth.now(zoneOf(timezone)).format(MONTH)

    /** @param onProgress (올린 파일 수, 전체 수) */
    suspend fun attach(uris: List<Uri>, onProgress: (Int, Int) -> Unit): Result {
        val title = monthTitle()
        val category = repo.categories().firstOrNull { it.name == CATEGORY } ?: repo.addCategory(CATEGORY)
        // 제목은 같은데 완료된 할 일과 미완료가 함께 있으면 미완료 쪽에 넣는다.
        val existing = repo.todos(ListFilter(categoryIds = listOf(category.id)))
            .filter { it.title.trim() == title }
            .minByOrNull { it.completed }

        val uploaded = mutableListOf<Attachment>()
        val failures = mutableListOf<String>()
        onProgress(0, uris.size)
        uris.forEachIndexed { i, uri ->
            try {
                uploaded += repo.uploadFile(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += e.userMessage()
            }
            onProgress(i + 1, uris.size)
        }
        if (uploaded.isEmpty()) throw IllegalStateException(failures.firstOrNull() ?: "올린 사진이 없습니다.")

        val todo = if (existing != null) {
            val current = repo.todo(existing.id)
            repo.updateTodo(
                current.id,
                TodoRequest(
                    title = current.title,
                    body = current.body,
                    categoryIds = current.categories.map { it.id },
                    completed = current.completed,
                    attachmentIds = current.attachments.filter { it.isFile }.map { it.id } + uploaded.map { it.id },
                ),
            )
        } else {
            repo.createTodo(
                TodoRequest(
                    title = title,
                    body = "",
                    categoryIds = listOf(category.id),
                    completed = false,
                    attachmentIds = uploaded.map { it.id },
                ),
            )
        }
        return Result(todo, uploaded.size, failures)
    }

    companion object {
        const val CATEGORY = "비용"
        private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}
