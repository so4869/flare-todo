package im.flare.todo.mcp;

import im.flare.todo.dto.CategoryRequest;
import im.flare.todo.dto.TodoRequest;
import im.flare.todo.entity.Category;
import im.flare.todo.entity.Todo;
import im.flare.todo.entity.User;
import im.flare.todo.security.ApiScope;
import im.flare.todo.service.CategoryService;
import im.flare.todo.service.TodoService;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * MCP 도구. 각 도구는 요청 컨텍스트에서 인증 사용자를 꺼내(McpAuth) 기존 서비스 계층을 그대로 호출한다.
 * 서비스가 User 단위로 소유권을 검사하므로 도구에 별도 권한 로직은 없다.
 */
@Component
@RequiredArgsConstructor
public class TodoMcpTools {

    private final McpAuth mcpAuth;
    private final TodoService todoService;
    private final CategoryService categoryService;

    public record CategoryItem(Long id, String name, Long todoCount) {
    }

    public record TodoItem(Long id, String title, boolean completed, List<String> categories,
                           Instant createdAt, Instant completedAt) {
        static TodoItem from(Todo t) {
            return new TodoItem(t.getId(), t.getTitle(), t.isCompleted(), categoryNames(t), t.getCreatedAt(),
                    t.getCompletedAt());
        }
    }

    public record TodoDetail(Long id, String title, String body, boolean completed, List<String> categories,
                             List<Long> categoryIds, Instant createdAt, Instant updatedAt, Instant completedAt) {
        static TodoDetail from(Todo t) {
            return new TodoDetail(t.getId(), t.getTitle(), htmlToText(t.getBody()), t.isCompleted(),
                    categoryNames(t), t.getCategories().stream().map(Category::getId).sorted().toList(),
                    t.getCreatedAt(), t.getUpdatedAt(), t.getCompletedAt());
        }
    }

    @McpTool(name = "list_todos", description = """
            현재 사용자의 할 일 목록을 돌려준다. filter: all | active(미완료) | completed(완료). \
            categoryIds 로 카테고리를 좁힐 수 있고, 날짜(YYYY-MM-DD, 사용자 시간대 기준)로 생성일·완료일 범위를 걸 수 있다. \
            본문은 포함하지 않으니 내용이 필요하면 get_todo 를 쓴다.""")
    @Transactional(readOnly = true)
    public List<TodoItem> listTodos(
            McpTransportContext ctx,
            @McpToolParam(description = "all | active | completed (기본 all)", required = false) String filter,
            @McpToolParam(description = "카테고리 ID 목록", required = false) List<Long> categoryIds,
            @McpToolParam(description = "생성일 시작 (YYYY-MM-DD)", required = false) String createdFrom,
            @McpToolParam(description = "생성일 끝 (YYYY-MM-DD)", required = false) String createdTo,
            @McpToolParam(description = "완료일 시작 (YYYY-MM-DD)", required = false) String completedFrom,
            @McpToolParam(description = "완료일 끝 (YYYY-MM-DD)", required = false) String completedTo,
            @McpToolParam(description = "정렬 기준: createdAt | completedAt (기본 createdAt)", required = false) String sortBy,
            @McpToolParam(description = "정렬 방향: asc | desc (기본 desc)", required = false) String sortDir) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_READ);
        return todoService.getTodos(user,
                        filter == null ? "all" : filter, categoryIds,
                        parseDate(createdFrom), parseDate(createdTo), parseDate(completedFrom), parseDate(completedTo),
                        sortBy == null ? "createdAt" : sortBy, sortDir == null ? "desc" : sortDir)
                .stream().map(TodoItem::from).toList();
    }

    @McpTool(name = "get_todo", description = "할 일 하나의 상세(본문 포함)를 돌려준다. 본문은 HTML 을 텍스트로 바꿔서 준다.")
    @Transactional(readOnly = true)
    public TodoDetail getTodo(McpTransportContext ctx,
                              @McpToolParam(description = "할 일 ID") Long id) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_READ);
        return TodoDetail.from(todoService.getTodo(id, user));
    }

    @McpTool(name = "create_todo", description = """
            새 할 일을 만든다. body 는 일반 텍스트(줄바꿈은 단락으로 바뀜) 또는 HTML. \
            categoryIds 는 list_categories 로 얻은 ID.""")
    @Transactional
    public TodoDetail createTodo(McpTransportContext ctx,
                                 @McpToolParam(description = "제목") String title,
                                 @McpToolParam(description = "본문 (텍스트 또는 HTML)", required = false) String body,
                                 @McpToolParam(description = "카테고리 ID 목록", required = false) List<Long> categoryIds,
                                 @McpToolParam(description = "완료 상태로 만들기 (기본 false)", required = false) Boolean completed) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_WRITE);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("제목은 비울 수 없습니다.");
        TodoRequest req = new TodoRequest();
        req.setTitle(title.trim());
        req.setBody(toHtml(body));
        req.setCategoryIds(categoryIds);
        req.setCompleted(completed);
        req.setAttachmentIds(List.of()); // MCP 에서는 첨부를 다루지 않는다
        return TodoDetail.from(todoService.createTodo(req, user));
    }

    @McpTool(name = "update_todo", description = """
            할 일을 수정한다. 넘기지 않은 필드는 그대로 둔다(title/body/categoryIds/completed 중 바꿀 것만 전달). \
            body 를 바꾸면 기존 본문의 이미지 등 HTML 은 새 내용으로 교체된다.""")
    @Transactional
    public TodoDetail updateTodo(McpTransportContext ctx,
                                 @McpToolParam(description = "할 일 ID") Long id,
                                 @McpToolParam(description = "새 제목", required = false) String title,
                                 @McpToolParam(description = "새 본문 (텍스트 또는 HTML)", required = false) String body,
                                 @McpToolParam(description = "새 카테고리 ID 목록 (빈 목록이면 모두 해제)", required = false) List<Long> categoryIds,
                                 @McpToolParam(description = "완료 여부", required = false) Boolean completed) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_WRITE);
        Todo current = todoService.getTodo(id, user);
        TodoRequest req = new TodoRequest();
        req.setTitle(title == null ? current.getTitle() : title.trim());
        if (req.getTitle().isBlank()) throw new IllegalArgumentException("제목은 비울 수 없습니다.");
        req.setBody(body == null ? current.getBody() : toHtml(body));
        req.setCategoryIds(categoryIds == null
                ? current.getCategories().stream().map(Category::getId).toList()
                : categoryIds);
        req.setCompleted(completed);
        req.setAttachmentIds(null); // null = 기존 첨부 유지
        return TodoDetail.from(todoService.updateTodo(id, req, user));
    }

    @McpTool(name = "complete_todo", description = "할 일을 완료(또는 completed=false 로 미완료) 처리한다.")
    @Transactional
    public TodoItem completeTodo(McpTransportContext ctx,
                                 @McpToolParam(description = "할 일 ID") Long id,
                                 @McpToolParam(description = "완료 여부 (기본 true)", required = false) Boolean completed) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_WRITE);
        boolean target = completed == null || completed;
        Todo todo = todoService.getTodo(id, user);
        if (todo.isCompleted() != target) todo = todoService.toggleComplete(id, user);
        return TodoItem.from(todo);
    }

    @McpTool(name = "delete_todo", description = "할 일을 삭제한다. 되돌릴 수 없다.")
    @Transactional
    public Map<String, Object> deleteTodo(McpTransportContext ctx,
                                          @McpToolParam(description = "할 일 ID") Long id) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_WRITE);
        todoService.deleteTodo(id, user);
        return Map.of("deleted", true, "id", id);
    }

    @McpTool(name = "list_categories", description = "현재 사용자의 카테고리 목록(ID, 이름, 할 일 수)을 돌려준다.")
    @Transactional(readOnly = true)
    public List<CategoryItem> listCategories(McpTransportContext ctx) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_READ);
        Map<Long, Long> counts = categoryService.getTodoCountByCategory(user);
        return categoryService.getCategories(user).stream()
                .map(c -> new CategoryItem(c.getId(), c.getName(), counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @McpTool(name = "create_category", description = "카테고리를 만든다.")
    @Transactional
    public CategoryItem createCategory(McpTransportContext ctx,
                                       @McpToolParam(description = "카테고리 이름 (50자 이하)") String name) {
        User user = mcpAuth.requireUser(ctx, ApiScope.TODOS_WRITE);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("카테고리 이름은 비울 수 없습니다.");
        if (name.length() > 50) throw new IllegalArgumentException("카테고리 이름은 50자 이하여야 합니다.");
        CategoryRequest req = new CategoryRequest();
        req.setName(name.trim());
        Category c = categoryService.addCategory(req, user);
        return new CategoryItem(c.getId(), c.getName(), 0L);
    }

    // ── helpers ────────────────────────────────────────────────

    private static List<String> categoryNames(Todo t) {
        return t.getCategories().stream().map(Category::getName).sorted(Comparator.naturalOrder()).toList();
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("날짜는 YYYY-MM-DD 형식이어야 합니다: " + s);
        }
    }

    /** 본문 HTML → 읽기용 텍스트. 단락/줄바꿈은 개행으로 */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        var doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        doc.select("br").append("\\n");
        doc.select("p, div, li, h1, h2, h3, h4, h5, h6, tr").append("\\n");
        String text = doc.text().replace("\\n", "\n");
        return text.replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** 일반 텍스트를 받으면 단락(<p>)으로 감싸고, 이미 HTML 이면 그대로 둔다 */
    static String toHtml(String body) {
        if (body == null) return "";
        String s = body.strip();
        if (s.isEmpty()) return "";
        if (s.startsWith("<") && s.endsWith(">")) return s;
        StringBuilder sb = new StringBuilder();
        for (String para : s.split("\\n{2,}")) {
            String escaped = org.jsoup.nodes.Entities.escape(para).replace("\n", "<br>");
            sb.append("<p>").append(escaped).append("</p>");
        }
        return sb.toString();
    }
}
