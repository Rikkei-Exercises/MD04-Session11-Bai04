package com.example.pharmacyservice.service;

import com.example.pharmacyservice.config.RedisConfig;
import com.example.pharmacyservice.dto.PharmacyAlertMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

@Service
public class PharmacyAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(PharmacyAlertPublisher.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelTopic alertTopic;

    public PharmacyAlertPublisher(RedisTemplate<String, Object> redisTemplate,
                                  ObjectMapper objectMapper,
                                  ChannelTopic alertTopic) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.alertTopic = alertTopic;
    }

    /**
     * Bắn thông báo vào channel "pharmacy-alerts" qua cơ chế Redis Pub/Sub (Fire-and-forget).
     * Sử dụng ObjectMapper để chuyển đổi đối tượng PharmacyAlertMessage thành chuỗi JSON:
     * { "type": "IMPORT", "message": "Đã nhập 100 hộp Panadol" }
     */
    public void publishAlert(PharmacyAlertMessage alertMessage) {
        try {
            // Serialize đối tượng sang JSON bằng ObjectMapper
            String jsonPayload = objectMapper.writeValueAsString(alertMessage);

            // Gửi message vào channel Redis
            redisTemplate.convertAndSend(alertTopic.getTopic(), jsonPayload);

            log.info(">>> [REDIS PUBLISHER] Đã gửi thông báo vào channel [{}]: {}", alertTopic.getTopic(), jsonPayload);
        } catch (JsonProcessingException e) {
            log.error("Lỗi khi chuyển đổi PharmacyAlertMessage sang JSON", e);
        }
    }
}
