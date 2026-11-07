package com.example.pharmacyservice.service;

import com.example.pharmacyservice.dto.ImportMedicineRequest;
import com.example.pharmacyservice.dto.PharmacyAlertMessage;
import com.example.pharmacyservice.dto.SellMedicineResult;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MedicineService {

    private static final Logger log = LoggerFactory.getLogger(MedicineService.class);

    private final MedicineRepository medicineRepository;
    private final PharmacyAlertPublisher pharmacyAlertPublisher;
    private final RedissonClient redissonClient;

    public MedicineService(MedicineRepository medicineRepository,
                           PharmacyAlertPublisher pharmacyAlertPublisher) {
        this(medicineRepository, pharmacyAlertPublisher, null);
    }

    @Autowired
    public MedicineService(MedicineRepository medicineRepository,
                           PharmacyAlertPublisher pharmacyAlertPublisher,
                           @Autowired(required = false) RedissonClient redissonClient) {
        this.medicineRepository = medicineRepository;
        this.pharmacyAlertPublisher = pharmacyAlertPublisher;
        this.redissonClient = redissonClient;
    }

    /**
     * Lấy thông tin chi tiết thuốc theo ID có áp dụng Redis Cache.
     *
     * Cơ chế hoạt động:
     * - Lần 1: Kiểm tra trong Redis cache 'medicines' với key = #id.
     *   Chưa có -> Spring thực thi phương thức bên dưới, truy vấn trực tiếp vào Database (hiện log SQL).
     *   Sau đó tự động lưu kết quả đối tượng thuốc vào Redis dưới dạng JSON.
     * - Lần 2+: Với cùng #id, dữ liệu đã tồn tại trong Redis.
     *   Spring trả về ngay kết quả từ Redis Cache mà KHÔNG thực thi phương thức bên dưới
     *   (không truy vấn Database, không in log SQL, thời gian phản hồi < 10ms).
     */
    @Cacheable(value = "medicines", key = "#id")
    public Medicine getMedicineById(Long id) {
        log.info("===> [DATABASE QUERY] Đang truy vấn Database để lấy thông tin thuốc với ID = {}", id);
        return medicineRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + id));
    }

    /**
     * Cập nhật thông tin thuốc (giá thuốc, số lượng...).
     *
     * Sử dụng @CacheEvict(value = "medicines", key = "#medicine.id"):
     * Khi cập nhật thuốc thành công, Cache cũ trong Redis sẽ bị xóa ngay lập tức (evicted).
     * Nhờ đó, lần truy vấn chi tiết thuốc tiếp theo sẽ buộc phải truy vấn lại DB để lấy giá mới nhất.
     */
    @CacheEvict(value = "medicines", key = "#medicine.id")
    public Medicine updateMedicine(Medicine medicine) {
        log.info("===> [CACHE EVICT] Đã kích hoạt @CacheEvict xóa cache 'medicines' key = {} do thuốc được cập nhật", medicine.getId());
        Medicine existing = medicineRepository.findById(medicine.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + medicine.getId()));

        if (medicine.getName() != null && !medicine.getName().isBlank()) {
            existing.setName(medicine.getName());
        }
        if (medicine.getQuantity() != null) {
            existing.setQuantity(medicine.getQuantity());
        }
        if (medicine.getUnitPrice() != null) {
            existing.setUnitPrice(medicine.getUnitPrice());
        }

        Medicine updated = medicineRepository.save(existing);
        log.info("===> [DATABASE UPDATE] Đã lưu thông tin thuốc mới vào Database: ID={}, Giá={}", updated.getId(), updated.getUnitPrice());
        return updated;
    }

    /**
     * Bán thuốc sử dụng Redisson Distributed Lock (RLock):
     * Key phân tán: lock:medicine:{id}
     *
     * Cơ chế hoạt động:
     * 1. Trước khi kiểm tra tồn kho và trừ số lượng, nhân viên bắt buộc phải lấy được Lock.
     * 2. Gọi tryLock(3, 5, TimeUnit.SECONDS) với wait time 3s, lease time 5s:
     *    - Chờ tối đa 3 giây để lấy Lock.
     *    - Tự động giải phóng Lock sau 5 giây nếu có sự cố.
     * 3. Khi đã có Lock:
     *    - Kiểm tra tồn kho: nếu tồn kho > 0 và >= số lượng cần mua -> trừ tồn kho -> lưu DB -> Thành công.
     *    - Nếu tồn kho == 0 -> Báo thông báo hết hàng (không bao giờ xảy ra lỗi âm kho).
     * 4. Cuối cùng, giải phóng Lock trong khối finally (lock.unlock()).
     */
    @CacheEvict(value = "medicines", key = "#medicineId")
    public SellMedicineResult sellMedicine(Long medicineId, int quantity, String employeeName) {
        String lockKey = "lock:medicine:" + medicineId;
        RLock lock = redissonClient != null ? redissonClient.getLock(lockKey) : null;
        boolean locked = false;

        try {
            if (lock != null) {
                log.info("===> [REDISSON LOCK] Nhân viên '{}' đang chờ lấy lock '{}' (wait: 3s, lease: 5s)...", employeeName, lockKey);
                locked = lock.tryLock(3, 5, TimeUnit.SECONDS);
                if (!locked) {
                    log.warn("===> [REDISSON LOCK TIMEOUT] Nhân viên '{}' KHÔNG lấy được lock '{}' sau 3s (hệ thống bận)", employeeName, lockKey);
                    return SellMedicineResult.builder()
                            .status("LOCK_TIMEOUT")
                            .message("Hệ thống đang bận xử lý giao dịch cho thuốc này, vui lòng thử lại sau!")
                            .employeeName(employeeName)
                            .medicineId(medicineId)
                            .quantitySold(0)
                            .remainingStock(-1)
                            .build();
                }
                log.info("===> [REDISSON LOCK SUCCESS] Nhân viên '{}' ĐÃ LẤY ĐƯỢC LOCK '{}' thành công!", employeeName, lockKey);
            }

            // Đã nắm giữ Lock: Đọc dữ liệu tồn kho mới nhất từ Database
            Medicine medicine = medicineRepository.findById(medicineId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + medicineId));

            int currentStock = medicine.getQuantity() != null ? medicine.getQuantity() : 0;
            log.info("===> [STOCK CHECK] Nhân viên '{}' kiểm tra tồn kho thuốc '{}' (ID={}): Tồn kho hiện tại = {}, Yêu cầu = {}",
                    employeeName, medicine.getName(), medicineId, currentStock, quantity);

            if (currentStock >= quantity && currentStock > 0) {
                // Đủ số lượng: Trừ tồn kho và lưu vào DB
                int newStock = currentStock - quantity;
                medicine.setQuantity(newStock);
                Medicine saved = medicineRepository.save(medicine);

                log.info("===> [SELL SUCCESS] Nhân viên '{}' bán thành công {} hộp '{}'. Tồn kho còn lại: {}",
                        employeeName, quantity, saved.getName(), newStock);

                return SellMedicineResult.builder()
                        .status("SUCCESS")
                        .message("Bán thuốc thành công!")
                        .employeeName(employeeName)
                        .medicineId(medicineId)
                        .medicineName(saved.getName())
                        .quantitySold(quantity)
                        .remainingStock(newStock)
                        .unitPrice(saved.getUnitPrice())
                        .totalAmount(saved.getUnitPrice() != null ? saved.getUnitPrice() * quantity : 0.0)
                        .build();
            } else {
                // Hết hàng: Báo hết hàng ngay lập tức
                log.warn("===> [OUT OF STOCK] Nhân viên '{}' nhận thông báo: Thuốc '{}' (ID={}) ĐÃ HẾT HÀNG! Tồn kho hiện tại: {}",
                        employeeName, medicine.getName(), medicineId, currentStock);

                return SellMedicineResult.builder()
                        .status("OUT_OF_STOCK")
                        .message("Sản phẩm đã hết hàng!")
                        .employeeName(employeeName)
                        .medicineId(medicineId)
                        .medicineName(medicine.getName())
                        .quantitySold(0)
                        .remainingStock(currentStock)
                        .build();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("===> [REDISSON ERROR] Giao dịch bị gián đoạn khi chờ lock: {}", lockKey, e);
            return SellMedicineResult.builder()
                    .status("ERROR")
                    .message("Giao dịch bị gián đoạn")
                    .employeeName(employeeName)
                    .medicineId(medicineId)
                    .build();
        } finally {
            if (lock != null && locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("===> [REDISSON UNLOCK] Nhân viên '{}' đã giải phóng lock '{}'", employeeName, lockKey);
            }
        }
    }

    public SellMedicineResult sellMedicine(Long medicineId, int quantity) {
        return sellMedicine(medicineId, quantity, "Nhân viên");
    }

    /**
     * Nhập thêm hàng cho hiệu thuốc:
     * - Cập nhật/thêm mới số lượng thuốc trong Database.
     * - Gửi thông báo JSON vào Redis channel "pharmacy-alerts" (Fire-and-forget):
     *   { "type": "IMPORT", "message": "Đã nhập 100 hộp Panadol" }
     */
    public Medicine importMedicine(ImportMedicineRequest request) {
        Medicine medicine = null;
        if (request.getMedicineId() != null) {
            medicine = medicineRepository.findById(request.getMedicineId()).orElse(null);
        }
        if (medicine == null && request.getMedicineName() != null && !request.getMedicineName().isBlank()) {
            medicine = medicineRepository.findByNameIgnoreCase(request.getMedicineName().trim()).orElse(null);
        }

        int importedQuantity = request.getQuantity() != null ? request.getQuantity() : 0;

        if (medicine != null) {
            int currentQty = medicine.getQuantity() != null ? medicine.getQuantity() : 0;
            medicine.setQuantity(currentQty + importedQuantity);
            if (request.getUnitPrice() != null) {
                medicine.setUnitPrice(request.getUnitPrice());
            }
        } else {
            medicine = Medicine.builder()
                    .name(request.getMedicineName() != null ? request.getMedicineName().trim() : "Panadol")
                    .quantity(importedQuantity)
                    .unitPrice(request.getUnitPrice() != null ? request.getUnitPrice() : 5000.0)
                    .build();
        }

        Medicine saved = medicineRepository.save(medicine);
        log.info("===> [DATABASE IMPORT] Đã lưu thuốc vào Database: ID={}, Tên='{}', Số lượng nhập={}, Tổng tồn={}",
                saved.getId(), saved.getName(), importedQuantity, saved.getQuantity());

        // Bắn thông báo Redis Pub/Sub vào channel "pharmacy-alerts" (Fire-and-forget)
        String alertText = String.format("Đã nhập %d hộp %s", importedQuantity, saved.getName());
        PharmacyAlertMessage alertMessage = PharmacyAlertMessage.builder()
                .type("IMPORT")
                .message(alertText)
                .build();

        pharmacyAlertPublisher.publishAlert(alertMessage);

        return saved;
    }

    public List<Medicine> getAllMedicines() {
        return medicineRepository.findAll();
    }

    public Medicine addMedicine(Medicine medicine) {
        return medicineRepository.save(medicine);
    }
}
