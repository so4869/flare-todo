package im.flare.todo.mcp;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class TodoMcpToolsHelpersTest {

    @Test
    void htmlToText_keepsParagraphBreaks() {
        String html = "<p>첫 줄</p><p>둘째 줄<br>셋째 줄</p><ul><li>a</li><li>b</li></ul>";
        assertThat(TodoMcpTools.htmlToText(html)).isEqualTo("첫 줄\n둘째 줄\n셋째 줄\na\nb");
        assertThat(TodoMcpTools.htmlToText(null)).isEmpty();
        assertThat(TodoMcpTools.htmlToText("<p><img src=\"x\"></p>")).isEmpty();
    }

    @Test
    void toHtml_wrapsPlainTextAndEscapes() {
        assertThat(TodoMcpTools.toHtml("a < b\n둘째\n\n새 단락")).isEqualTo("<p>a &lt; b<br>둘째</p><p>새 단락</p>");
        assertThat(TodoMcpTools.toHtml("<p>이미 HTML</p>")).isEqualTo("<p>이미 HTML</p>");
        assertThat(TodoMcpTools.toHtml(null)).isEmpty();
        assertThat(TodoMcpTools.toHtml("   ")).isEmpty();
    }

    @Test
    void parseDate() {
        assertThat(TodoMcpTools.parseDate("2026-10-15")).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(TodoMcpTools.parseDate(null)).isNull();
        assertThatThrownBy(() -> TodoMcpTools.parseDate("15/10/2026")).isInstanceOf(IllegalArgumentException.class);
    }
}
