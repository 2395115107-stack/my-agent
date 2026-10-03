package com.myagent.team;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * team.* 实时事件(SSE)。对齐 AionUi 19 个 team.* WS 事件的最小子集:
 * task_changed / mailbox_changed / agent_status_changed / teammate_message。
 */
@Component
public class TeamEventPublisher {

    public record TeamEvent(String event, Long teamId, String payload) {}

    private final Map<Long, Sinks.Many<TeamEvent>> sinks = new ConcurrentHashMap<>();

    public Flux<TeamEvent> subscribe(Long teamId) {
        return sinks.computeIfAbsent(teamId,
                        k -> Sinks.many().multicast().onBackpressureBuffer(1024, false))
                .asFlux();
    }

    public void publish(String event, Long teamId, String payload) {
        Sinks.Many<TeamEvent> sink = sinks.get(teamId);
        if (sink != null) {
            sink.tryEmitNext(new TeamEvent(event, teamId, payload));
        }
    }
}
