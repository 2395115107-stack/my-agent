package com.myagent.team.patrol;

import com.myagent.team.entity.Team;
import com.myagent.team.mapper.TeamMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡查接口(调度台右侧「巡查」页签):
 * - GET  /api/team/{teamId}/patrol           发现列表(未解决在前)
 * - POST /api/team/{teamId}/patrol/run       立即巡查一轮(与定时巡查同一链路,含自动处置)
 * - POST /api/team/{teamId}/patrol/findings/{findingId}/ack  认领发现(停止通知,CRITICAL 升级会重开)
 */
@RestController
@RequestMapping("/api/team/{teamId}/patrol")
@RequiredArgsConstructor
public class PatrolController {

    private final PatrolService patrolService;
    private final TeamMapper teamMapper;

    @GetMapping
    public List<PatrolFinding> list(@PathVariable Long teamId) {
        return patrolService.listFindings(teamId);
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable Long teamId) {
        Team team = requireTeam(teamId);
        List<PatrolFinding> touched = patrolService.patrolTeam(team, "manual");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trigger", "manual");
        result.put("detected", touched.size());
        result.put("findings", patrolService.listFindings(teamId));
        return result;
    }

    @PostMapping("/findings/{findingId}/ack")
    public PatrolFinding ack(@PathVariable Long teamId, @PathVariable Long findingId) {
        return patrolService.ack(findingId);
    }

    private Team requireTeam(Long teamId) {
        Team team = teamMapper.selectOneById(teamId);
        if (team == null) {
            throw new IllegalArgumentException("team not found: " + teamId);
        }
        return team;
    }
}
