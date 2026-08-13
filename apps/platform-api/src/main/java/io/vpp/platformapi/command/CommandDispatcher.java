package io.vpp.platformapi.command;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.vpp.platformapi.audit.AuditOutboxWriter;import io.vpp.platformapi.config.CommandProperties;

@Component @ConditionalOnProperty(name="platform.commands.dispatcher-enabled",havingValue="true")
public class CommandDispatcher {
    private final CommandRepository repository;private final CommandProperties properties;private final AuditOutboxWriter audit;
    CommandDispatcher(CommandRepository r,CommandProperties p,AuditOutboxWriter a){repository=r;properties=p;audit=a;}
    @Scheduled(fixedDelayString="${platform.commands.poll-interval:1s}") @Transactional public void dispatch(){Instant now=Instant.now();for(var c:repository.lockDue(now,properties.batchSize())){repository.dispatch(c,now);audit.systemSucceeded(c.tenantId(),"command-dispatcher","COMMAND_DISPATCHED","COMMAND",c.command().id(),null,c.command(),"DISPATCHED");}}
    @Scheduled(fixedDelayString="${platform.commands.timeout-interval:1s}") @Transactional public void timeout(){Instant now=Instant.now();for(var c:repository.lockExpired(now,properties.batchSize())){repository.timeout(c,now);audit.systemSucceeded(repository.tenantId(c.id()),"command-timeout-scanner","COMMAND_TIMED_OUT","COMMAND",c.id(),"ACK_TIMEOUT",c,"TIMED_OUT");}}
}
