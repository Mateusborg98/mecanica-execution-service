package br.com.techchallenge.validacao;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit lab validation: real APIs via loopback port-forwards, no payment or SMTP. */
public final class ValidacaoAws {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String OS = "http://127.0.0.1:19081";
    private static final String BILLING = "http://127.0.0.1:19082";
    private static final String EXECUTION = "http://127.0.0.1:19083";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static String token;
    private static final String CORRELATION = UUID.randomUUID().toString();

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"--confirmar-laboratorio".equals(args[0])) {
            throw new IllegalArgumentException("Exige --confirmar-laboratorio; cria dados ficticios persistentes.");
        }
        token = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("JWT ausente na entrada padrao");
        for (String base : List.of(OS, BILLING, EXECUTION)) {
            require("UP".equals(call("GET", base + "/actuator/health/readiness", null).path("status").asText()), "Readiness");
        }
        var results = new ArrayList<Map<String, Object>>();
        results.add(scenario(true));
        results.add(scenario(false));
        var report = Map.of("validadoEm", Instant.now().toString(), "correlationId", CORRELATION,
                "cenarios", results, "pagamentoRealValidado", false, "smtpValidado", false);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/resultado-aws.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("PASS: dois cenarios distribuidos; pagamento e SMTP nao executados.");
        token = null;
    }

    private static Map<String, Object> scenario(boolean approve) throws Exception {
        String tag = UUID.randomUUID().toString();
        String cpf = cpf();
        int matricula = 100000 + RANDOM.nextInt(900000000);
        String placa = "TST" + RANDOM.nextInt(10) + (char)('A' + RANDOM.nextInt(26)) + String.format("%02d", RANDOM.nextInt(100));
        call("POST", OS + "/clientes", Map.of("nome", "Validacao AWS ficticia", "cpfCnpj", cpf, "contato", "11999999999", "email", "teste@example.invalid"));
        call("POST", OS + "/operadores", Map.of("nome", "Operador de validacao", "matricula", matricula, "email", tag+"@example.invalid", "contato", "11999999999", "cargo", "MECANICO"));
        call("POST", OS + "/veiculos", Map.of("placa", placa, "marca", "Teste", "modelo", "Laboratorio", "ano", 2024, "cpfCnpj", cpf));
        String order = call("POST", OS + "/ordens-servico", Map.of("placa", placa, "matriculaOperador", matricula)).path("id").asText();
        String repair = awaitField(OS + "/ordens-servico/"+order+"/reparo", "publicado", "true").path("reparoId").asText();
        awaitField(EXECUTION + "/reparos/"+repair, "status", "AGUARDANDO_APROVACAO");
        String piece = call("POST", EXECUTION + "/pecas", Map.of("sku", "AWS-"+tag, "descricao", "Peca ficticia", "preco", 25.50)).path("id").asText();
        call("POST", EXECUTION + "/pecas/"+piece+"/entradas", Map.of("movimentoId", UUID.randomUUID().toString(), "quantidade", 5));
        call("POST", EXECUTION + "/reparos/"+repair+"/diagnostico", Map.of("observacoes", "Validacao AWS sem pagamento", "itens", List.of(Map.of("tipo", "PECA", "referenciaId", piece, "quantidade", 2))));
        call("POST", EXECUTION + "/reparos/"+repair+"/reserva", Map.of("itens", List.of(Map.of("pecaId", piece, "quantidade", 2))));
        String budget = UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:"+repair).getBytes(StandardCharsets.UTF_8)).toString();
        awaitField(BILLING + "/orcamentos/"+budget, "status", "AGUARDANDO_APROVACAO");
        JsonNode reserved = call("GET", EXECUTION + "/pecas/"+piece, null);
        require(reserved.path("disponivel").asInt(-1) == 3, "Estoque apos reserva deve ser 3");
        call("POST", BILLING + "/orcamentos/"+budget+(approve ? "/aprovar" : "/recusar"), null);
        if (approve) {
            awaitField(EXECUTION+"/reparos/"+repair, "status", "NA_FILA");
            JsonNode projected = awaitField(OS+"/ordens-servico/acompanhamento/"+order, "status", "NA_FILA");
            require(projected.path("valorTotal").asDouble() == 51.0, "Valor da OS deve ser 51");
            boolean inQueue = false;
            for (JsonNode item : call("GET", EXECUTION+"/reparos/fila?limite=100", null)) if (repair.equals(item.path("id").asText())) inQueue = true;
            require(inQueue, "Reparo aprovado deve constar na fila");
            call("POST", EXECUTION+"/reparos/"+repair+"/iniciar", null);
            awaitField(OS+"/ordens-servico/acompanhamento/"+order, "status", "EM_EXECUCAO");
            call("POST", EXECUTION+"/reparos/"+repair+"/concluir", null);
            awaitField(OS+"/ordens-servico/acompanhamento/"+order, "status", "FINALIZADA");
            JsonNode charge = awaitField(BILLING+"/cobrancas/"+budget, "status", "PENDENTE");
            require(charge.path("valorTotal").asDouble() == 51.0, "Cobranca deve ser 51");
            require(order.equals(charge.path("ordemId").asText()), "Correlacao da cobranca");
            require(request("POST", OS+"/ordens-servico/"+order+"/entregar", null).statusCode() == 422, "Entrega sem pagamento deve retornar 422");
        } else {
            awaitField(EXECUTION+"/reparos/"+repair, "status", "CANCELADO");
            awaitField(OS+"/ordens-servico/acompanhamento/"+order, "status", "CANCELADA");
            awaitField(EXECUTION+"/reparos/"+repair+"/reserva", "status", "LIBERADA");
            require(call("GET", EXECUTION+"/pecas/"+piece, null).path("disponivel").asInt(-1) == 5, "Compensacao deve devolver estoque a 5");
            require(request("POST", BILLING+"/orcamentos/"+budget+"/recusar", null).statusCode() == 400, "Segunda decisao deve ser rejeitada com 400");
            require(call("GET", EXECUTION+"/pecas/"+piece, null).path("disponivel").asInt(-1) == 5, "Repeticao nao deve duplicar devolucao");
            require(request("GET", BILLING+"/cobrancas/"+budget, null).statusCode() == 404, "Recusa nao deve criar cobranca");
        }
        System.out.println("PASS: " + (approve ? "aprovacao, execucao e bloqueio da entrega" : "recusa e compensacao de estoque"));
        return Map.of("cenario", approve ? "aprovado-aguardando-pagamento" : "recusado-compensado", "ordemId", order, "reparoId", repair, "orcamentoId", budget, "pecaId", piece);
    }

    private static JsonNode awaitField(String url, String field, String expected) throws Exception {
        for (int attempt=0; attempt<60; attempt++) {
            var response = request("GET", url, null);
            if (response.statusCode()==200) {
                JsonNode node = JSON.readTree(response.body());
                if (expected.equals(node.path(field).asText())) return node;
            } else if (response.statusCode()!=404 && response.statusCode()!=503) {
                throw new IllegalStateException("HTTP "+response.statusCode()+" aguardando "+URI.create(url).getPath());
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Timeout aguardando "+field+"="+expected+" em "+URI.create(url).getPath());
    }
    private static JsonNode call(String method, String url, Object body) throws Exception {
        var response=request(method,url,body);
        require(response.statusCode()>=200 && response.statusCode()<300, "HTTP "+response.statusCode()+" "+method+" "+URI.create(url).getPath());
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }
    private static HttpResponse<String> request(String method, String url, Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer "+token).header("X-Correlation-ID",CORRELATION);
        if(body!=null) builder.header("Content-Type","application/json");
        builder.method(method, body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void require(boolean condition, String message) { if(!condition) throw new IllegalStateException(message); }
    private static String cpf() {
        StringBuilder value=new StringBuilder();
        for(int i=0;i<9;i++) value.append(RANDOM.nextInt(10));
        for(int size=9;size<11;size++) { int sum=0; for(int i=0;i<size;i++) sum+=(value.charAt(i)-'0')*(size+1-i); int digit=11-sum%11;value.append(digit>=10?0:digit); }
        return value.toString();
    }
}
