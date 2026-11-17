package br.com.techchallenge.mecanica.dlq;

import com.rabbitmq.client.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DlqRecovery {
    private record Contract(String type, int version) { }
    private static final Map<String, Contract> CONTRACTS = Map.ofEntries(
        Map.entry("execution.reparo-solicitado.v1", new Contract("ReparoSolicitado", 1)),
        Map.entry("billing.diagnostico.v1", new Contract("DiagnosticoRegistrado", 1)),
        Map.entry("os.diagnostico.v1", new Contract("DiagnosticoRegistrado", 1)),
        Map.entry("execution.orcamento.v1", new Contract("OrcamentoDecidido", 1)),
        Map.entry("os.orcamento.v2", new Contract("OrcamentoDecidido", 2)),
        Map.entry("billing.reparo-concluido.v1", new Contract("ReparoConcluido", 1)),
        Map.entry("os.reparo-concluido.v1", new Contract("ReparoConcluido", 1)),
        Map.entry("os.reparo-iniciado.v1", new Contract("ReparoIniciado", 1)),
        Map.entry("os.pagamento.v1", new Contract("PagamentoConfirmado", 1)),
        Map.entry("billing.reparo-cancelado.v1", new Contract("ReparoCancelado", 1)),
        Map.entry("os.reparo-cancelado.v1", new Contract("ReparoCancelado", 1)));
    private static final JsonMapper JSON = new JsonMapper();
    private DlqRecovery() { }

    public static String destination(String source) {
        if (source == null || !source.endsWith(".dlq") || !CONTRACTS.containsKey(source.substring(0, source.length() - 4)))
            throw new IllegalArgumentException("Fila fora da lista de contratos permitidos.");
        return source.substring(0, source.length() - 4);
    }

    static JsonNode envelope(String source, byte[] body) {
        var contract = CONTRACTS.get(destination(source));
        try {
            if (body.length > 1024 * 1024) throw new IllegalArgumentException();
            var event = JSON.readTree(body);
            if (!event.isObject() || !event.path("schemaVersion").isIntegralNumber() || !event.path("schemaVersion").canConvertToInt()
                || event.path("schemaVersion").intValue() != contract.version()
                || !contract.type().equals(event.path("eventType").asString())) throw new IllegalArgumentException();
            for (var key : List.of("eventId", "reparoId", "ordemId")) {
                var value = required(event, key);
                if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            }
            OffsetDateTime.parse(required(event, "ocorridoEm"));
            var expected = required(event, "reparoId");
            switch (contract.type()) {
                case "ReparoSolicitado" -> expected = required(event, "ordemId");
                case "OrcamentoDecidido", "PagamentoConfirmado" -> {
                    expected = UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:" + expected).getBytes(StandardCharsets.UTF_8)).toString();
                    var key = contract.type().equals("OrcamentoDecidido") ? "orcamentoId" : "cobrancaId";
                    if (!expected.equals(required(event, key))) throw new IllegalArgumentException();
                }
                default -> { }
            }
            if (!expected.equals(required(event, "eventId"))) throw new IllegalArgumentException();
            if (contract.type().equals("OrcamentoDecidido") && !Set.of("APROVADO", "RECUSADO").contains(required(event, "decisao")))
                throw new IllegalArgumentException();
            return event;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Envelope inválido ou incompatível; não reprocessar automaticamente.");
        }
    }

    private static String required(JsonNode event, String key) {
        if (!event.path(key).isString()) throw new IllegalArgumentException();
        return event.path(key).stringValue();
    }

    static String sha(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    public static Map<String, Object> inspect(Channel channel, String source) throws Exception {
        destination(source);
        var message = channel.basicGet(source, false);
        if (message == null) return Map.of("queue", source, "empty", true);
        try {
            var result = new LinkedHashMap<String, Object>();
            result.put("queue", source); result.put("sha256", sha(message.getBody())); result.put("bytes", message.getBody().length);
            try {
                var event = envelope(source, message.getBody());
                for (var key : List.of("eventId", "eventType", "reparoId", "ordemId")) result.put(key, required(event, key));
                result.put("schemaVersion", event.path("schemaVersion").intValue()); result.put("envelopeValid", true);
            } catch (IllegalArgumentException ex) { result.put("envelopeValid", false); }
            result.put("previousAttempt", previousHeader(message.getProps()));
            return result;
        } finally { channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true); }
    }

    static String previousHeader(AMQP.BasicProperties props) {
        var value = props.getHeaders() == null ? null : props.getHeaders().get("x-replay-attempt");
        return value == null ? null : value.toString();
    }

    public record Review(String sha256, String operator, String reason, String classification, boolean causeFixed, String retryOf) { }

    public static Map<String, Object> replay(Channel channel, Audit audit, String source, Review review) throws Exception {
        var target = destination(source);
        if (!"transient".equals(review.classification()) || !review.causeFixed())
            throw new IllegalArgumentException("Somente falha transitória analisada e corrigida pode ser reenviada.");
        channel.queueDeclarePassive(target);
        channel.confirmSelect();
        var message = channel.basicGet(source, false);
        if (message == null) throw new IllegalArgumentException("DLQ vazia.");
        boolean acknowledged = false;
        try {
            var sha = sha(message.getBody());
            if (!sha.equals(review.sha256())) throw new IllegalArgumentException("A mensagem mudou; inspecione novamente.");
            var event = envelope(source, message.getBody());
            var reservation = audit.reserve(source, sha, required(event, "eventId"), review.operator(), review.reason(), previousHeader(message.getProps()), review.retryOf());
            if (reservation.publish()) {
                var headers = new HashMap<String, Object>();
                if (message.getProps().getHeaders() != null) headers.putAll(message.getProps().getHeaders());
                headers.keySet().removeIf(k -> k.startsWith("x-death") || k.startsWith("x-first-death") || k.startsWith("x-last-death") || k.startsWith("x-replay-"));
                headers.put("x-replay-attempt", reservation.id()); headers.put("x-replay-source", source);
                var original = message.getProps();
                var props = new AMQP.BasicProperties.Builder().contentType(original.getContentType() == null ? "application/json" : original.getContentType())
                    .contentEncoding(original.getContentEncoding()).deliveryMode(2).headers(headers)
                    .messageId(original.getMessageId() == null ? required(event, "eventId") : original.getMessageId())
                    .correlationId(original.getCorrelationId()).type(original.getType()).appId(original.getAppId()).build();
                var returned = new AtomicBoolean();
                ReturnListener listener = (code, text, exchange, key, properties, body) -> returned.set(true);
                channel.addReturnListener(listener);
                try {
                    channel.basicPublish("", target, true, props, message.getBody());
                    if (!channel.waitForConfirms(10_000) || returned.get()) throw new java.io.IOException("Publicação rejeitada ou não roteada.");
                } catch (Exception ex) {
                    audit.update(reservation.id(), "UNKNOWN", ex.getClass().getSimpleName());
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new IllegalStateException("Publicação não confirmada; conferir auditoria antes de repetir.", ex);
                } finally { channel.removeReturnListener(listener); }
                audit.update(reservation.id(), "CONFIRMED", null);
            }
            channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            acknowledged = true;
            audit.update(reservation.id(), "ACK_SENT", null);
            return Map.of("attemptId", reservation.id(), "state", "ACK_SENT", "target", target,
                "eventId", required(event, "eventId"), "sha256", sha, "businessProcessingConfirmed", false);
        } finally {
            if (!acknowledged && channel.isOpen()) channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
        }
    }
}
