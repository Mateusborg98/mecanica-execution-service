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

class ConclusaoOutboxTest {
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

    private UUID iniciar() {
        var r=service.criar(UUID.randomUUID());
        service.liberar(r.getId(),UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:"+r.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.iniciar(r.getId()); return r.getId();
    }
    private final Clock futuro=Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"),ZoneOffset.UTC);
    private ConclusaoOutboxWorker worker(java.util.function.Consumer<ReparoConcluido> publisher,Clock clock) {
        return new ConclusaoOutboxWorker(jdbc,tx,publisher,clock);
    }
    @Test void concluirDeveSalvarEstadoEEventoSemDuplicarNovaChamada() {
        var id=iniciar(); var r=service.concluir(id);
        assertThrows(IllegalStateException.class,()->service.concluir(id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_reparos_concluidos",Integer.class));
        var eventos=new java.util.ArrayList<ReparoConcluido>(); var w=worker(eventos::add,futuro);
        assertTrue(w.publicarProximo()); assertFalse(w.publicarProximo());
        assertEquals(r.getConcluidoEm(),eventos.getFirst().ocorridoEm());
        assertEquals(id,eventos.getFirst().eventId()); assertEquals(r.getOrcamentoId(),eventos.getFirst().orcamentoId());
        assertNotNull(jdbc.queryForObject("SELECT publicado_em FROM outbox_reparos_concluidos",java.sql.Timestamp.class));
    }
    @Test void falhaAoGravarOutboxDeveReverterConclusao() {
        var id=iniciar(); var r=service.buscar(id);
        jdbc.update("INSERT INTO outbox_reparos_concluidos(event_id,reparo_id,ordem_id,orcamento_id,ocorrido_em) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
                id,id,r.getOrdemId(),r.getOrcamentoId());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->service.concluir(id));
        assertEquals(StatusReparo.EM_EXECUCAO,service.buscar(id).getStatus());
        assertNull(service.buscar(id).getConcluidoEm()); assertEquals(2,gateway.buscar(id).orElseThrow().versao());
    }
    @Test void envioSemConfirmacaoDevePermanecerPendenteERepetirMesmoEvento() {
        var id=iniciar(); service.concluir(id); var eventos=new java.util.ArrayList<ReparoConcluido>();
        var w=worker(event->{eventos.add(event); throw new IllegalStateException("Sem confirmacao");},futuro);
        assertFalse(w.publicarProximo()); assertFalse(w.publicarProximo());
        assertNull(jdbc.queryForObject("SELECT publicado_em FROM outbox_reparos_concluidos",java.sql.Timestamp.class));
        assertTrue(worker(eventos::add,Clock.offset(futuro,Duration.ofSeconds(31))).publicarProximo());
        assertEquals(eventos.getFirst(),eventos.getLast());
        assertEquals(2,jdbc.queryForObject("SELECT tentativas FROM outbox_reparos_concluidos",Integer.class));
    }
    @Test void publicadoresConcorrentesDevemPularEventoBloqueado() throws Exception {
        service.concluir(iniciar()); var entrou=new java.util.concurrent.CountDownLatch(1); var liberar=new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<ReparoConcluido> lento=event->{
            entrou.countDown();
            try { if(!liberar.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->worker(lento,futuro).publicarProximo());
            try { assertTrue(entrou.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertFalse(worker(event->fail("Duplicado"),futuro).publicarProximo()); }
            finally { liberar.countDown(); }
            assertTrue(a.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void duasConclusoesConcorrentesDevemCriarUmEvento() throws Exception {
        var id=iniciar(); var largada=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> tarefa=()->{
            largada.await(); try { service.concluir(id); return true; }
            catch(IllegalStateException | ConflitoReparoException ex) { return false; }
        };
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(tarefa); var b=pool.submit(tarefa); largada.countDown();
            assertNotEquals(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_reparos_concluidos",Integer.class));
    }
    @Test void migracaoNaoDevePublicarConclusoesHistoricasSemReconciliacao() {
        service.concluir(iniciar()); jdbc.execute("DROP TABLE outbox_reparos_concluidos");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__outbox_reparos_concluidos.sql")).execute(ds);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_reparos_concluidos",Integer.class));
    }
}
