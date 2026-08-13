package io.vpp.iotgateway.kafka;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.SendResult;

public interface AsyncKafkaSender {
    CompletableFuture<SendResult<String, byte[]>> send(ProducerRecord<String, byte[]> record);
}
