package br.com.techchallenge.mecanica.execution.infrastructure.messaging;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.core.*;
@Configuration
@ConditionalOnProperty(name="integration.messaging.enabled", havingValue="true")
public class RabbitTopology {
    public static final String EXCHANGE = "mecanica.events";
    public static final String ROUTE = "diagnostico.registrado.v1";
    public static final String QUEUE = "billing.diagnostico.v1";
    @Bean public Declarables diagnosticoTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var deadExchange = new DirectExchange("mecanica.dlx", true, false);
        var deadQueue = QueueBuilder.durable("billing.diagnostico.v1.dlq").build();
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange("mecanica.dlx")
                .deadLetterRoutingKey(QUEUE).build();
        return new Declarables(exchange, deadExchange, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(ROUTE),
                BindingBuilder.bind(deadQueue).to(deadExchange).with(QUEUE));
    }
}
