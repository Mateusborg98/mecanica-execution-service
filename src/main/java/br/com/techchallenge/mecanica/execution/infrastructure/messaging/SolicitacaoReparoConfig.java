package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import br.com.techchallenge.mecanica.execution.application.*;
import br.com.techchallenge.mecanica.execution.infrastructure.SolicitacaoReparoJdbcGateway;
@Configuration
@ConditionalOnProperty(name="integration.messaging.enabled",havingValue="true")
public class SolicitacaoReparoConfig {
    @Bean SolicitacaoReparoListener solicitacaoReparoListener(JdbcTemplate jdbc,Transacao tx) {
        return new SolicitacaoReparoListener(new SolicitacaoReparoJdbcGateway(jdbc,tx));
    }
    @Bean public Declarables solicitacaoReparoTopology() {
        var exchange=new DirectExchange("mecanica.events",true,false);
        var dlx=new DirectExchange("mecanica.dlx",true,false);
        var queue=QueueBuilder.durable("execution.reparo-solicitado.v1").deadLetterExchange("mecanica.dlx")
            .deadLetterRoutingKey("execution.reparo-solicitado.v1").build();
        var dlq=QueueBuilder.durable("execution.reparo-solicitado.v1.dlq").build();
        return new Declarables(exchange,dlx,queue,dlq,
            BindingBuilder.bind(queue).to(exchange).with("reparo.solicitado.v1"),
            BindingBuilder.bind(dlq).to(dlx).with("execution.reparo-solicitado.v1"));
    }
}
