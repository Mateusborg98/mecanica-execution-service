package br.com.techchallenge.mecanica.dlq;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DlqRecoveryTest {
    private static final String SOURCE = "os.reparo-cancelado.v1.dlq";
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final byte[] BODY = ("""
        {"schemaVersion":1,"eventType":"ReparoCancelado","eventId":"%s","reparoId":"%s",
         "ordemId":"22222222-2222-2222-2222-222222222222","ocorridoEm":"2026-11-04T12:00:00Z"}
        """.formatted(ID, ID)).getBytes(StandardCharsets.UTF_8);
    @TempDir Path temp;
    private Audit audit;
    private Channel channel;

    @BeforeEach void prepare() throws Exception {
        audit = spy(new Audit(temp.resolve("audit.sqlite3")));
        channel = channel(BODY, Map.of());
    }
    @AfterEach void close() throws Exception { audit.close(); }
    private Channel channel(byte[] body, Map<String, Object> headers) throws Exception {
        var result = mock(Channel.class);
        when(result.isOpen()).thenReturn(true);
        when(result.waitForConfirms(anyLong())).thenReturn(true);
        if (body != null) when(result.basicGet(SOURCE, false)).thenReturn(new GetResponse(new Envelope(7, false, "", SOURCE),
            new AMQP.BasicProperties.Builder().contentType("application/json").messageId(ID).headers(headers).build(), body, 0));
        return result;
    }
    private DlqRecovery.Review review() throws Exception {
        return new DlqRecovery.Review(DlqRecovery.sha(BODY), "operador", "Causa corrigida conforme incidente TEST-1", "transient", true, null);
    }
    private Map<String, Object> replay(Channel c) throws Exception { return DlqRecovery.replay(c, audit, SOURCE, review()); }
    private String state() throws Exception { return audit.history().getFirst().get("state").toString(); }

    @Test void rejectsOverflowingSchemaVersion() {
        var invalid = new String(BODY, StandardCharsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> DlqRecovery.envelope(SOURCE, invalid));
    }
    @Test void inspectionPreservesMessageAndHidesBody() throws Exception {
        var result = DlqRecovery.inspect(channel, SOURCE);
        assertEquals(true, result.get("envelopeValid")); assertFalse(result.containsKey("body"));
        verify(channel).basicNack(7, false, true); verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertTrue(audit.history().isEmpty());
    }

    @Test void invalidEnvelopeIsInspectableButNotReplayed() throws Exception {
        channel = channel("{}".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals(false, DlqRecovery.inspect(channel, SOURCE).get("envelopeValid"));
        var r = new DlqRecovery.Review(DlqRecovery.sha("{}".getBytes(StandardCharsets.UTF_8)), "op", "Causa corrigida TEST", "transient", true, null);
        assertThrows(IllegalArgumentException.class, () -> DlqRecovery.replay(channel, audit, SOURCE, r));
        verify(channel, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test void directPersistentPublicationPreservesBodyAndConfirmsBeforeAck() throws Exception {
        channel = channel(BODY, Map.of("x-death", List.of(), "traceparent", "trace"));
        var result = replay(channel);
        var properties = org.mockito.ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        var order = inOrder(channel, audit);
        order.verify(audit).reserve(eq(SOURCE), anyString(), eq(ID), anyString(), anyString(), isNull(), isNull());
        order.verify(channel).basicPublish(eq(""), eq("os.reparo-cancelado.v1"), eq(true), properties.capture(), eq(BODY));
        order.verify(channel).waitForConfirms(10_000);
        order.verify(audit).update(anyString(), eq("CONFIRMED"), isNull());
        order.verify(channel).basicAck(7, false);
        assertEquals(2, properties.getValue().getDeliveryMode());
        assertEquals("trace", properties.getValue().getHeaders().get("traceparent"));
        assertFalse(properties.getValue().getHeaders().containsKey("x-death"));
        assertEquals(false, result.get("businessProcessingConfirmed"));
        assertEquals(List.of("PREPARED", "CONFIRMED", "ACK_SENT"), audit.rows("SELECT state FROM transitions ORDER BY seq").stream().map(r -> r.get("state")).toList());
    }

    @Test void changedHashMissingReviewConflictOrShortReasonNeverPublishes() throws Exception {
        var hash = DlqRecovery.sha(BODY);
        for (var r : List.of(new DlqRecovery.Review("changed", "op", "Causa corrigida TEST", "transient", true, null),
            new DlqRecovery.Review(hash, "op", "Causa corrigida TEST", "conflict", true, null),
            new DlqRecovery.Review(hash, "op", "Causa corrigida TEST", "transient", false, null),
            new DlqRecovery.Review(hash, "", "Causa corrigida TEST", "transient", true, null),
            new DlqRecovery.Review(hash, "op", "curto", "transient", true, null))) {
            assertThrows(IllegalArgumentException.class, () -> DlqRecovery.replay(channel, audit, SOURCE, r));
        }
        verify(channel, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test void uncertainPublicationPreservesOriginalAndBlocksRetry() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException());
        assertThrows(IllegalStateException.class, () -> replay(channel));
        assertEquals("UNKNOWN", state()); verify(channel).basicNack(7, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        var second = channel(BODY, Map.of());
        assertThrows(IllegalArgumentException.class, () -> replay(second));
        verify(second, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test void brokerNackNeverAcknowledgesOriginal() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> replay(channel));
        assertEquals("UNKNOWN", state()); verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void mandatoryReturnEvenWithConfirmNeverAcknowledgesOriginal() throws Exception {
        var listener = new ReturnListener[1];
        doAnswer(inv -> { listener[0] = inv.getArgument(0); return null; }).when(channel).addReturnListener(any(ReturnListener.class));
        doAnswer(inv -> { listener[0].handleReturn(312, "NO_ROUTE", "", "target", inv.getArgument(3), inv.getArgument(4)); return null; })
            .when(channel).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
        assertThrows(IllegalStateException.class, () -> replay(channel));
        assertEquals("UNKNOWN", state()); verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void confirmedPublicationResumesAfterAckFailureWithoutRepublishing() throws Exception {
        doThrow(new IOException()).when(channel).basicAck(7, false);
        assertThrows(IOException.class, () -> replay(channel)); assertEquals("CONFIRMED", state());
        var second = channel(BODY, Map.of()); replay(second);
        verify(second, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
        assertEquals("ACK_SENT", state());
    }

    @Test void returnedReplayRequiresNewReviewAndLinksAttempts() throws Exception {
        var first = replay(channel).get("attemptId").toString();
        var second = channel(BODY, Map.of("x-replay-attempt", first));
        assertThrows(IllegalArgumentException.class, () -> replay(second));
        var r = review();
        var next = DlqRecovery.replay(second, audit, SOURCE, new DlqRecovery.Review(r.sha256(), r.operator(), r.reason(), r.classification(), true, first));
        assertNotEquals(first, next.get("attemptId")); assertEquals(first, audit.history().getFirst().get("retry_of"));
    }

    @Test void secondConnectionCannotReservePreparedAttempt() throws Exception {
        audit.reserve(SOURCE, DlqRecovery.sha(BODY), ID, "op", "Causa corrigida TEST", null, null);
        try (var second = new Audit(temp.resolve("audit.sqlite3"))) {
            assertThrows(IllegalArgumentException.class, () -> second.reserve(SOURCE, DlqRecovery.sha(BODY), ID, "op", "Causa corrigida TEST", null, null));
        }
    }

    @Test void unknownQueueRejectedBeforeBrokerAccess() {
        assertThrows(IllegalArgumentException.class, () -> DlqRecovery.replay(channel, audit, "arbitrary.dlq", review()));
        verifyNoInteractions(channel);
    }

    @Test void emptyQueueCreatesNoAttempt() throws Exception {
        channel = channel(null, Map.of()); assertEquals(true, DlqRecovery.inspect(channel, SOURCE).get("empty"));
        assertThrows(IllegalArgumentException.class, () -> replay(channel)); assertTrue(audit.history().isEmpty());
    }

    @Test void auditFailureAfterPublishLeavesPreparedAndOriginalUnacked() throws Exception {
        doThrow(new SQLException("disk failure")).when(audit).update(anyString(), eq("CONFIRMED"), isNull());
        assertThrows(SQLException.class, () -> replay(channel)); assertEquals("PREPARED", state());
        verify(channel, never()).basicAck(anyLong(), anyBoolean()); verify(channel).basicNack(7, false, true);
    }

    @Test void persistedHistorySurvivesReopeningSameDatabase() throws Exception {
        var result = replay(channel);
        try (var reopened = new Audit(temp.resolve("audit.sqlite3"))) {
            assertEquals(result.get("attemptId"), reopened.history().getFirst().get("id"));
            assertEquals("ACK_SENT", reopened.history().getFirst().get("state"));
        }
    }

    @Test void cliRejectsUnknownOrMissingOptionsWithoutConnecting() {
        assertEquals(2, DlqCli.run(new String[]{"history"}));
        assertEquals(2, DlqCli.run(new String[]{"replay", "--local", "--queue"}));
        assertEquals(2, DlqCli.run(new String[]{"history", "--local", "--unknown"}));
        assertEquals(0, DlqCli.run(new String[]{"--help"}));
    }
}
