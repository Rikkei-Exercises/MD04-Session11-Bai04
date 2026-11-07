package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.SellMedicineResult;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.MedicineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RefreshScope
@RestController
@RequestMapping("/api/pharmacy")
public class PharmacyController {

    private static final Logger log = LoggerFactory.getLogger(PharmacyController.class);

    @Value("${app.branch-name}")
    private String branchName;

    @Value("${app.hotline}")
    private String hotline;

    @Value("${spring.datasource.url:N/A}")
    private String datasourceUrl;

    private final MedicineRepository medicineRepository;
    private final MedicineService medicineService;

    public PharmacyController(MedicineRepository medicineRepository, MedicineService medicineService) {
        this.medicineRepository = medicineRepository;
        this.medicineService = medicineService;
    }

    @GetMapping("/branch-info")
    public ResponseEntity<Map<String, Object>> getBranchInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("branchName", branchName);
        response.put("hotline", hotline);
        response.put("datasourceUrl", datasourceUrl);
        response.put("status", "SUCCESS");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/medicines")
    public ResponseEntity<List<Medicine>> getAllMedicines() {
        return ResponseEntity.ok(medicineService.getAllMedicines());
    }

    /**
     * API Lấy chi tiết thuốc theo ID.
     * Sử dụng Redis Cache qua MedicineService.getMedicineById(id)
     */
    @GetMapping("/medicines/{id}")
    public ResponseEntity<Medicine> getMedicineById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Medicine medicine = medicineService.getMedicineById(id);
        long duration = System.currentTimeMillis() - startTime;
        log.info("API GET /api/pharmacy/medicines/{} phản hồi trong {} ms", id, duration);

        return ResponseEntity.ok()
                .header("X-Response-Time-Millis", String.valueOf(duration))
                .body(medicine);
    }

    @PostMapping("/medicines")
    public ResponseEntity<Medicine> addMedicine(@RequestBody Medicine medicine) {
        return ResponseEntity.ok(medicineService.addMedicine(medicine));
    }

    /**
     * API Cập nhật thông tin thuốc (sửa giá, số lượng): PUT /api/pharmacy/medicines/{id}
     * Kích hoạt @CacheEvict để xóa cache cũ khỏi Redis.
     */
    @PutMapping("/medicines/{id}")
    public ResponseEntity<Medicine> updateMedicine(@PathVariable Long id, @RequestBody Medicine medicine) {
        medicine.setId(id);
        Medicine updated = medicineService.updateMedicine(medicine);
        return ResponseEntity.ok(updated);
    }

    /**
     * API Nhập hàng: POST /api/pharmacy/import
     * Khi nhập hàng thành công:
     * - Lưu DB & xóa cache cũ.
     * - Gửi message Redis Pub/Sub vào channel "pharmacy-alerts":
     *   { "type": "IMPORT", "message": "Đã nhập 100 hộp Panadol" }
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importMedicine(@RequestBody com.example.pharmacyservice.dto.ImportMedicineRequest request) {
        Medicine saved = medicineService.importMedicine(request);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Nhập hàng thành công và đã gửi thông báo Redis Pub/Sub");
        response.put("channel", "pharmacy-alerts");
        response.put("alertPayload", Map.of(
                "type", "IMPORT",
                "message", String.format("Đã nhập %d hộp %s", request.getQuantity(), saved.getName())
        ));
        response.put("medicine", saved);

        return ResponseEntity.ok(response);
    }

    /**
     * API Bán thuốc trực tiếp sử dụng Redisson Distributed Lock:
     * POST /api/pharmacy/medicines/{id}/sell
     */
    @PostMapping("/medicines/{id}/sell")
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
     *
     * POST /api/pharmacy/medicines/{id}/simulate-concurrent-sell
     *
     * Kết quả mong muốn:
     * - Chỉ có đúng 1 người mua được hàng (status: SUCCESS).
     * - Người kia nhận thông báo hết hàng (status: OUT_OF_STOCK).
     * - Tồn kho còn 0, không bao giờ bị âm kho (-1).
     */
    @PostMapping("/medicines/{id}/simulate-concurrent-sell")
    public ResponseEntity<Map<String, Object>> simulateConcurrentSell(@PathVariable Long id) {
        // Đặt số lượng tồn kho = 1 để mô phỏng "chỉ còn 1 sản phẩm cuối cùng"
        Medicine medicine = medicineRepository.findById(id).orElse(null);
        if (medicine != null) {
            medicine.setQuantity(1);
            medicineRepository.save(medicine);
        }

        // Tạo 2 luồng đại diện cho 2 nhân viên ở 2 máy tính khác nhau cùng nhấn thanh toán
        CompletableFuture<SellMedicineResult> threadA = CompletableFuture.supplyAsync(() ->
                medicineService.sellMedicine(id, 1, "Nhân viên A (Máy 1)")
        );

        CompletableFuture<SellMedicineResult> threadB = CompletableFuture.supplyAsync(() ->
                medicineService.sellMedicine(id, 1, "Nhân viên B (Máy 2)")
        );

        // Chờ cả 2 luồng hoàn thành
        CompletableFuture.allOf(threadA, threadB).join();

        SellMedicineResult resultA = threadA.join();
        SellMedicineResult resultB = threadB.join();

        Medicine updatedMed = medicineRepository.findById(id).orElse(null);
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
