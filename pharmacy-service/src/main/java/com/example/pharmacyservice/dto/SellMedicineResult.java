package com.example.pharmacyservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellMedicineResult implements Serializable {
    private String status;         // SUCCESS, OUT_OF_STOCK, LOCK_TIMEOUT, ERROR
    private String message;        // "Bán thuốc thành công!", "Sản phẩm đã hết hàng!"
    private String employeeName;   // "Nhân viên A", "Nhân viên B"
    private Long medicineId;
    private String medicineName;
    private Integer quantitySold;
    private Integer remainingStock;
    private Double unitPrice;
    private Double totalAmount;
}
