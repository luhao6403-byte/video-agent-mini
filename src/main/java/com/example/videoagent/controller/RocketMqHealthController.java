package com.example.videoagent.controller;

import java.util.Map;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RocketMqHealthController {

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMqHealthController(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @PostMapping("/health/rocketmq/message")
    public Map<String, Object> sendMessage() {
        SendResult result = rocketMQTemplate.syncSend(
                "video-agent-mini-health",
                "hello-rocketmq"
        );

        return Map.of(
                "component", "rocketmq",
                "status", result.getSendStatus() == SendStatus.SEND_OK
                        ? "UP" : "DOWN",
                "sendStatus", result.getSendStatus().name(),
                "messageId", result.getMsgId()
        );
    }
}