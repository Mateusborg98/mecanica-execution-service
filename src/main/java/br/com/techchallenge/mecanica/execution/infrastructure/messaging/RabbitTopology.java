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
    public static final String DECISAO_ROUTE = "orcamento.decidido.v1";
    public static final String DECISAO_QUEUE = "execution.orcamento.v1";
    @Bean public Declarables decisaoTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var dlx = new DirectExchange("mecanica.dlx", true, false);
        var queue = QueueBuilder.durable(DECISAO_QUEUE).deadLetterExchange("mecanica.dlx")
                .deadLetterRoutingKey(DECISAO_QUEUE).build();
        var dlq = QueueBuilder.durable(DECISAO_QUEUE + ".dlq").build();
        return new Declarables(exchange, dlx, queue, dlq,
                BindingBuilder.bind(queue).to(exchange).with(DECISAO_ROUTE),
                BindingBuilder.bind(dlq).to(dlx).with(DECISAO_QUEUE));
    }
    public static final String CONCLUSAO_ROUTE = "reparo.concluido.v1";
    public static final String CONCLUSAO_QUEUE = "billing.reparo-concluido.v1";
    @Bean public Declarables conclusaoTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var dlx = new DirectExchange("mecanica.dlx", true, false);
        var queue = QueueBuilder.durable(CONCLUSAO_QUEUE).deadLetterExchange("mecanica.dlx")
                .deadLetterRoutingKey(CONCLUSAO_QUEUE).build();
        var dlq = QueueBuilder.durable(CONCLUSAO_QUEUE + ".dlq").build();
        return new Declarables(exchange, dlx, queue, dlq,
                BindingBuilder.bind(queue).to(exchange).with(CONCLUSAO_ROUTE),
                BindingBuilder.bind(dlq).to(dlx).with(CONCLUSAO_QUEUE));
    }
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
