package io.vpp.iotgateway.kafka;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Component
public class KafkaTemplateSender implements AsyncKafkaSender {
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public KafkaTemplateSender(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public CompletableFuture<SendResult<String, byte[]>> send(
            ProducerRecord<String, byte[]> record) {
        return kafkaTemplate.send(record);
    }
}
