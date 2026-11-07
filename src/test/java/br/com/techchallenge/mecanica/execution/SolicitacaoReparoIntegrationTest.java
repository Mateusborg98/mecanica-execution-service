package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import br.com.techchallenge.mecanica.execution.infrastructure.*;
class SolicitacaoReparoIntegrationTest {
    JdbcTemplate jdbc; com.zaxxer.hikari.HikariDataSource ds;
    SolicitacaoReparoJdbcGateway gateway; String schema; boolean postgres;
    UUID ordem,reparo; final Instant data=Instant.parse("2026-10-27T15:00:00Z");
    @BeforeEach void setup() {
        postgres="true".equals(System.getenv("RUN_POSTGRES_TESTS")); ds=new com.zaxxer.hikari.HikariDataSource();
        if(postgres) {
            String url=System.getenv().getOrDefault("TEST_POSTGRES_URL","jdbc:postgresql://localhost:5435/mecanica_execution");
            schema="test_"+UUID.randomUUID().toString().replace("-","");
            new JdbcTemplate(new DriverManagerDataSource(url,"mecanica_execution","execution_local")).execute("CREATE SCHEMA "+schema);
            ds.setJdbcUrl(url+"?currentSchema="+schema); ds.setUsername("mecanica_execution");ds.setPassword("execution_local");
        } else ds.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__criar_reparos.sql"), new ClassPathResource("db/migration/V9__outbox_reparos_cancelados.sql"), new ClassPathResource("db/migration/V8__outbox_reparos_iniciados.sql"),
            new ClassPathResource("db/migration/V7__solicitacoes_reparo_recebidas.sql")).execute(ds);
        var tx=new ExecutionConfig().transacao(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        gateway=new SolicitacaoReparoJdbcGateway(jdbc,tx); ordem=UUID.randomUUID(); reparo=UUID.randomUUID();
    }
    @AfterEach void cleanup() { if(ds!=null) { if(postgres && jdbc!=null) jdbc.execute("DROP SCHEMA "+schema+" CASCADE");ds.close(); } }
    void receber() { gateway.receber(ordem,reparo,data); }
    @Test void criaReparoUmaVezSemRegredirStatus() {
        receber(); jdbc.update("UPDATE reparos SET status='NA_FILA' WHERE id=?",reparo); receber();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reparos",Integer.class));
        assertEquals("NA_FILA",jdbc.queryForObject("SELECT status FROM reparos WHERE id=?",String.class,reparo));
        assertEquals(ordem,jdbc.queryForObject("SELECT ordem_id FROM reparos WHERE id=?",UUID.class,reparo));
    }
    @Test void rejeitaMensagemConflitante() {
        receber(); assertThrows(IllegalArgumentException.class,()->gateway.receber(ordem,UUID.randomUUID(),data));
        assertThrows(IllegalArgumentException.class,()->gateway.receber(ordem,reparo,data.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->gateway.receber(UUID.randomUUID(),reparo,data));
    }
    @Test void reparoManualConflitanteNaoDeixaInboxConfirmada() {
        jdbc.update("INSERT INTO reparos(id,ordem_id,criado_em,status) VALUES (?,?,?,'AGUARDANDO_APROVACAO')",
                UUID.randomUUID(),ordem,java.sql.Timestamp.from(data));
        assertThrows(IllegalArgumentException.class,this::receber);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM solicitacoes_reparo_recebidas",Integer.class));
    }
    @Test void falhaNaCriacaoReverteInbox() {
        jdbc.update("INSERT INTO reparos(id,ordem_id,criado_em,status) VALUES (?,?,?,'AGUARDANDO_APROVACAO')",
                reparo,UUID.randomUUID(),java.sql.Timestamp.from(data));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,this::receber);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM solicitacoes_reparo_recebidas",Integer.class));
    }
    @Test void concorrenciaNaoDuplicaReparo() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(this::receber);var b=pool.submit(this::receber);
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reparos",Integer.class));
    }
}
