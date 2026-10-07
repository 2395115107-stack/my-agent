package com.myagent.web;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目(dsh workspace 的映射):会话的组织维度。
 * 普通对话 = project_id 为空的会话;删除项目时其会话自动回到普通对话。
 */
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT p.id, p.name, p.description, p.created_at, " +
                        "  (SELECT COUNT(*) FROM tbl_session s WHERE s.project_id = p.id) AS session_count " +
                        "FROM tbl_project p ORDER BY p.id");
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        String description = String.valueOf(body.getOrDefault("description", "")).trim();
        Long id = jdbc.queryForObject(
                "INSERT INTO tbl_project(name, description, created_at) VALUES (?,?,now()) RETURNING id",
                Long.class, name, description.isBlank() ? null : description);
        return Map.of("id", id, "name", name);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        // 会话回到普通对话(不删消息)
        jdbc.update("UPDATE tbl_session SET project_id = NULL WHERE project_id = ?", id);
        jdbc.update("DELETE FROM tbl_project WHERE id = ?", id);
        return Map.of("deleted", id);
    }
}
