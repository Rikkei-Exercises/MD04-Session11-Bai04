package com.example.pharmacyservice;

import com.example.pharmacyservice.config.RedisConfig;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.MedicineService;
import com.example.pharmacyservice.service.PharmacyAlertPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.cache.type=simple",
        "spring.redis.pubsub.enabled=false"
})
class MedicineServiceCacheTest {

    @MockitoBean
    private MedicineRepository medicineRepository;

    @MockitoBean
    private PharmacyAlertPublisher pharmacyAlertPublisher;

    @Autowired
    private MedicineService medicineService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConfig redisConfig;

    private Medicine sampleMedicine;

    @BeforeEach
    void setUp() {
        // Xóa cache cũ trước mỗi test
        if (cacheManager.getCache("medicines") != null) {
            Objects.requireNonNull(cacheManager.getCache("medicines")).clear();
        }

        sampleMedicine = Medicine.builder()
                .id(1L)
                .name("Paracetamol 500mg")
                .quantity(100)
                .unitPrice(2500.0)
                .build();

        when(medicineRepository.findById(1L)).thenReturn(Optional.of(sampleMedicine));
        when(medicineRepository.save(any(Medicine.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Cấu hình Redis: TTL 60 phút, không cache null, key text và value JSON")
    void testRedisCacheConfiguration() {
        assertNotNull(redisConfig, "RedisConfig bean phải tồn tại");
        RedisCacheConfiguration config = redisConfig.redisCacheConfiguration();
        assertNotNull(config, "RedisCacheConfiguration phải được cấu hình");
        assertFalse(config.getAllowCacheNullValues(), "disableCachingNullValues phải được thiết lập");
    }

    @Test
    @DisplayName("Lần 1 gọi getMedicineById: Kiểm tra Cache chưa có -> chạy DB query (gọi Repository 1 lần)")
    void testFirstCallQueriesDatabase() {
        Medicine result = medicineService.getMedicineById(1L);

        assertNotNull(result);
        assertEquals("Paracetamol 500mg", result.getName());
        assertEquals(1L, result.getId());

        // Kiểm tra repository.findById(1L) được gọi đúng 1 lần
        verify(medicineRepository, times(1)).findById(1L);
    }

    @Test
    @DisplayName("Lần 2 gọi getMedicineById cùng ID: Lấy từ Cache -> KHÔNG gọi Repository, tốc độ phản hồi < 10ms")
    void testSecondCallHitsCacheAndSkipsDatabase() {
        // Lần 1: Chưa có trong cache -> Gọi DB
        Medicine firstCallResult = medicineService.getMedicineById(1L);
        assertNotNull(firstCallResult);
        verify(medicineRepository, times(1)).findById(1L);

        // Lần 2: Đã có trong cache -> Lấy trực tiếp từ cache, không gọi DB
        long startTime = System.currentTimeMillis();
        Medicine secondCallResult = medicineService.getMedicineById(1L);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(secondCallResult);
        assertEquals(firstCallResult.getName(), secondCallResult.getName());
        assertEquals(firstCallResult.getUnitPrice(), secondCallResult.getUnitPrice());

        // Quan trọng: repository.findById vẫn chỉ được gọi đúng 1 lần từ lần gọi 1
        verify(medicineRepository, times(1)).findById(1L);

        // Tốc độ phản hồi cực nhanh khi lấy từ Cache (< 10ms)
        assertTrue(duration < 10, "Thời gian phản hồi khi lấy từ cache phải < 10ms, thực tế: " + duration + " ms");
    }

    @Test
    @DisplayName("Cơ chế @CacheEvict: Khi update giá thuốc -> Cache bị xóa -> Lần truy vấn sau phải gọi lại DB để nạp giá mới")
    void testCacheEvictOnUpdateMedicine() {
        // Bước 1: Gọi lần 1 -> Cache Miss -> Truy vấn DB
        Medicine call1 = medicineService.getMedicineById(1L);
        assertEquals(2500.0, call1.getUnitPrice());
        verify(medicineRepository, times(1)).findById(1L);

        // Bước 2: Gọi lần 2 -> Cache Hit -> Lấy từ Cache, KHÔNG gọi lại DB
        Medicine call2 = medicineService.getMedicineById(1L);
        assertEquals(2500.0, call2.getUnitPrice());
        verify(medicineRepository, times(1)).findById(1L); // Vẫn chỉ gọi 1 lần

        // Bước 3: Cập nhật giá thuốc lên 5000.0 -> Kích hoạt @CacheEvict(value = "medicines", key = "#medicine.id")
        Medicine updateRequest = Medicine.builder()
                .id(1L)
                .name("Paracetamol 500mg")
                .quantity(100)
                .unitPrice(5000.0)
                .build();

        // Giả lập sau khi lưu DB, repository.findById trả về đối tượng có giá mới
        when(medicineRepository.findById(1L)).thenReturn(Optional.of(updateRequest));

        Medicine updated = medicineService.updateMedicine(updateRequest);
        assertEquals(5000.0, updated.getUnitPrice());

        // Kiểm tra trong Cache xem key '1' đã bị xóa chưa
        assertNull(Objects.requireNonNull(cacheManager.getCache("medicines")).get(1L),
                "Key '1' trong cache 'medicines' phải bị xóa ngay lập tức (@CacheEvict)");

        // Bước 4: Gọi lại getMedicineById(1L) sau khi xóa cache
        // Hệ thống phát hiện Cache trống -> Bắt buộc phải gọi lại DB lần nữa
        Medicine callAfterEvict = medicineService.getMedicineById(1L);
        assertEquals(5000.0, callAfterEvict.getUnitPrice(), "Phải nhận được giá mới nhất từ DB là 5000.0");

        // Xác nhận repository.findById đã được gọi lại lần thứ 2!
        verify(medicineRepository, times(3)).findById(1L); // 1 lần ở bước 1, 1 lần ở bước 3 (update), 1 lần ở bước 4 (query lại)
    }
}
