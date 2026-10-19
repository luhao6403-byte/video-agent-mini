package com.example.videoagent;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "video-agent-mini-health",
        consumerGroup = "video-agent-mini-health-consumer"
)
public class RocketMqHealthConsumer implements RocketMQListener<String> {

    @Override
    public void onMessage(String message) {
        System.out.println("收到 RocketMQ 测试消息：" + message);
    }
}