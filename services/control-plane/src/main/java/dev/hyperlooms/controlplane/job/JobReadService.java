package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobReadService {

    private final JdbcTemplate jdbc;

    public JobReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JobView> findOwned(UUID jobId, String requesterSubject) {
        List<JobView> rows = jdbc.query("""
                SELECT id, recipe_id, state, created_at, updated_at, stop_requested_at
                FROM jobs WHERE id = ? AND requester_subject = ? AND request_version = 1
                """, (rs, row) -> new JobView(rs.getObject("id", UUID.class),
                rs.getObject("recipe_id", UUID.class), JobState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("stop_requested_at") != null), jobId, requesterSubject);
        return rows.stream().findFirst();
    }

    public List<JobView> recentOwned(String requesterSubject) {
        return jdbc.query("""
                SELECT id, recipe_id, state, created_at, updated_at, stop_requested_at
                FROM jobs WHERE requester_subject = ? AND request_version = 1
                ORDER BY created_at DESC, id DESC LIMIT 50
                """, (rs, row) -> new JobView(rs.getObject("id", UUID.class),
                rs.getObject("recipe_id", UUID.class), JobState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("stop_requested_at") != null), requesterSubject);
    }

    public List<RecipeView> approvedRecipes() {
        return jdbc.query("""
                SELECT id, name, max_runtime_seconds, min_vram_bytes
                FROM workload_recipes WHERE state = 'APPROVED' AND approved_at IS NOT NULL
                ORDER BY name LIMIT 50
                """, (rs, row) -> new RecipeView(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getInt("max_runtime_seconds"),
                rs.getLong("min_vram_bytes")));
    }

    public record RecipeView(UUID recipeId, String name, int maxRuntimeSeconds,
            long minVramBytes) { }
}
