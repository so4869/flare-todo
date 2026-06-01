package im.flare.todo.service;

import im.flare.todo.dto.CategoryOrderRequest;
import im.flare.todo.dto.CategoryRequest;
import im.flare.todo.entity.Category;
import im.flare.todo.entity.User;
import im.flare.todo.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<Category> getCategories(User user) {
        return categoryRepository.findByUserOrderBySortOrderAscIdAsc(user);
    }

    @Transactional
    public Category addCategory(CategoryRequest request, User user) {
        int nextOrder = categoryRepository.findByUserOrderBySortOrderAscIdAsc(user).size();
        Category category = Category.builder()
                .name(request.getName())
                .user(user)
                .sortOrder(nextOrder)
                .build();
        return categoryRepository.save(category);
    }

    @Transactional
    public void deleteCategory(Long id, User user) {
        Category category = categoryRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("카테고리를 찾을 수 없습니다."));
        categoryRepository.delete(category);
    }

    @Transactional
    public void updateOrder(CategoryOrderRequest request, User user) {
        List<Long> ids = request.getCategoryIds();
        for (int i = 0; i < ids.size(); i++) {
            final int order = i;
            categoryRepository.findByIdAndUser(ids.get(i), user)
                    .ifPresent(cat -> {
                        cat.setSortOrder(order);
                        categoryRepository.save(cat);
                    });
        }
    }
}
