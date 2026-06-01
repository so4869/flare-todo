package im.flare.todo.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "categories",
    indexes = {
        // 사용자별 카테고리 목록 정렬 조회
        @Index(name = "idx_category_user_sort", columnList = "user_id, sort_order")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "int default 0")
    @Builder.Default
    private int sortOrder = 0;
}
