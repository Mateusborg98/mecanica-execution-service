package br.com.techchallenge.mecanica.execution;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
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

class EstoqueApiTest {
    private JdbcTemplate jdbc;
    private com.zaxxer.hikari.HikariDataSource ds;
    private ReparoGateway gateway;
    private EstoqueGateway estoque;
    private Transacao tx;
    private ReparoService service;
    private MockMvc mvc;
    private EstoqueService catalogo;
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
        mvc = MockMvcBuilders.standaloneSetup(new ReparoController(service), new EstoqueController(catalogo))
                .setControllerAdvice(new ReparoExceptionHandler()).build();
    }
    @AfterEach void limpar() {
        if (jdbc != null) {
            if (postgres) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            ds.close();
        }
    }
    private Peca peca(int quantidade) {
        var p = catalogo.criar(UUID.randomUUID().toString(), "Filtro", new BigDecimal("25.50"));
        return quantidade == 0 ? p : catalogo.entrada(UUID.randomUUID(), p.id(), quantidade);
    }
    private UUID reparo() { return service.criar(UUID.randomUUID()).getId(); }
    @Test void deveCriarConsultarPecaEDarEntradaPelaApi() throws Exception {
        var resposta = mvc.perform(post("/pecas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"FILTRO-1\",\"descricao\":\"Filtro\",\"preco\":25.50}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.disponivel").value(0))
                .andReturn().getResponse();
        var location = resposta.getHeader("Location");
        mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.preco").value(25.5));
        var body = "{\"movimentoId\":\"" + UUID.randomUUID() + "\",\"quantidade\":10}";
        mvc.perform(post(location + "/entradas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disponivel").value(10));
        mvc.perform(post(location + "/entradas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disponivel").value(10));
        assertThrows(IllegalStateException.class, () -> catalogo.criar("FILTRO-1", "Outro", BigDecimal.ONE));
    }
    @Test void deveReservarPelaApiECompensarCancelamento() throws Exception {
        var p = peca(10); var id = reparo();
        var body = "{\"itens\":[{\"pecaId\":\"" + p.id() + "\",\"quantidade\":3}]}";
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            mvc.perform(post("/reparos/" + id + "/reserva").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESERVADA"));
        }
        mvc.perform(get("/reparos/" + id + "/reserva")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].quantidade").value(3));
        assertEquals(7, catalogo.buscar(p.id()).disponivel());
        service.cancelar(id);
        assertEquals(10, catalogo.buscar(p.id()).disponivel());
        assertEquals(Reserva.Status.LIBERADA, catalogo.buscarReserva(id).status());
        assertEquals(Reserva.Status.LIBERADA, catalogo.reservar(id, List.of(new ItemReserva(p.id(), 3))).status());
        assertThrows(IllegalStateException.class, () -> service.cancelar(id));
        assertEquals(10, catalogo.buscar(p.id()).disponivel());
    }
    @Test void deveConsumirAoIniciarSemDebitarNovamente() {
        var p = peca(10); var id = reparo();
        catalogo.reservar(id, List.of(new ItemReserva(p.id(), 4)));
        service.liberar(id, UUID.randomUUID()); service.iniciar(id); service.concluir(id);
        assertEquals(6, catalogo.buscar(p.id()).disponivel());
        assertEquals(Reserva.Status.CONSUMIDA, catalogo.buscarReserva(id).status());
        assertThrows(IllegalStateException.class, () -> service.cancelar(id));
    }
    @Test void deveDesfazerReservaInteiraSeSegundoItemNaoTiverSaldo() {
        var a = peca(4); var b = peca(4); var id = reparo();
        var ordenados = java.util.stream.Stream.of(a, b).sorted(java.util.Comparator.comparing(p -> p.id().toString())).toList();
        assertThrows(IllegalStateException.class, () -> catalogo.reservar(id,
                List.of(new ItemReserva(ordenados.get(0).id(), 2), new ItemReserva(ordenados.get(1).id(), 5))));
        assertEquals(4, catalogo.buscar(a.id()).disponivel()); assertEquals(4, catalogo.buscar(b.id()).disponivel());
        assertThrows(RecursoNaoEncontradoException.class, () -> catalogo.buscarReserva(id));
        catalogo.reservar(id, List.of(new ItemReserva(a.id(), 1)));
        assertEquals(3, catalogo.buscar(a.id()).disponivel());
    }
    @Test void deveRecusarMudancaDeConteudoDaRequisicaoRepetida() {
        var p = peca(5); var movimento = UUID.randomUUID();
        catalogo.entrada(movimento, p.id(), 1);
        assertThrows(IllegalStateException.class, () -> catalogo.entrada(movimento, p.id(), 2));
        var outra = peca(0);
        assertThrows(IllegalStateException.class, () -> catalogo.entrada(movimento, outra.id(), 1));
        var id = reparo(); catalogo.reservar(id, List.of(new ItemReserva(p.id(), 2)));
        assertThrows(IllegalStateException.class, () -> catalogo.reservar(id, List.of(new ItemReserva(p.id(), 3))));
        assertEquals(4, catalogo.buscar(p.id()).disponivel());
    }
    @Test void deveRetornar404ParaReferenciasInexistentes() throws Exception {
        mvc.perform(get("/pecas/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/reparos/" + UUID.randomUUID() + "/reserva")).andExpect(status().isNotFound());
        assertThrows(RecursoNaoEncontradoException.class, () -> catalogo.entrada(UUID.randomUUID(), UUID.randomUUID(), 1));
        assertThrows(ReparoNaoEncontradoException.class, () -> catalogo.reservar(UUID.randomUUID(), List.of(new ItemReserva(UUID.randomUUID(), 1))));
        var id = reparo();
        assertThrows(RecursoNaoEncontradoException.class, () -> catalogo.reservar(id, List.of(new ItemReserva(UUID.randomUUID(), 1))));
        assertThrows(RecursoNaoEncontradoException.class, () -> catalogo.buscarReserva(id));
    }
    @Test void deveRecusarReservaDepoisDoInicioOuCancelamento() {
        var p = peca(5); var id = reparo(); service.liberar(id, UUID.randomUUID()); service.iniciar(id);
        assertThrows(IllegalStateException.class, () -> catalogo.reservar(id, List.of(new ItemReserva(p.id(), 1))));
        var cancelado = reparo(); service.cancelar(cancelado);
        assertThrows(IllegalStateException.class, () -> catalogo.reservar(cancelado, List.of(new ItemReserva(p.id(), 1))));
    }
    @Test void deveProtegerSaldoEmDuasReservasConcorrentes() throws Exception {
        var p = peca(5); var primeiro = reparo(); var segundo = reparo();
        var largada = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> tentarReserva(primeiro, p.id(), largada));
            var b = pool.submit(() -> tentarReserva(segundo, p.id(), largada)); largada.countDown();
            boolean ganhouA = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean ganhouB = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(ganhouA, ganhouB); assertEquals(1, catalogo.buscar(p.id()).disponivel());
            assertThrows(RecursoNaoEncontradoException.class, () -> catalogo.buscarReserva(ganhouA ? segundo : primeiro));
        }
    }
    private boolean tentarReserva(UUID reparoId, UUID pecaId, java.util.concurrent.CountDownLatch largada) throws Exception {
        largada.await();
        try { catalogo.reservar(reparoId, List.of(new ItemReserva(pecaId, 4))); return true; }
        catch (IllegalStateException e) { return false; }
    }
    @Test void deveDarEntradaUmaUnicaVezMesmoComDuasThreads() throws Exception {
        var p = peca(0); var movimento = UUID.randomUUID();
        var largada = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { largada.await(); return catalogo.entrada(movimento, p.id(), 3); });
            var b = pool.submit(() -> { largada.await(); return catalogo.entrada(movimento, p.id(), 3); });
            largada.countDown(); a.get(10, java.util.concurrent.TimeUnit.SECONDS); b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(3, catalogo.buscar(p.id()).disponivel());
        }
    }
    @Test void deveValidarDadosSemAlterarSaldo() throws Exception {
        mvc.perform(post("/pecas").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/reparos/" + UUID.randomUUID() + "/reserva").contentType(MediaType.APPLICATION_JSON).content("{\"itens\":[]}"))
                .andExpect(status().isBadRequest());
        assertThrows(IllegalArgumentException.class, () -> new ItemReserva(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ItemReserva(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> catalogo.criar(" ", "Filtro", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> catalogo.criar("SKU", "Filtro", new BigDecimal("1.001")));
        assertThrows(IllegalArgumentException.class, () -> catalogo.entrada(null, UUID.randomUUID(), 1));
        assertThrows(IllegalArgumentException.class, () -> catalogo.entrada(UUID.randomUUID(), UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> catalogo.reservar(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> catalogo.reservar(UUID.randomUUID(), List.of()));
        var item = new ItemReserva(UUID.randomUUID(), 1);
        assertThrows(IllegalArgumentException.class, () -> catalogo.reservar(UUID.randomUUID(), List.of(item, item)));
    }
    @Test void deveReverterCancelamentoSeDevolucaoFalhar() {
        var p = peca(1); var id = reparo(); catalogo.reservar(id, List.of(new ItemReserva(p.id(), 1)));
        catalogo.entrada(UUID.randomUUID(), p.id(), Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> service.cancelar(id));
        assertEquals(StatusReparo.AGUARDANDO_APROVACAO, service.buscar(id).getStatus());
        assertEquals(Reserva.Status.RESERVADA, catalogo.buscarReserva(id).status());
        assertThrows(IllegalStateException.class, () -> catalogo.entrada(UUID.randomUUID(), p.id(), 1));
        assertEquals(Integer.MAX_VALUE, catalogo.buscar(p.id()).disponivel());
    }
    @Test void deveManterReparoEReservaConsistentesNaDisputaEntreInicioECancelamento() throws Exception {
        var p = peca(5); var id = reparo(); catalogo.reservar(id, List.of(new ItemReserva(p.id(), 3)));
        service.liberar(id, UUID.randomUUID());
        var largada = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var inicio = pool.submit(() -> transicao(id, true, largada));
            var cancelamento = pool.submit(() -> transicao(id, false, largada)); largada.countDown();
            boolean iniciou = inicio.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean cancelou = cancelamento.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(iniciou, cancelou);
            assertEquals(iniciou ? 2 : 5, catalogo.buscar(p.id()).disponivel());
            assertEquals(iniciou ? Reserva.Status.CONSUMIDA : Reserva.Status.LIBERADA, catalogo.buscarReserva(id).status());
        }
    }
    private boolean transicao(UUID id, boolean iniciar, java.util.concurrent.CountDownLatch largada) throws Exception {
        largada.await();
        try { if (iniciar) service.iniciar(id); else service.cancelar(id); return true; }
        catch (IllegalStateException | ConflitoReparoException e) { return false; }
    }
}
