package com.example.pharmacyservice.dto;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PharmacyAlertMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Loại sự kiện thông báo (Ví dụ: "IMPORT")
     */
    private String type;

    /**
     * Nội dung thông báo (Ví dụ: "Đã nhập 100 hộp Panadol")
     */
    private String message;
}
