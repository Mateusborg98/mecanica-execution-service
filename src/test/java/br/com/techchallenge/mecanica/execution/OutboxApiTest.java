package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import br.com.techchallenge.mecanica.execution.infrastructure.messaging.*;
import static org.mockito.Mockito.*;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.*;
import java.util.List;
import java.math.BigDecimal;
import br.com.techchallenge.mecanica.execution.domain.estoque.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.infrastructure.*;
import br.com.techchallenge.mecanica.execution.presentation.*;
import br.com.techchallenge.mecanica.execution.domain.reparo.*;

class OutboxApiTest {
    private JdbcTemplate jdbc;
    private com.zaxxer.hikari.HikariDataSource ds;
    private ReparoGateway gateway;
    private EstoqueGateway estoque;
    private Transacao tx;
    private ReparoService service;
    private MockMvc mvc;
    private EstoqueService catalogo;
    private DiagnosticoService diagnosticos;
    private String schema;
    private boolean postgres;
    @BeforeEach void configurar() {
        postgres = "true".equals(System.getenv("RUN_POSTGRES_TESTS"));
        ds = new com.zaxxer.hikari.HikariDataSource();
        if (postgres) {
            ds.setJdbcUrl(System.getenv().getOrDefault("TEST_POSTGRES_URL", "jdbc:postgresql://localhost:5435/mecanica_execution"));
            ds.setUsername("mecanica_execution"); ds.setPassword("execution_local");
            schema = "test_" + UUID.randomUUID().toString().replace("-", "");
            var admin = new DriverManagerDataSource(ds.getJdbcUrl(), "mecanica_execution", "execution_local"); new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
            ds.setJdbcUrl(ds.getJdbcUrl() + "?currentSchema=" + schema);
        } else ds.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__criar_reparos.sql"), new ClassPathResource("db/migration/V2__catalogo_e_reservas.sql"), new ClassPathResource("db/migration/V3__servicos_e_diagnosticos.sql"), new ClassPathResource("db/migration/V4__outbox_diagnosticos.sql"), new ClassPathResource("db/migration/V5__inbox_decisoes_orcamento.sql"), new ClassPathResource("db/migration/V6__outbox_reparos_concluidos.sql")).execute(ds);
        var config = new ExecutionConfig();
        tx = config.transacao(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        gateway = config.reparoGateway(jdbc, tx);
        estoque = config.estoqueGateway(jdbc, tx); service = config.reparoService(gateway, estoque, tx);
        catalogo = config.estoqueService(estoque);
        diagnosticos = config.diagnosticoService(config.diagnosticoGateway(jdbc, tx));
        mvc = MockMvcBuilders.standaloneSetup(new ReparoController(service), new EstoqueController(catalogo), new DiagnosticoController(diagnosticos))
                .setControllerAdvice(new ReparoExceptionHandler()).build();
    }
    @AfterEach void limpar() {
        if (jdbc != null) {
            if (postgres) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            ds.close();
        }
    }
    private final Clock envioClock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private UUID diagnostico() {
        var id = service.criar(UUID.randomUUID()).getId();
        var s = diagnosticos.criarServico(UUID.randomUUID().toString(), "Inspeção", BigDecimal.TEN);
        diagnosticos.registrar(id, "Inspecionar", List.of(new Diagnostico.Selecao(Diagnostico.Tipo.SERVICO, s.id(), 1)));
        return id;
    }
    private DiagnosticoOutboxWorker worker(PublicadorDiagnostico publicador, Clock clock) {
        return new DiagnosticoOutboxWorker(jdbc, tx, new DiagnosticoJdbcGateway(jdbc, tx, clock), publicador, clock);
    }
    @Test void deveRegistrarOutboxNaMesmaTransacaoESemDuplicarReenvio() {
        var id = diagnostico(); var d = diagnosticos.buscar(id);
        diagnosticos.registrar(id, d.observacoes(), d.itens().stream().map(Diagnostico.Item::selecao).toList());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_diagnosticos WHERE reparo_id = ?", Integer.class, id));
        var outro = service.criar(UUID.randomUUID()).getId();
        assertThrows(RecursoNaoEncontradoException.class, () -> diagnosticos.registrar(outro, "Falhar", List.of(
                new Diagnostico.Selecao(Diagnostico.Tipo.SERVICO, UUID.randomUUID(), 1))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_diagnosticos WHERE reparo_id = ?", Integer.class, outro));
    }
    @Test void deveMarcarEnviadoSomenteDepoisDeConfirmar() {
        var id = diagnostico(); var publicador = mock(PublicadorDiagnostico.class); var w = worker(publicador, envioClock);
        assertTrue(w.publicarProximo()); assertFalse(w.publicarProximo());
        verify(publicador).publicar(id, diagnosticos.buscar(id));
        assertNotNull(jdbc.queryForObject("SELECT publicado_em FROM outbox_diagnosticos WHERE event_id = ?", java.sql.Timestamp.class, id));
    }
    @Test void deveManterPendenteAposFalhaERetentarDepois() {
        var id = diagnostico(); var publicador = mock(PublicadorDiagnostico.class);
        doThrow(new IllegalStateException("broker indisponível")).doNothing().when(publicador).publicar(any(), any());
        var w = worker(publicador, envioClock); assertFalse(w.publicarProximo()); assertFalse(w.publicarProximo());
        assertNull(jdbc.queryForObject("SELECT publicado_em FROM outbox_diagnosticos WHERE event_id = ?", java.sql.Timestamp.class, id));
        assertTrue(worker(publicador, Clock.offset(envioClock, Duration.ofSeconds(31))).publicarProximo());
        assertEquals(2, jdbc.queryForObject("SELECT tentativas FROM outbox_diagnosticos WHERE event_id = ?", Integer.class, id));
        verify(publicador, times(2)).publicar(any(), any());
    }
    @Test void devePularRegistroBloqueadoPorOutroPublicador() throws Exception {
        diagnostico(); var entrou = new java.util.concurrent.CountDownLatch(1); var liberar = new java.util.concurrent.CountDownLatch(1);
        PublicadorDiagnostico lento = (id, d) -> {
            entrou.countDown();
            try { if (!liberar.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var primeiro = pool.submit(() -> worker(lento, envioClock).publicarProximo());
            try {
                assertTrue(entrou.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(worker(mock(PublicadorDiagnostico.class), envioClock).publicarProximo());
            } finally { liberar.countDown(); }
            assertTrue(primeiro.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void deveIncluirDiagnosticosAnterioresNaMigracao() {
        var id = diagnostico(); jdbc.execute("DROP TABLE outbox_diagnosticos");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V4__outbox_diagnosticos.sql")).execute(ds);
        assertEquals(id, jdbc.queryForObject("SELECT event_id FROM outbox_diagnosticos", UUID.class));
    }
}
