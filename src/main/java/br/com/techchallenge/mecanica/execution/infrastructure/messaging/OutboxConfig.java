package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import br.com.techchallenge.mecanica.execution.application.*;
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="integration.messaging.enabled", havingValue="true")
public class OutboxConfig {
    @Bean public ConclusaoOutboxWorker conclusaoOutboxWorker(JdbcTemplate jdbc, Transacao tx, RabbitTemplate rabbit) {
        return new ConclusaoOutboxWorker(jdbc,tx,new RabbitConclusaoPublisher(rabbit),Clock.systemUTC());
    }
    @Bean public PublicadorDiagnostico publicadorDiagnostico(RabbitTemplate rabbit) { return new RabbitDiagnosticoPublisher(rabbit); }
    @Bean public DiagnosticoOutboxWorker diagnosticoOutboxWorker(JdbcTemplate jdbc, Transacao tx,
            DiagnosticoGateway diagnosticos, PublicadorDiagnostico publicador) {
        return new DiagnosticoOutboxWorker(jdbc, tx, diagnosticos, publicador, Clock.systemUTC());
    }
}
