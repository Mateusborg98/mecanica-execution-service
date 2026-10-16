package br.com.techchallenge.mecanica.execution.infrastructure;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import br.com.techchallenge.mecanica.execution.application.*;
@Configuration
public class ExecutionConfig {
    @Bean public DiagnosticoGateway diagnosticoGateway(JdbcTemplate jdbc, Transacao tx) {
        return new DiagnosticoJdbcGateway(jdbc, tx, Clock.systemUTC());
    }
    @Bean public DiagnosticoService diagnosticoService(DiagnosticoGateway gateway) { return new DiagnosticoService(gateway); }
    @Bean public Transacao transacao(PlatformTransactionManager manager) { return new TransacaoSpring(manager); }
    @Bean public ReparoGateway reparoGateway(JdbcTemplate jdbc) { return new ReparoJdbcGateway(jdbc); }
    @Bean public EstoqueGateway estoqueGateway(JdbcTemplate jdbc, Transacao tx) { return new EstoqueJdbcGateway(jdbc, tx); }
    @Bean public EstoqueService estoqueService(EstoqueGateway gateway) { return new EstoqueService(gateway); }
    @Bean public ReparoService reparoService(ReparoGateway gateway, EstoqueGateway estoque, Transacao tx) {
        return new ReparoService(gateway, Clock.systemUTC(), estoque, tx);
    }
}
