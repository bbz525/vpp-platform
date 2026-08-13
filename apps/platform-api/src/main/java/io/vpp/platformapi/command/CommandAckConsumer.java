package io.vpp.platformapi.command;

import java.time.Instant;import java.util.Set;import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;import org.springframework.kafka.annotation.KafkaListener;import org.springframework.stereotype.Component;import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.ObjectMapper;

@Component
public class CommandAckConsumer {
    private static final Set<String> STATUSES=Set.of("ACCEPTED","EXECUTING","SUCCEEDED","FAILED");
    private final CommandRepository repository;private final ObjectMapper mapper;
    CommandAckConsumer(CommandRepository repository,ObjectMapper mapper){this.repository=repository;this.mapper=mapper;}
    @KafkaListener(topics="vpp.command.events.v1",groupId="platform-command-ack-v1",autoStartup="${platform.commands.consumer-enabled:false}")
    @Transactional public void consume(ConsumerRecord<String,byte[]> record){process(record.key(),record.value());}
    @Transactional public void process(String recordKey,byte[] payload){
        try{JsonNode n=mapper.readTree(payload);UUID event=UUID.fromString(required(n,"event_id"));if(!repository.claim(event))return;
            UUID command=UUID.fromString(required(n,"command_id"));String key=required(n,"idempotency_key");String status=required(n,"status");if(!STATUSES.contains(status))throw new IllegalArgumentException("unsupported command ack status");
            var seed=repository.lockAck(command);if(seed==null)throw new IllegalArgumentException("unknown command");
            if(!recordKey.equals(seed.tenantId()+":"+seed.externalCode()))throw new IllegalArgumentException("command ack device identity mismatch");
            Instant occurred=Instant.parse(required(n,"device_time"));
            repository.ack(command,seed,event,key,status,text(n,"reason_code"),text(n,"message"),n.path("actual").isNull()||n.path("actual").isMissingNode()?null:n.path("actual"),occurred,Instant.now());
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("invalid command ack",e);}}
    private static String required(JsonNode n,String f){String s=n.path(f).asText();if(s==null||s.isBlank())throw new IllegalArgumentException("missing "+f);return s;}
    private static String text(JsonNode n,String f){return n.path(f).isNull()||n.path(f).isMissingNode()?null:n.path(f).asText();}
}
