package com.example.pharmacyservice.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportMedicineRequest {
    private Long medicineId;
    private String medicineName;
    private Integer quantity;
    private Double unitPrice;
}
