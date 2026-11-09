package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.infrastructure.DecisaoOrcamentoJdbcGateway;
@Configuration
@ConditionalOnProperty(name="integration.messaging.enabled", havingValue="true")
public class DecisaoMessagingConfig {
    @Bean public DecisaoOrcamentoListener decisaoOrcamentoListener(JdbcTemplate jdbc, Transacao tx, ReparoService reparos) {
        return new DecisaoOrcamentoListener(new DecisaoOrcamentoJdbcGateway(jdbc, tx, reparos));
    }
}
