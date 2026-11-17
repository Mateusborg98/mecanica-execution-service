package br.com.techchallenge.mecanica.execution.bdd;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.pt.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.domain.estoque.ItemReserva;
import br.com.techchallenge.mecanica.execution.infrastructure.ExecutionConfig;
import br.com.techchallenge.mecanica.execution.presentation.*;

public class ReparoSteps {
    private HikariDataSource ds;
    private JdbcTemplate jdbc;
    private JdbcTemplate admin;
    private String schema;
    private ReparoService reparos;
    private EstoqueService estoque;
    private MockMvc mvc;
    private UUID reparoId;
    private UUID pecaId;

    @Before
    public void prepararBancoIsolado() {
        ds = new HikariDataSource();
        if ("true".equals(System.getenv("RUN_POSTGRES_TESTS"))) {
            var url = System.getenv().getOrDefault("TEST_POSTGRES_URL", "jdbc:postgresql://localhost:5435/mecanica_execution");
            admin = new JdbcTemplate(new DriverManagerDataSource(url, "mecanica_execution", "execution_local"));
            schema = "bdd_" + UUID.randomUUID().toString().replace("-", "");
            admin.execute("CREATE SCHEMA " + schema);
            ds.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            ds.setUsername("mecanica_execution"); ds.setPassword("execution_local");
        } else {
            ds.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        }
        jdbc = new JdbcTemplate(ds);
        var migrations = new ResourceDatabasePopulator();
        for (var file : List.of("V1__criar_reparos.sql", "V2__catalogo_e_reservas.sql", "V3__servicos_e_diagnosticos.sql",
                "V4__outbox_diagnosticos.sql", "V5__inbox_decisoes_orcamento.sql", "V6__outbox_reparos_concluidos.sql",
                "V8__outbox_reparos_iniciados.sql", "V9__outbox_reparos_cancelados.sql")) {
            migrations.addScript(new ClassPathResource("db/migration/" + file));
        }
        migrations.execute(ds);
        var config = new ExecutionConfig();
        var tx = config.transacao(new DataSourceTransactionManager(ds));
        var gateway = config.estoqueGateway(jdbc, tx);
        reparos = config.reparoService(config.reparoGateway(jdbc, tx), gateway, tx);
        estoque = new EstoqueService(gateway);
        mvc = MockMvcBuilders.standaloneSetup(new ReparoController(reparos))
                .setControllerAdvice(new ReparoExceptionHandler()).build();
    }

    @After
    public void fecharBanco() {
        try {
            if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
            else if (jdbc != null) jdbc.execute("SHUTDOWN");
        } finally { if (ds != null) ds.close(); }
    }

    @Dado("um reparo aguardando aprovação com duas peças reservadas de um estoque de cinco")
    public void reservarPecas() {
        reparoId = reparos.criar(UUID.randomUUID()).getId();
        pecaId = estoque.criar("BDD-" + UUID.randomUUID(), "Filtro", new BigDecimal("25.50")).id();
        estoque.entrada(UUID.randomUUID(), pecaId, 5);
        estoque.reservar(reparoId, List.of(new ItemReserva(pecaId, 2)));
    }

    @Quando("o orçamento do reparo é aprovado")
    public void aprovar() { reparos.liberar(reparoId, UUID.randomUUID()); }

    @Quando("o operador inicia o reparo")
    public void iniciar() throws Exception { transicao("iniciar", "EM_EXECUCAO"); }

    @Quando("o operador conclui o reparo")
    public void concluir() throws Exception { transicao("concluir", "CONCLUIDO"); }

    @Quando("o operador cancela o reparo duas vezes")
    public void cancelarDuasVezes() throws Exception {
        transicao("cancelar", "CANCELADO"); transicao("cancelar", "CANCELADO");
    }

    @Quando("o operador tenta iniciar o reparo sem aprovação")
    @Então("uma tentativa de iniciar o reparo é rejeitada")
    public void rejeitarInicio() throws Exception { rejeitar("iniciar"); }

    @Então("uma tentativa de cancelar o reparo é rejeitada")
    public void rejeitarCancelamento() throws Exception { rejeitar("cancelar"); }

    @Então("o reparo permanece concluído ao ser consultado")
    public void consultarConcluido() throws Exception {
        consultar("CONCLUIDO");
        var salvo = reparos.buscar(reparoId);
        assertNotNull(salvo.getIniciadoEm()); assertNotNull(salvo.getConcluidoEm());
        assertFalse(salvo.getConcluidoEm().isBefore(salvo.getIniciadoEm()));
    }

    @Então("o reparo permanece cancelado ao ser consultado")
    public void consultarCancelado() throws Exception { consultar("CANCELADO"); }

    @Então("a operação é rejeitada e o reparo continua aguardando aprovação")
    public void consultarPendente() throws Exception { consultar("AGUARDANDO_APROVACAO"); }

    @Então("o estoque disponível é {int} e a reserva está {string}")
    public void conferirEstoque(int quantidade, String status) {
        assertEquals(quantidade, estoque.buscar(pecaId).disponivel());
        assertEquals(status, estoque.buscarReserva(reparoId).status().name());
    }

    @Então("existe um único evento de conclusão pendente na outbox")
    public void conferirConclusao() { conferirOutbox("outbox_reparos_concluidos", 1); }

    @Então("não existe evento de conclusão na outbox")
    public void semConclusao() { conferirOutbox("outbox_reparos_concluidos", 0); }

    @Então("existe um único evento de cancelamento pendente na outbox")
    public void conferirCancelamento() { conferirOutbox("outbox_reparos_cancelados", 1); }

    private void conferirOutbox(String tabela, int esperado) {
        assertEquals(esperado, jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE reparo_id = ? AND publicado_em IS NULL", Integer.class, reparoId));
    }
    private void transicao(String acao, String esperado) throws Exception {
        mvc.perform(post("/reparos/" + reparoId + "/" + acao))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(esperado));
    }
    private void rejeitar(String acao) throws Exception {
        mvc.perform(post("/reparos/" + reparoId + "/" + acao)).andExpect(status().isConflict());
    }
    private void consultar(String esperado) throws Exception {
        mvc.perform(get("/reparos/" + reparoId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(esperado));
    }
}
