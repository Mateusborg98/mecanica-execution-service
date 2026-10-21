package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
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

class ReparoApiTest {
    private JdbcTemplate jdbc;
    private com.zaxxer.hikari.HikariDataSource ds;
    private ReparoGateway gateway;
    private EstoqueGateway estoque;
    private Transacao tx;
    private ReparoService service;
    private MockMvc mvc;
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
        mvc = MockMvcBuilders.standaloneSetup(new ReparoController(service))
                .setControllerAdvice(new ReparoExceptionHandler()).build();
    }
    @AfterEach void limpar() {
        if (jdbc != null) {
            if (postgres) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            ds.close();
        }
    }
    @Test void deveCriarConsultarEImpedirDuplicidadePorOrdem() throws Exception {
        var ordem = UUID.randomUUID();
        var result = mvc.perform(post("/reparos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ordemId\":\"" + ordem + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AGUARDANDO_APROVACAO"))
                .andReturn().getResponse();
        mvc.perform(get(result.getHeader("Location"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ordemId").value(ordem.toString()));
        mvc.perform(post("/reparos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ordemId\":\"" + ordem + "\"}"))
                .andExpect(status().isConflict());
    }
    @Test void devePersistirFluxoCompletoAposAprovacao() throws Exception {
        var r = service.criar(UUID.randomUUID()); var orcamento = UUID.randomUUID();
        service.liberar(r.getId(), orcamento);
        mvc.perform(post("/reparos/" + r.getId() + "/iniciar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_EXECUCAO"));
        mvc.perform(post("/reparos/" + r.getId() + "/concluir")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONCLUIDO"));
        var salvo = service.buscar(r.getId()); assertEquals(orcamento, salvo.getOrcamentoId());
        assertNotNull(salvo.getIniciadoEm()); assertNotNull(salvo.getConcluidoEm());
        assertEquals(3, gateway.buscar(r.getId()).orElseThrow().versao());
        assertThrows(IllegalStateException.class, () -> service.cancelar(r.getId()));
    }
    @Test void naoDeveIniciarSemAprovacao() throws Exception {
        var r = service.criar(UUID.randomUUID());
        mvc.perform(post("/reparos/" + r.getId() + "/iniciar")).andExpect(status().isConflict());
        assertEquals(StatusReparo.AGUARDANDO_APROVACAO, service.buscar(r.getId()).getStatus());
    }
    @Test void deveCancelarAntesDeIniciar() throws Exception {
        var r = service.criar(UUID.randomUUID());
        mvc.perform(post("/reparos/" + r.getId() + "/cancelar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"));
        assertEquals(StatusReparo.CANCELADO, service.buscar(r.getId()).getStatus());
    }
    @Test void deveRetornar404E400() throws Exception {
        mvc.perform(get("/reparos/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/reparos/invalido")).andExpect(status().isBadRequest());
        mvc.perform(post("/reparos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/reparos").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        assertThrows(IllegalArgumentException.class, () -> service.criar(null));
    }
    @Test void deveAceitarTransicoesNoMesmoInstanteComPrecisaoDoBanco() {
        var fixo = new ReparoService(gateway, Clock.fixed(Instant.parse("2026-10-14T12:00:00.123456789Z"), ZoneOffset.UTC), estoque, tx);
        var r = fixo.criar(UUID.randomUUID()); fixo.liberar(r.getId(), UUID.randomUUID());
        fixo.iniciar(r.getId()); fixo.concluir(r.getId());
        assertEquals(StatusReparo.CONCLUIDO, fixo.buscar(r.getId()).getStatus());
    }
    @Test void deveRecusarSnapshotObsoleto() {
        var r = service.criar(UUID.randomUUID()); service.liberar(r.getId(), UUID.randomUUID());
        var inicio = gateway.buscar(r.getId()).orElseThrow();
        var cancelamento = gateway.buscar(r.getId()).orElseThrow();
        inicio.reparo().iniciar(Instant.now()); cancelamento.reparo().cancelar();
        gateway.atualizar(inicio);
        assertThrows(ConflitoReparoException.class, () -> gateway.atualizar(cancelamento));
        assertEquals(StatusReparo.EM_EXECUCAO, service.buscar(r.getId()).getStatus());
    }
    @Test void devePreservarUmaUnicaOperacaoConcorrente() throws Exception {
        var r = service.criar(UUID.randomUUID()); service.liberar(r.getId(), UUID.randomUUID());
        var inicio = gateway.buscar(r.getId()).orElseThrow(); inicio.reparo().iniciar(Instant.now());
        var cancelamento = gateway.buscar(r.getId()).orElseThrow(); cancelamento.reparo().cancelar();
        var largada = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> atualizar(inicio, largada));
            var b = pool.submit(() -> atualizar(cancelamento, largada)); largada.countDown();
            var iniciou = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var cancelou = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(iniciou, cancelou);
            assertEquals(iniciou ? StatusReparo.EM_EXECUCAO : StatusReparo.CANCELADO, service.buscar(r.getId()).getStatus());
        }
    }
    private boolean atualizar(ReparoGateway.Registro registro, java.util.concurrent.CountDownLatch largada) throws Exception {
        largada.await();
        try { gateway.atualizar(registro); return true; } catch (ConflitoReparoException e) { return false; }
    }
}
