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

class DecisaoOrcamentoIntegrationTest {
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
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__criar_reparos.sql"), new ClassPathResource("db/migration/V2__catalogo_e_reservas.sql"), new ClassPathResource("db/migration/V3__servicos_e_diagnosticos.sql"), new ClassPathResource("db/migration/V4__outbox_diagnosticos.sql"), new ClassPathResource("db/migration/V5__inbox_decisoes_orcamento.sql")).execute(ds);
        var config = new ExecutionConfig(); gateway = config.reparoGateway(jdbc);
        tx = config.transacao(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
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

    private UUID preparar() {
        var r=service.criar(UUID.randomUUID());
        var p=catalogo.criar("SKU-"+UUID.randomUUID(),"Filtro",BigDecimal.TEN);
        catalogo.entrada(UUID.randomUUID(),p.id(),5);
        diagnosticos.registrar(r.getId(),"Diagnóstico",List.of(new Diagnostico.Selecao(Diagnostico.Tipo.PECA,p.id(),2)));
        catalogo.reservar(r.getId(),List.of(new ItemReserva(p.id(),2)));
        return r.getId();
    }
    private UUID budget(UUID id) { return UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private void receber(UUID id,boolean aprovado) {
        new DecisaoOrcamentoJdbcGateway(jdbc,tx,service).aplicar(budget(id),budget(id),id,service.buscar(id).getOrdemId(),aprovado);
    }
    private UUID peca(UUID id) { return diagnosticos.buscar(id).itens().getFirst().referenciaId(); }
    @Test void aprovacaoDeveLiberarEReentregaNaoDeveRegredirReparoEmExecucao() {
        var id=preparar(); receber(id,true);
        assertEquals(StatusReparo.NA_FILA,service.buscar(id).getStatus());
        service.iniciar(id); receber(id,true);
        assertEquals(StatusReparo.EM_EXECUCAO,service.buscar(id).getStatus());
        assertEquals(budget(id),service.buscar(id).getOrcamentoId());
        assertEquals(3,catalogo.buscar(peca(id)).disponivel());
        assertEquals(Reserva.Status.CONSUMIDA,catalogo.buscarReserva(id).status());
    }
    @Test void recusaDeveCancelarEDevolverReservaUmaUnicaVez() {
        var id=preparar(); receber(id,false); receber(id,false);
        assertEquals(StatusReparo.CANCELADO,service.buscar(id).getStatus());
        assertEquals(budget(id),service.buscar(id).getOrcamentoId());
        assertEquals(5,catalogo.buscar(peca(id)).disponivel());
        assertEquals(Reserva.Status.LIBERADA,catalogo.buscarReserva(id).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM decisoes_orcamento_recebidas",Integer.class));
    }
    @Test void falhaNaDevolucaoDeveReverterReparoEInbox() {
        var id=preparar(); var p=peca(id);
        jdbc.update("UPDATE pecas SET disponivel = ? WHERE id = ?",Integer.MAX_VALUE,p);
        assertThrows(IllegalStateException.class,()->receber(id,false));
        assertEquals(StatusReparo.AGUARDANDO_APROVACAO,service.buscar(id).getStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM decisoes_orcamento_recebidas",Integer.class));
        assertEquals(Reserva.Status.RESERVADA,catalogo.buscarReserva(id).status());
        jdbc.update("UPDATE pecas SET disponivel = 3 WHERE id = ?",p); receber(id,false);
        assertEquals(5,catalogo.buscar(p).disponivel());
    }
    @Test void deveRecusarDecisaoConflitanteOuCorrelacaoInvalida() {
        var id=preparar(); var g=new DecisaoOrcamentoJdbcGateway(jdbc,tx,service);
        assertThrows(IllegalArgumentException.class,()->g.aplicar(budget(id),budget(id),id,UUID.randomUUID(),true));
        assertThrows(IllegalArgumentException.class,()->g.aplicar(UUID.randomUUID(),budget(id),id,service.buscar(id).getOrdemId(),true));
        receber(id,true);
        assertThrows(IllegalArgumentException.class,()->receber(id,false));
        assertEquals(StatusReparo.NA_FILA,service.buscar(id).getStatus());
    }
    @Test void naoDeveLiberarReparoCancelado() {
        var id=preparar(); service.cancelar(id);
        assertThrows(IllegalArgumentException.class,()->receber(id,true));
        receber(id,false);
        assertEquals(5,catalogo.buscar(peca(id)).disponivel());
    }
    @Test void deveRecusarReparoSemDiagnosticoEReparoInexistente() {
        var id=service.criar(UUID.randomUUID()).getId();
        assertThrows(IllegalArgumentException.class,()->receber(id,true));
        var ausente=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->new DecisaoOrcamentoJdbcGateway(jdbc,tx,service)
                .aplicar(budget(ausente),budget(ausente),ausente,UUID.randomUUID(),true));
    }
    @Test void reentregasConcorrentesDevemDevolverUmaUnicaVez() throws Exception {
        var id=preparar(); var largada=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{largada.await(); receber(id,false); return true;});
            var b=pool.submit(()->{largada.await(); receber(id,false); return true;});
            largada.countDown(); assertTrue(a.get(10,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(5,catalogo.buscar(peca(id)).disponivel());
    }
}
