package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
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

class DiagnosticoApiTest {
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
    private Servico maoDeObra() { return diagnosticos.criarServico(UUID.randomUUID().toString(), "Troca de filtro", new BigDecimal("100.00")); }
    private Peca peca() { return catalogo.criar(UUID.randomUUID().toString(), "Filtro", new BigDecimal("25.50")); }
    private UUID reparo() { return service.criar(UUID.randomUUID()).getId(); }
    private Diagnostico.Selecao item(Servico s) { return new Diagnostico.Selecao(Diagnostico.Tipo.SERVICO, s.id(), 1); }
    @Test void deveCadastrarEConsultarServicoPorHttp() throws Exception {
        var resposta = mvc.perform(post("/servicos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"TROCA\",\"descricao\":\"Troca de filtro\",\"preco\":100.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        mvc.perform(get(resposta.getHeader("Location"))).andExpect(status().isOk()).andExpect(jsonPath("$.preco").value(100.0));
        assertThrows(IllegalStateException.class, () -> diagnosticos.criarServico("TROCA", "Outro", BigDecimal.ONE));
        mvc.perform(get("/servicos/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @Test void deveRegistrarDiagnosticoMistoEConsultarTotal() throws Exception {
        var p = peca(); var s = maoDeObra(); var id = reparo();
        var body = "{\"observacoes\":\"Trocar filtro\",\"itens\":[{\"tipo\":\"PECA\",\"referenciaId\":\"" + p.id()
                + "\",\"quantidade\":2},{\"tipo\":\"SERVICO\",\"referenciaId\":\"" + s.id() + "\",\"quantidade\":1}]}";
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            mvc.perform(post("/reparos/" + id + "/diagnostico").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.valorTotal").value(151.0));
        }
        mvc.perform(get("/reparos/" + id + "/diagnostico")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].valorUnitario").value(25.5))
                .andExpect(jsonPath("$.ordemId").value(service.buscar(id).getOrdemId().toString()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM itens_diagnostico WHERE reparo_id = ?", Integer.class, id));
        mvc.perform(post("/reparos/" + id + "/diagnostico").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("Trocar filtro", "Outro diagnóstico"))).andExpect(status().isConflict());
    }
    @Test void devePreservarValoresMesmoAposAlteracaoDoCatalogo() {
        var p = peca(); var s = maoDeObra(); var id = reparo();
        var selecaoPeca = new Diagnostico.Selecao(Diagnostico.Tipo.PECA, p.id(), 2);
        var original = diagnosticos.registrar(id, "Trocar filtro", List.of(item(s), selecaoPeca));
        jdbc.update("UPDATE pecas SET preco = 50, descricao = 'Nova descrição' WHERE id = ?", p.id());
        jdbc.update("UPDATE servicos SET preco = 200 WHERE id = ?", s.id());
        var repetido = diagnosticos.registrar(id, "Trocar filtro", List.of(selecaoPeca, item(s)));
        assertEquals(original, repetido); assertEquals(new BigDecimal("151.00"), repetido.total());
        assertEquals("Filtro", repetido.itens().getFirst().descricao());
        assertThrows(UnsupportedOperationException.class, () -> repetido.itens().clear());
    }
    @Test void deveDesfazerDiagnosticoSeReferenciaNaoExistir() {
        var id = reparo(); var p = peca();
        assertThrows(RecursoNaoEncontradoException.class, () -> diagnosticos.registrar(id, "Revisão", List.of(
                new Diagnostico.Selecao(Diagnostico.Tipo.PECA, p.id(), 1),
                new Diagnostico.Selecao(Diagnostico.Tipo.SERVICO, UUID.randomUUID(), 1))));
        assertThrows(RecursoNaoEncontradoException.class, () -> diagnosticos.buscar(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM itens_diagnostico WHERE reparo_id = ?", Integer.class, id));
    }
    @Test void deveExigirReparoPendenteEImpedirAlteracaoDoDiagnostico() {
        var s = maoDeObra();
        assertThrows(ReparoNaoEncontradoException.class, () -> diagnosticos.registrar(UUID.randomUUID(), "Revisão", List.of(item(s))));
        var id = reparo(); service.liberar(id, UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> diagnosticos.registrar(id, "Revisão", List.of(item(s))));
        var outro = reparo(); diagnosticos.registrar(outro, "Revisão", List.of(item(s)));
        assertThrows(IllegalStateException.class, () -> diagnosticos.registrar(outro, "Revisão",
                List.of(new Diagnostico.Selecao(Diagnostico.Tipo.SERVICO, s.id(), 2))));
    }
    @Test void deveImpedirInicioSemReservarPecasEDepoisConsumirReservaCorreta() {
        var p = peca(); var id = reparo();
        diagnosticos.registrar(id, "Trocar filtro", List.of(new Diagnostico.Selecao(Diagnostico.Tipo.PECA, p.id(), 2)));
        service.liberar(id, UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> service.iniciar(id));
        assertEquals(StatusReparo.NA_FILA, service.buscar(id).getStatus());
        catalogo.entrada(UUID.randomUUID(), p.id(), 3);
        catalogo.reservar(id, List.of(new ItemReserva(p.id(), 2)));
        service.iniciar(id);
        assertEquals(Reserva.Status.CONSUMIDA, catalogo.buscarReserva(id).status());
        assertEquals(1, catalogo.buscar(p.id()).disponivel());
    }
    @Test void deveRecusarDiagnosticoDivergenteDeReservaExistente() {
        var p = peca(); var id = reparo(); catalogo.entrada(UUID.randomUUID(), p.id(), 3);
        catalogo.reservar(id, List.of(new ItemReserva(p.id(), 1)));
        assertThrows(IllegalStateException.class, () -> diagnosticos.registrar(id, "Trocar filtro",
                List.of(new Diagnostico.Selecao(Diagnostico.Tipo.PECA, p.id(), 2))));
        assertThrows(RecursoNaoEncontradoException.class, () -> diagnosticos.buscar(id));
        diagnosticos.registrar(id, "Trocar filtro", List.of(new Diagnostico.Selecao(Diagnostico.Tipo.PECA, p.id(), 1)));
        service.liberar(id, UUID.randomUUID()); service.iniciar(id);
        assertEquals(Reserva.Status.CONSUMIDA, catalogo.buscarReserva(id).status());
    }
    @Test void deveRecusarReservaDivergenteEPermitirTentativaCorrigida() {
        var p = peca(); var id = reparo(); catalogo.entrada(UUID.randomUUID(), p.id(), 3);
        diagnosticos.registrar(id, "Trocar filtro", List.of(new Diagnostico.Selecao(Diagnostico.Tipo.PECA, p.id(), 2)));
        assertThrows(IllegalStateException.class, () -> catalogo.reservar(id, List.of(new ItemReserva(p.id(), 1))));
        assertEquals(3, catalogo.buscar(p.id()).disponivel());
        assertThrows(RecursoNaoEncontradoException.class, () -> catalogo.buscarReserva(id));
        catalogo.reservar(id, List.of(new ItemReserva(p.id(), 2)));
        assertEquals(1, catalogo.buscar(p.id()).disponivel());
    }
    @Test void devePermitirSomenteMaoDeObraSemReserva() {
        var id = reparo(); diagnosticos.registrar(id, "Inspeção", List.of(item(maoDeObra())));
        service.liberar(id, UUID.randomUUID()); service.iniciar(id); service.concluir(id);
        assertEquals(StatusReparo.CONCLUIDO, service.buscar(id).getStatus());
    }
    @Test void deveAceitarReenvioSimultaneoSemDuplicarDiagnostico() throws Exception {
        var id = reparo(); var selecao = item(maoDeObra()); var largada = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { largada.await(); return diagnosticos.registrar(id, "Inspeção", List.of(selecao)); });
            var b = pool.submit(() -> { largada.await(); return diagnosticos.registrar(id, "Inspeção", List.of(selecao)); });
            largada.countDown(); assertEquals(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM diagnosticos WHERE reparo_id = ?", Integer.class, id));
        }
    }
    @Test void deveValidarDadosEReferenciasHttp() throws Exception {
        mvc.perform(post("/servicos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/reparos/" + UUID.randomUUID() + "/diagnostico")).andExpect(status().isNotFound());
        mvc.perform(post("/reparos/" + UUID.randomUUID() + "/diagnostico").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observacoes\":\"Revisão\",\"itens\":[]}")).andExpect(status().isBadRequest());
        assertThrows(IllegalArgumentException.class, () -> diagnosticos.criarServico(" ", "Teste", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> diagnosticos.criarServico("COD", "Teste", new BigDecimal("0.001")));
        assertThrows(IllegalArgumentException.class, () -> new Diagnostico.Selecao(null, UUID.randomUUID(), 1));
        assertThrows(IllegalArgumentException.class, () -> new Diagnostico.Selecao(Diagnostico.Tipo.PECA, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new Diagnostico.Selecao(Diagnostico.Tipo.PECA, UUID.randomUUID(), 0));
        var selecao = item(maoDeObra());
        assertThrows(IllegalArgumentException.class, () -> diagnosticos.registrar(reparo(), "Teste", List.of(selecao, selecao)));
        assertThrows(IllegalArgumentException.class, () -> diagnosticos.registrar(reparo(), " ", List.of(selecao)));
        assertThrows(IllegalArgumentException.class, () -> diagnosticos.registrar(reparo(), "Teste", null));
    }
}
