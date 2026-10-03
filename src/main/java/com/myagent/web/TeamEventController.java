package com.myagent.web;

import com.myagent.team.TeamEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 团队实时事件(SSE):团队看板订阅 team.task_changed / mailbox_changed /
 * agent_status_changed / teammate_message(对齐 AionUi 19 个 team.* 事件的最小子集)。
 */
@RestController
@RequiredArgsConstructor
public class TeamEventController {

    private final TeamEventPublisher publisher;

    @GetMapping(value = "/api/team/{teamId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TeamEventPublisher.TeamEvent>> events(@PathVariable Long teamId) {
        return publisher.subscribe(teamId)
                .map(e -> ServerSentEvent.<TeamEventPublisher.TeamEvent>builder()
                        .event(e.event())
                        .data(e)
                        .build());
    }
}
