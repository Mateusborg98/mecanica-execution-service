package br.com.techchallenge.mecanica.dlq;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Keeps the schema and file used by the previous operational tool. */
public class Audit implements AutoCloseable {
    private final Connection db;
    public record Reservation(String id, boolean publish) { }

    public Audit(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var sql = db.createStatement()) {
            sql.execute("PRAGMA busy_timeout=10000");
            sql.execute("PRAGMA synchronous=FULL");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS attempts (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                  source TEXT NOT NULL, target TEXT NOT NULL, sha TEXT NOT NULL,
                  event_id TEXT NOT NULL, operator TEXT NOT NULL, reason TEXT NOT NULL,
                  state TEXT NOT NULL, created TEXT NOT NULL, updated TEXT NOT NULL,
                  retry_of TEXT, error_type TEXT)
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS transitions (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id TEXT NOT NULL,
                  state TEXT NOT NULL, occurred TEXT NOT NULL)
                """);
        }
    }

    public Reservation reserve(String source, String sha, String eventId, String operator,
                               String reason, String previousHeader, String retryOf) throws SQLException {
        if (operator == null || operator.isBlank() || reason == null || reason.strip().length() < 12)
            throw new IllegalArgumentException("Informe responsável e motivo com pelo menos 12 caracteres.");
        execute("BEGIN IMMEDIATE");
        try {
            var previous = rows("SELECT * FROM attempts WHERE source=? AND sha=? ORDER BY seq DESC LIMIT 1", source, sha);
            if (!previous.isEmpty()) {
                var row = previous.getFirst();
                var id = row.get("id").toString();
                var state = row.get("state").toString();
                if (Set.of("PREPARED", "UNKNOWN").contains(state))
                    throw new IllegalArgumentException("Tentativa anterior incerta: investigar antes de reenviar.");
                if (!id.equals(previousHeader)) {
                    if (Set.of("CONFIRMED", "ACK_SENT").contains(state)) {
                        execute("COMMIT");
                        return new Reservation(id, false);
                    }
                    throw new IllegalArgumentException("Estado anterior incompatível; conferir auditoria.");
                }
                if (!id.equals(retryOf))
                    throw new IllegalArgumentException("Mensagem retornou à DLQ: informe --retry-of após nova análise.");
            } else if (retryOf != null) {
                throw new IllegalArgumentException("Tentativa anterior não consta nesta auditoria.");
            }
            var id = UUID.randomUUID().toString();
            var now = Instant.now().toString();
            execute("""
                INSERT INTO attempts(id,source,target,sha,event_id,operator,reason,state,created,updated,retry_of)
                VALUES (?,?,?,?,?,?,?,'PREPARED',?,?,?)
                """, id, source, DlqRecovery.destination(source), sha, eventId, operator.strip(), reason.strip(), now, now, retryOf);
            execute("INSERT INTO transitions(attempt_id,state,occurred) VALUES (?,'PREPARED',?)", id, now);
            execute("COMMIT");
            return new Reservation(id, true);
        } catch (SQLException | RuntimeException ex) {
            execute("ROLLBACK");
            throw ex;
        }
    }

    public void update(String id, String state, String error) throws SQLException {
        execute("BEGIN IMMEDIATE");
        try {
            var now = Instant.now().toString();
            execute("UPDATE attempts SET state=?,updated=?,error_type=? WHERE id=?", state, now, error, id);
            execute("INSERT INTO transitions(attempt_id,state,occurred) VALUES (?,?,?)", id, state, now);
            execute("COMMIT");
        } catch (SQLException | RuntimeException ex) {
            execute("ROLLBACK");
            throw ex;
        }
    }

    public List<Map<String, Object>> history() throws SQLException {
        return rows("SELECT * FROM attempts ORDER BY seq DESC LIMIT 100");
    }

    private void execute(String query, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(query)) {
            bind(statement, args); statement.execute();
        }
    }

    List<Map<String, Object>> rows(String query, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(query)) {
            bind(statement, args);
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<Map<String, Object>>();
                while (result.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
                        row.put(result.getMetaData().getColumnName(i), result.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }
    @Override public void close() throws SQLException { db.close(); }
}
