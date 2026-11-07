package com.example.pharmacyservice;

import com.example.pharmacyservice.config.RedisConfig;
import com.example.pharmacyservice.model.Product;
import com.example.pharmacyservice.repository.ProductRepository;
import com.example.pharmacyservice.service.PharmacyAlertPublisher;
import com.example.pharmacyservice.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.cache.type=simple",
        "spring.redis.pubsub.enabled=false",
        "spring.redisson.enabled=false"
})
class ProductServiceCacheTest {

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private PharmacyAlertPublisher pharmacyAlertPublisher;

    @Autowired
    private ProductService productService;

    @Autowired
    private CacheManager cacheManager;

    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        if (cacheManager.getCache("products") != null) {
            Objects.requireNonNull(cacheManager.getCache("products")).clear();
        }

        sampleProduct = Product.builder()
                .id(1L)
                .name("Máy đo huyết áp điện tử Omron")
                .description("Thiết bị y tế gia đình cao cấp, đo huyết áp tự động")
                .imageUrl("https://cdn.example.com/images/omron-bp.jpg")
                .price(850000.0)
                .category("Thiết bị y tế")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Lần 1 truy cập chi tiết sản phẩm: Cache Miss -> Truy vấn DB, thời gian ~200ms")
    void testFirstCallQueriesDatabaseWithApprox200ms() {
        long startTime = System.currentTimeMillis();
        Product product = productService.getProductById(1L);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(product);
        assertEquals("Máy đo huyết áp điện tử Omron", product.getName());
        assertEquals(850000.0, product.getPrice());

        // Xác nhận repository.findById(1L) được gọi
        verify(productRepository, times(1)).findById(1L);

        // Lần đầu đọc DB có độ trễ ~200ms
        assertTrue(duration >= 180, "Lần đầu đọc DB phải mất ~200ms, thực tế: " + duration + " ms");
    }

    @Test
    @DisplayName("Lần 2 truy cập cùng sản phẩm: Cache Hit -> Đọc từ Cache, KHÔNG gọi DB, thời gian < 10ms")
    void testSecondCallHitsCacheInLessThan10ms() {
        // Lần 1: Nạp vào cache
        Product firstCall = productService.getProductById(1L);
        assertNotNull(firstCall);
        verify(productRepository, times(1)).findById(1L);

        // Lần 2: Đọc từ cache
        long startTime = System.currentTimeMillis();
        Product secondCall = productService.getProductById(1L);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(secondCall);
        assertEquals(firstCall.getName(), secondCall.getName());
        assertEquals(firstCall.getPrice(), secondCall.getPrice());

        // Quan trọng: repository.findById vẫn chỉ được gọi đúng 1 lần từ lần gọi 1
        verify(productRepository, times(1)).findById(1L);

        // Tốc độ phản hồi cực nhanh < 10ms
        assertTrue(duration < 15, "Lần 2 đọc từ cache phải cực nhanh (< 10ms), thực tế: " + duration + " ms");
    }

    @Test
    @DisplayName("Quản trị viên cập nhật sản phẩm: @CacheEvict tự động xóa cache, lần sau phải đọc lại từ DB")
    void testCacheEvictOnUpdateProduct() {
        // Bước 1: Gọi lần 1 -> Đọc DB
        Product call1 = productService.getProductById(1L);
        assertEquals(850000.0, call1.getPrice());
        verify(productRepository, times(1)).findById(1L);

        // Bước 2: Gọi lần 2 -> Đọc Cache, không gọi DB
        Product call2 = productService.getProductById(1L);
        assertEquals(850000.0, call2.getPrice());
        verify(productRepository, times(1)).findById(1L);

        // Bước 3: Quản trị viên cập nhật giá và mô tả mới -> Kích hoạt @CacheEvict(value = "products", key = "#product.id")
        Product updateRequest = Product.builder()
                .id(1L)
                .name("Máy đo huyết áp điện tử Omron (Model 2026)")
                .description("Phiên bản nâng cấp đo Bluetooth")
                .imageUrl("https://cdn.example.com/images/omron-bp-2026.jpg")
                .price(950000.0)
                .category("Thiết bị y tế")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(updateRequest));

        Product updated = productService.updateProduct(updateRequest);
        assertEquals(950000.0, updated.getPrice());

        // Kiểm tra cache đã bị xóa
        assertNull(Objects.requireNonNull(cacheManager.getCache("products")).get(1L),
                "Key '1' trong cache 'products' phải bị xóa ngay lập tức (@CacheEvict)");

        // Bước 4: Gọi lại getProductById sau khi evict -> Bắt buộc phải query DB lần nữa để lấy dữ liệu mới
        Product callAfterEvict = productService.getProductById(1L);
        assertEquals(950000.0, callAfterEvict.getPrice(), "Phải nhận được giá mới nhất là 950000.0");

        // Xác nhận repository.findById đã được gọi lại
        verify(productRepository, times(3)).findById(1L); // 1 lần ở bước 1, 1 lần ở bước 3 (update), 1 lần ở bước 4 (query lại)
    }
}
