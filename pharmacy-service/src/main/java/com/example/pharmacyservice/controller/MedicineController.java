package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.SellMedicineResult;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.service.MedicineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/medicines")
public class MedicineController {

    private static final Logger log = LoggerFactory.getLogger(MedicineController.class);

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) {
        this.medicineService = medicineService;
    }

    /**
     * API Lấy chi tiết thuốc theo ID: GET /api/v1/medicines/{id}
     * Áp dụng Redis Cache @Cacheable(value = "medicines", key = "#id")
     */
    @GetMapping("/{id}")
    public ResponseEntity<Medicine> getMedicineById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Medicine medicine = medicineService.getMedicineById(id);
        long duration = System.currentTimeMillis() - startTime;
        log.info("API GET /api/v1/medicines/{} hoàn thành trong {} ms", id, duration);

        return ResponseEntity.ok()
                .header("X-Response-Time-Millis", String.valueOf(duration))
                .body(medicine);
    }

    @GetMapping
    public ResponseEntity<List<Medicine>> getAllMedicines() {
        return ResponseEntity.ok(medicineService.getAllMedicines());
    }

    @PostMapping
    public ResponseEntity<Medicine> createMedicine(@RequestBody Medicine medicine) {
        return ResponseEntity.ok(medicineService.addMedicine(medicine));
    }

    /**
     * API Cập nhật thuốc / sửa giá: PUT /api/v1/medicines/{id}
     * Kích hoạt @CacheEvict xóa cache cũ khỏi Redis ngay lập tức
     */
    @PutMapping("/{id}")
    public ResponseEntity<Medicine> updateMedicine(@PathVariable Long id, @RequestBody Medicine medicine) {
        medicine.setId(id);
        Medicine updated = medicineService.updateMedicine(medicine);
        return ResponseEntity.ok(updated);
    }

    @PutMapping
    public ResponseEntity<Medicine> updateMedicineDirect(@RequestBody Medicine medicine) {
        Medicine updated = medicineService.updateMedicine(medicine);
        return ResponseEntity.ok(updated);
    }

    /**
     * API Nhập hàng: POST /api/v1/medicines/import
     * Phát thông báo JSON vào channel pharmacy-alerts
     */
    @PostMapping("/import")
    public ResponseEntity<java.util.Map<String, Object>> importMedicine(@RequestBody com.example.pharmacyservice.dto.ImportMedicineRequest request) {
        Medicine saved = medicineService.importMedicine(request);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Nhập hàng thành công và đã gửi thông báo Redis Pub/Sub");
        response.put("channel", "pharmacy-alerts");
        response.put("alertPayload", java.util.Map.of(
                "type", "IMPORT",
                "message", String.format("Đã nhập %d hộp %s", request.getQuantity(), saved.getName())
        ));
        response.put("medicine", saved);

        return ResponseEntity.ok(response);
    }

    /**
     * API Bán thuốc trực tiếp sử dụng Redisson Distributed Lock:
     * POST /api/v1/medicines/{id}/sell
     */
    @PostMapping("/{id}/sell")
    public ResponseEntity<SellMedicineResult> sellMedicine(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(defaultValue = "Nhân viên quầy") String employeeName) {
        SellMedicineResult result = medicineService.sellMedicine(id, quantity, employeeName);
        return ResponseEntity.ok(result);
    }

    /**
     * API Mô phỏng 2 nhân viên (2 luồng song song) cùng nhấn "Thanh toán" tại cùng một mili giây
     * cho hộp thuốc hiếm (chỉ còn 1 sản phẩm cuối cùng trong kho).
     * POST /api/v1/medicines/{id}/simulate-concurrent-sell
     */
    @PostMapping("/{id}/simulate-concurrent-sell")
    public ResponseEntity<Map<String, Object>> simulateConcurrentSell(@PathVariable Long id) {
        // Đặt số lượng tồn kho = 1 để mô phỏng "chỉ còn 1 sản phẩm cuối cùng"
        Medicine medicine = medicineService.getMedicineById(id);
        if (medicine != null) {
            medicine.setQuantity(1);
            medicineService.updateMedicine(medicine);
        }

        // Tạo 2 luồng song song chạy đồng thời
        CompletableFuture<SellMedicineResult> threadA = CompletableFuture.supplyAsync(() ->
                medicineService.sellMedicine(id, 1, "Nhân viên A (Máy 1)")
        );

        CompletableFuture<SellMedicineResult> threadB = CompletableFuture.supplyAsync(() ->
                medicineService.sellMedicine(id, 1, "Nhân viên B (Máy 2)")
        );

        CompletableFuture.allOf(threadA, threadB).join();

        SellMedicineResult resultA = threadA.join();
        SellMedicineResult resultB = threadB.join();

        Medicine updatedMed = medicineService.getMedicineById(id);
        int finalStock = updatedMed != null && updatedMed.getQuantity() != null ? updatedMed.getQuantity() : 0;

        Map<String, Object> response = new HashMap<>();
        response.put("scenario", "Bán hộp thuốc hiếm chỉ còn 1 sản phẩm - 2 nhân viên cùng mua song song");
        response.put("initialStock", 1);
        response.put("finalStock", finalStock);
        response.put("preventedNegativeStock", finalStock >= 0);
        response.put("lockMechanism", "Redisson RLock (key: lock:medicine:" + id + ", wait: 3s, lease: 5s)");
        response.put("employee1_Result", resultA);
        response.put("employee2_Result", resultB);

        long successCount = List.of(resultA, resultB).stream()
                .filter(r -> "SUCCESS".equals(r.getStatus()))
                .count();
        long outOfStockCount = List.of(resultA, resultB).stream()
                .filter(r -> "OUT_OF_STOCK".equals(r.getStatus()))
                .count();

        response.put("summary", String.format("Thành công: %d giao dịch, Hết hàng: %d giao dịch. Kho không bị âm!",
                successCount, outOfStockCount));

        return ResponseEntity.ok(response);
    }
}
