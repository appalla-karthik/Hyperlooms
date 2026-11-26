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
                SELECT id, recipe_id, state, created_at, updated_at
                FROM jobs WHERE id = ? AND requester_subject = ? AND request_version = 1
                """, (rs, row) -> new JobView(rs.getObject("id", UUID.class),
                rs.getObject("recipe_id", UUID.class), JobState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), jobId, requesterSubject);
        return rows.stream().findFirst();
    }
}
