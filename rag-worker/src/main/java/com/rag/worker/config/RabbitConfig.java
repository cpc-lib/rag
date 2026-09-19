package com.rag.worker.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker RabbitMQ 配置：拓扑声明（与 API 幂等同构）+ 手动 ack 监听工厂。
 */
@Configuration
public class RabbitConfig {

    @Bean
    public DirectExchange ragIngestExchange(@Value("${rag.mq.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public Queue parseQueue(@Value("${rag.mq.queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding parseBinding(DirectExchange ragIngestExchange, Queue parseQueue) {
        return BindingBuilder.bind(parseQueue).to(ragIngestExchange).with("parse");
    }

    @Bean
    public Binding reindexBinding(DirectExchange ragIngestExchange, Queue parseQueue) {
        return BindingBuilder.bind(parseQueue).to(ragIngestExchange).with("reindex");
    }

    @Bean
    public Queue retryQueue(@Value("${rag.mq.retry-queue}") String retryQueue,
                            @Value("${rag.mq.exchange}") String exchange) {
        return QueueBuilder.durable(retryQueue)
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", "parse")
                .build();
    }

    @Bean
    public Binding retryBinding(DirectExchange ragIngestExchange, Queue retryQueue) {
        return BindingBuilder.bind(retryQueue).to(ragIngestExchange).with("retry");
    }

    @Bean
    public Queue dlqQueue(@Value("${rag.mq.dlq-queue}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding dlqBinding(DirectExchange ragIngestExchange, Queue dlqQueue) {
        return BindingBuilder.bind(dlqQueue).to(ragIngestExchange).with("dlq");
    }

    @Bean
    public SimpleRabbitListenerContainerFactory ingestFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(2);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
