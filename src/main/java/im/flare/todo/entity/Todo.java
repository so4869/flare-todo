package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "todos",
    indexes = {
        // 기본 목록 조회 및 created_at 범위 검색
        @Index(name = "idx_todo_user_created_at",  columnList = "user_id, created_at"),
        // 완료여부 필터 + created_at 정렬 (가장 빈번한 쿼리 패턴)
        @Index(name = "idx_todo_user_completed_created", columnList = "user_id, completed, created_at"),
        // 완료일 정렬 및 completed_at 범위 검색
        @Index(name = "idx_todo_user_completed_at", columnList = "user_id, completed_at"),
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "LONGTEXT")
    private String body;

    @Column(nullable = false)
    private boolean completed;

    @Column
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "todo_categories",
            joinColumns = @JoinColumn(name = "todo_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"),
            indexes = {
                // category_id로 해당 카테고리의 todo 역방향 조회
                @Index(name = "idx_todo_categories_category_id", columnList = "category_id")
            }
    )
    @Builder.Default
    private Set<Category> categories = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
