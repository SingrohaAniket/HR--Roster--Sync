package com.moveinsync.roster.outbox;

import com.moveinsync.roster.config.OutboxProperties;
import com.moveinsync.roster.config.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutNanos;

    public OutboxPublisher(RabbitTemplate rabbitTemplate, OutboxProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.confirmTimeoutMs());
    }

    public Set<Long> publish(List<ClaimedMessage> messages) {
        Map<Long, CorrelationData> sent = new LinkedHashMap<>();
        for (ClaimedMessage message : messages) {
            CorrelationData correlation = new CorrelationData(String.valueOf(message.id()));
            try {
                rabbitTemplate.send(RabbitConfig.EXCHANGE, message.eventType(), toAmqp(message), correlation);
                sent.put(message.id(), correlation);
            } catch (AmqpException e) {
                log.warn("Could not reach the broker after sending {} of {} messages: {}",
                        sent.size(), messages.size(), e.getClass().getSimpleName());
                break;
            }
        }
        return awaitAcknowledgements(sent);
    }

    private Set<Long> awaitAcknowledgements(Map<Long, CorrelationData> sent) {
        Set<Long> acknowledged = new HashSet<>();
        long deadline = System.nanoTime() + confirmTimeoutNanos;
        for (Map.Entry<Long, CorrelationData> entry : sent.entrySet()) {
            try {
                CorrelationData.Confirm confirm = entry.getValue().getFuture()
                        .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (confirm.isAck() && entry.getValue().getReturned() == null) {
                    acknowledged.add(entry.getKey());
                }
            } catch (TimeoutException | ExecutionException e) {
                log.debug("No acknowledgement for outbox row {}", entry.getKey());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return acknowledged;
    }

    private static Message toAmqp(ClaimedMessage message) {
        return MessageBuilder.withBody(message.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(String.valueOf(message.id()))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
