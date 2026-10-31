package com.moveinsync.roster.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "roster.events";
    public static final String QUEUE = "roster.events.all";

    private static final int CHANNEL_RPC_TIMEOUT_MS = 5_000;

    @Bean
    public TopicExchange rosterEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue rosterEventsQueue() {
        return new Queue(QUEUE, true, false, false);
    }

    @Bean
    public Binding rosterEventsBinding(Queue rosterEventsQueue, TopicExchange rosterEventsExchange) {
        return BindingBuilder.bind(rosterEventsQueue).to(rosterEventsExchange).with("#");
    }

    @Bean
    public ConnectionFactoryCustomizer rabbitChannelTimeout() {
        return factory -> factory.setChannelRpcTimeout(CHANNEL_RPC_TIMEOUT_MS);
    }
}
