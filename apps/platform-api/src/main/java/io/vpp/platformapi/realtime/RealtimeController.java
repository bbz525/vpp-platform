package io.vpp.platformapi.realtime;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.vpp.platformapi.realtime.RealtimeDtos.PortfolioSnapshot;
import io.vpp.platformapi.realtime.RealtimeDtos.TelemetryQueryResponse;
import io.vpp.platformapi.security.ActorResolver;
import io.vpp.platformapi.security.Role;

@Validated
@RestController
@RequestMapping("/api/v1")
public class RealtimeController {
    private final ActorResolver actors;
    private final RealtimeService realtime;
    private final RealtimeTicketService tickets;

    public RealtimeController(ActorResolver actors, RealtimeService realtime,
            RealtimeTicketService tickets) {
        this.actors = actors;
        this.realtime = realtime;
        this.tickets = tickets;
    }

    @org.springframework.web.bind.annotation.PostMapping("/realtime/tickets")
    public RealtimeTicketService.Ticket realtimeTicket() {
        return tickets.issue(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN));
    }

    @GetMapping("/portfolios/{portfolioId}/snapshot")
    public PortfolioSnapshot portfolioSnapshot(@PathVariable UUID portfolioId) {
        return realtime.portfolioSnapshot(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                Role.AUDITOR, Role.SITE_ADMIN).tenantId(), portfolioId);
    }

    @GetMapping("/telemetry/query")
    public TelemetryQueryResponse telemetry(
            @RequestParam @Pattern(regexp = "PORTFOLIO|SITE|DEVICE", flags = Pattern.Flag.CASE_INSENSITIVE)
            String targetType,
            @RequestParam UUID targetId,
            @RequestParam @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$") String metric,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return realtime.query(actors.require(Role.TENANT_ADMIN, Role.OPERATOR,
                        Role.AUDITOR, Role.SITE_ADMIN).tenantId(),
                targetType, targetId, metric, from, to);
    }
}
