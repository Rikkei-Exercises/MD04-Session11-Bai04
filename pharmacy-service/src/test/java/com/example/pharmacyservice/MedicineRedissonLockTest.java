package com.example.pharmacyservice;

import com.example.pharmacyservice.dto.SellMedicineResult;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.MedicineService;
import com.example.pharmacyservice.service.PharmacyAlertPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicineRedissonLockTest {

    @Mock
    private MedicineRepository medicineRepository;

    @Mock
    private PharmacyAlertPublisher pharmacyAlertPublisher;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private MedicineService medicineService;

    @BeforeEach
    void setUp() {
        medicineService = new MedicineService(medicineRepository, pharmacyAlertPublisher, redissonClient);
    }

    @Test
    @DisplayName("Redisson Lock: Lấy RLock với key 'lock:medicine:{id}' và gọi tryLock(3, 5, SECONDS)")
    void testSellMedicineAcquiresLockWithKeyAndCorrectParameters() throws Exception {
        Long medicineId = 1L;
        String expectedLockKey = "lock:medicine:1";

        Medicine sample = Medicine.builder()
                .id(medicineId)
                .name("Panadol Extra")
                .quantity(10)
                .unitPrice(4000.0)
                .build();

        when(redissonClient.getLock(expectedLockKey)).thenReturn(rLock);
        when(rLock.tryLock(eq(3L), eq(5L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(medicineRepository.findById(medicineId)).thenReturn(Optional.of(sample));
        when(medicineRepository.save(any(Medicine.class))).thenAnswer(inv -> inv.getArgument(0));

        SellMedicineResult result = medicineService.sellMedicine(medicineId, 2, "Nhân viên A");

        // Xác minh RLock được tạo đúng key: lock:medicine:1
        verify(redissonClient, times(1)).getLock(expectedLockKey);

        // Xác minh gọi tryLock với waitTime=3s, leaseTime=5s
        verify(rLock, times(1)).tryLock(3L, 5L, TimeUnit.SECONDS);

        // Xác minh đã unlock sau khi giao dịch thành công
        verify(rLock, times(1)).unlock();

        assertEquals("SUCCESS", result.getStatus());
        assertEquals("Bán thuốc thành công!", result.getMessage());
        assertEquals(8, result.getRemainingStock());
        assertEquals(2, result.getQuantitySold());
    }

    @Test
    @DisplayName("Hết hàng: Khi tồn kho = 0, thông báo hết hàng và không cho bán âm kho")
    void testSellMedicineWhenOutOfStock() throws Exception {
        Long medicineId = 2L;
        String expectedLockKey = "lock:medicine:2";

        Medicine outOfStockMedicine = Medicine.builder()
                .id(medicineId)
                .name("Thuốc hiếm A")
                .quantity(0) // Đã hết hàng
                .unitPrice(50000.0)
                .build();

        when(redissonClient.getLock(expectedLockKey)).thenReturn(rLock);
        when(rLock.tryLock(eq(3L), eq(5L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(medicineRepository.findById(medicineId)).thenReturn(Optional.of(outOfStockMedicine));

        SellMedicineResult result = medicineService.sellMedicine(medicineId, 1, "Nhân viên B");

        assertEquals("OUT_OF_STOCK", result.getStatus());
        assertEquals("Sản phẩm đã hết hàng!", result.getMessage());
        assertEquals(0, result.getRemainingStock());

        // Không được gọi lưu DB khi hết hàng
        verify(medicineRepository, never()).save(any(Medicine.class));

        // Vẫn phải đảm bảo giải phóng lock
        verify(rLock, times(1)).unlock();
    }

    @Test
    @DisplayName("Lock Timeout: Khi không lấy được lock sau 3 giây chờ -> Trả về thông báo hệ thống bận")
    void testSellMedicineWhenLockCannotBeAcquired() throws Exception {
        Long medicineId = 3L;
        String expectedLockKey = "lock:medicine:3";

        when(redissonClient.getLock(expectedLockKey)).thenReturn(rLock);
        when(rLock.tryLock(3L, 5L, TimeUnit.SECONDS)).thenReturn(false);

        SellMedicineResult result = medicineService.sellMedicine(medicineId, 1, "Nhân viên C");

        assertEquals("LOCK_TIMEOUT", result.getStatus());
        assertTrue(result.getMessage().contains("Hệ thống đang bận"));

        // Không truy vấn DB hay unlock vì chưa từng có lock
        verify(medicineRepository, never()).findById(anyLong());
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("Kịch bản 2 luồng song song cùng mua sản phẩm cuối cùng: Đúng 1 người thành công, 1 người hết hàng, không âm kho")
    void testConcurrentSellWithOnlyOneItemRemaining() throws Exception {
        Long medicineId = 99L;
        String lockKey = "lock:medicine:99";

        // Tồn kho ban đầu chỉ có 1 sản phẩm
        AtomicInteger currentStock = new AtomicInteger(1);
        ReentrantLock realLock = new ReentrantLock();

        when(redissonClient.getLock(lockKey)).thenReturn(rLock);

        // Giả lập cơ chế RLock bằng ReentrantLock thật để test 2 luồng song song
        when(rLock.tryLock(eq(3L), eq(5L), eq(TimeUnit.SECONDS))).thenAnswer(inv -> {
            return realLock.tryLock(3, TimeUnit.SECONDS);
        });

        when(rLock.isHeldByCurrentThread()).thenAnswer(inv -> realLock.isHeldByCurrentThread());

        doAnswer(inv -> {
            if (realLock.isHeldByCurrentThread()) {
                realLock.unlock();
            }
            return null;
        }).when(rLock).unlock();

        when(medicineRepository.findById(medicineId)).thenAnswer(inv -> {
            return Optional.of(Medicine.builder()
                    .id(medicineId)
                    .name("Thuốc đặc trị hiếm (Còn 1 hộp)")
                    .quantity(currentStock.get())
                    .unitPrice(120000.0)
                    .build());
        });

        when(medicineRepository.save(any(Medicine.class))).thenAnswer(inv -> {
            Medicine m = inv.getArgument(0);
            currentStock.set(m.getQuantity());
            return m;
        });

        // Khởi tạo 2 luồng đại diện cho 2 nhân viên cùng nhấn thanh toán tại cùng mili giây
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<SellMedicineResult> taskEmployee1 = () -> {
            startLatch.await(); // Chờ còi xuất phát để 2 luồng chạy cùng mili giây
            return medicineService.sellMedicine(medicineId, 1, "Nhân viên 1 (Máy A)");
        };

        Callable<SellMedicineResult> taskEmployee2 = () -> {
            startLatch.await(); // Chờ còi xuất phát để 2 luồng chạy cùng mili giây
            return medicineService.sellMedicine(medicineId, 1, "Nhân viên 2 (Máy B)");
        };

        Future<SellMedicineResult> future1 = executor.submit(taskEmployee1);
        Future<SellMedicineResult> future2 = executor.submit(taskEmployee2);

        // Phát tín hiệu cho 2 luồng cùng chạy
        startLatch.countDown();

        SellMedicineResult result1 = future1.get(5, TimeUnit.SECONDS);
        SellMedicineResult result2 = future2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Kiểm tra kết quả: Có đúng 1 người SUCCESS và đúng 1 người OUT_OF_STOCK
        boolean oneIsSuccess = "SUCCESS".equals(result1.getStatus()) || "SUCCESS".equals(result2.getStatus());
        boolean oneIsOutOfStock = "OUT_OF_STOCK".equals(result1.getStatus()) || "OUT_OF_STOCK".equals(result2.getStatus());

        assertTrue(oneIsSuccess, "Phải có đúng 1 giao dịch thành công");
        assertTrue(oneIsOutOfStock, "Phải có 1 giao dịch nhận thông báo hết hàng");
        assertNotEquals(result1.getStatus(), result2.getStatus(), "Hai nhân viên không thể cùng mua thành công");

        // Tồn kho cuối cùng phải là 0, TUYỆT ĐỐI KHÔNG BỊ ÂM KHO (-1)
        assertEquals(0, currentStock.get(), "Tồn kho cuối cùng phải bằng 0 (đã ngăn ngừa bán âm kho thành công)");
    }
}
