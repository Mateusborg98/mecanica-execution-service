package br.com.techchallenge.mecanica.bdd;

import io.cucumber.java.BeforeAll;
import io.cucumber.java.AfterAll;
import io.cucumber.java.pt.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

public class FluxoSteps {
    private static final JsonMapper JSON=new JsonMapper();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String OS="http://127.0.0.1:28081", BILLING="http://127.0.0.1:28082", EXECUTION="http://127.0.0.1:28083";
    private static Path root, env;
    private static String jwt;
    private static String clientJwt;
    private static boolean started;
    private String ordem, reparo, orcamento, peca, order;

    @BeforeAll public static void iniciar() throws Exception {
        root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("compose.bdd.yaml"))) root=root.getParent();
        if(root==null) throw new IllegalStateException("Execute no repositório mecanica-execution-service.");
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        var target=root.resolve("tests/fluxo-distribuido/target");Files.createDirectories(target);
        env=target.resolve("bdd-public.env");
        Files.writeString(env,"OS_INTEGRATION_PUBLIC_KEY='-----BEGIN PUBLIC KEY-----\\n"+Base64.getEncoder().encodeToString(key.getPublic().getEncoded())+"\\n-----END PUBLIC KEY-----'\n");
        long now=Instant.now().getEpochSecond();
        var claims=Map.of("iss","mecanica-bdd-local","sub","10000000-0000-0000-0000-000000000001","role","OPERADOR","iat",now,"exp",now+3600);
        var unsigned=b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))+"."+b64(JSON.writeValueAsBytes(claims));
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());signature.update(unsigned.getBytes(StandardCharsets.UTF_8));jwt=unsigned+"."+b64(signature.sign());
        var clientClaims=new HashMap<String,Object>(claims);
        clientClaims.put("role","CLIENTE");
        var clientUnsigned=b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))+"."+b64(JSON.writeValueAsBytes(clientClaims));
        signature.initSign(key.getPrivate());signature.update(clientUnsigned.getBytes(StandardCharsets.UTF_8));
        clientJwt=clientUnsigned+"."+b64(signature.sign());
        started=true;
        if(Boolean.getBoolean("bdd.skipBuild")) compose("up","-d","--wait","--wait-timeout","180");
        else compose("up","-d","--build","--wait","--wait-timeout","180");
        for(var service:List.of(OS,BILLING,EXECUTION)) await(service+"/actuator/health",n->"UP".equals(n.path("status").asString()));
    }
    @AfterAll public static void encerrar() throws Exception { if(started) {try {compose("logs","--no-color","--tail","100");} finally {compose("down");}} }
    private static void compose(String... args) throws Exception {
        var command=new ArrayList<>(List.of("docker","compose","--env-file",env.toString(),"-f","compose.bdd.yaml"));command.addAll(List.of(args));
        var log=root.resolve("tests/fluxo-distribuido/target/compose.log").toFile();
        var process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();
        if(!process.waitFor(15,TimeUnit.MINUTES)){process.destroyForcibly();throw new AssertionError("Timeout Docker Compose BDD");}
        assertEquals(0,process.exitValue(),"Docker Compose BDD falhou");
    }
    private static String b64(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static HttpResponse<String> request(String method,String url,Object body,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if((url.startsWith(OS) || url.startsWith(EXECUTION)) && !headers.containsKey("Authorization")) builder.header("Authorization","Bearer "+jwt);
        headers.forEach(builder::header);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode post(String url,Object body) throws Exception {
        var response=request("POST",url,body,Map.of());assertTrue(response.statusCode()>=200 && response.statusCode()<300,()->url+" HTTP "+response.statusCode()+" "+response.body());
        return response.body().isBlank()?JSON.createObjectNode():JSON.readTree(response.body());
    }
    private static JsonNode await(String url,Predicate<JsonNode> condition) throws Exception {
        var deadline=Instant.now().plusSeconds(url.endsWith("/actuator/health")?300:100);String last="";
        while(Instant.now().isBefore(deadline)) {
            try { var response=request("GET",url,null,Map.of());last="HTTP "+response.statusCode()+" "+response.body();
                if(response.statusCode()==200){var node=JSON.readTree(response.body());if(condition.test(node))return node;}
                else if(response.statusCode()!=404 && response.statusCode()!=503) throw new AssertionError(url+": "+last);
            } catch(java.io.IOException e){last=e.getClass().getSimpleName();}
            Thread.sleep(500);
        }
        throw new AssertionError("Timeout "+url+": "+last);
    }
    private static Predicate<JsonNode> status(String value){return n->value.equals(n.path("status").asString());}

    @Dado("uma ordem aberta pela API da OS e diagnosticada pela execução") public void abrir() throws Exception {
        assertEquals(403,request("GET",OS+"/ordens-servico",null,Map.of("Authorization","Bearer "+clientJwt)).statusCode());
        assertEquals(403,request("POST",EXECUTION+"/pecas",Map.of(),Map.of("Authorization","Bearer "+clientJwt)).statusCode());
        assertEquals(401,HTTP.send(HttpRequest.newBuilder(URI.create(EXECUTION+"/pecas"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var abertura=request("POST",OS+"/ordens-servico",Map.of("placa","ABC1D23","matriculaOperador",1),
            Map.of("Authorization","Bearer "+clientJwt));
        assertEquals(201,abertura.statusCode(),abertura.body());
        ordem=JSON.readTree(abertura.body()).path("id").asString();
        reparo=await(OS+"/ordens-servico/"+ordem+"/reparo",n->n.path("publicado").asBoolean()).path("reparoId").asString();
        await(EXECUTION+"/reparos/"+reparo,status("AGUARDANDO_APROVACAO"));
        orcamento=UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:"+reparo).getBytes(StandardCharsets.UTF_8)).toString();
        peca=post(EXECUTION+"/pecas",Map.of("sku","BDD-"+UUID.randomUUID(),"descricao","Peça BDD","preco",25.50)).path("id").asString();
        post(EXECUTION+"/pecas/"+peca+"/entradas",Map.of("movimentoId",UUID.randomUUID(),"quantidade",5));
        post(EXECUTION+"/reparos/"+reparo+"/diagnostico",Map.of("observacoes","BDD distribuído","itens",List.of(Map.of("tipo","PECA","referenciaId",peca,"quantidade",2))));
        post(EXECUTION+"/reparos/"+reparo+"/reserva",Map.of("itens",List.of(Map.of("pecaId",peca,"quantidade",2))));
        await(BILLING+"/orcamentos/"+orcamento,status("AGUARDANDO_APROVACAO"));
        await(EXECUTION+"/pecas/"+peca,n->n.path("disponivel").asInt()==3);
    }
    @Quando("o cliente aprova o orçamento e o reparo é concluído") public void concluir() throws Exception {
        post(BILLING+"/orcamentos/"+orcamento+"/aprovar",null);
        await(EXECUTION+"/reparos/"+reparo,status("NA_FILA"));
        post(EXECUTION+"/reparos/"+reparo+"/iniciar",null);post(EXECUTION+"/reparos/"+reparo+"/concluir",null);
        await(OS+"/ordens-servico/acompanhamento/"+ordem,status("FINALIZADA"));
        await(BILLING+"/cobrancas/"+orcamento,status("PENDENTE"));
    }
    @Então("a entrega fica bloqueada enquanto a cobrança está pendente") public void bloquear() throws Exception {
        assertEquals(422,request("POST",OS+"/ordens-servico/"+ordem+"/entregar",null,Map.of()).statusCode());
    }
    @Quando("o provedor confirma o pagamento por webhook assinado") public void pagar() throws Exception {
        var checkout=post(BILLING+"/cobrancas/"+orcamento+"/checkout",null);order=checkout.path("orderId").asString();assertFalse(order.isBlank());
        var url=BILLING+"/webhooks/mercado-pago?data.id="+order+"&type=order";
        assertEquals(401,request("POST",url,Map.of(),Map.of("x-request-id","bdd-invalid","x-signature","ts=1,v1="+"0".repeat(64))).statusCode());
        assertEquals(200,request("POST","http://127.0.0.1:28090/simulacao/"+order+"/aprovar",null,Map.of("Authorization","Bearer bdd-token-not-real")).statusCode());
        webhook();webhook();
    }
    private void webhook() throws Exception {
        String requestId=UUID.randomUUID().toString(),ts=Long.toString(Instant.now().getEpochSecond());
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("bdd-webhook-secret-not-real".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var digest=HexFormat.of().formatHex(mac.doFinal(("id:"+order+";request-id:"+requestId+";ts:"+ts+";").getBytes(StandardCharsets.UTF_8)));
        assertEquals(200,request("POST",BILLING+"/webhooks/mercado-pago?data.id="+order+"&type=order",Map.of(),Map.of("x-request-id",requestId,"x-signature","ts="+ts+",v1="+digest)).statusCode());
    }
    @Então("a cobrança fica paga e a OS pode ser entregue") public void entregar() throws Exception {
        await(BILLING+"/cobrancas/"+orcamento,status("PAGA"));
        await(OS+"/ordens-servico/"+ordem+"/historico",n->{for(var e:n)if("PAGAMENTO".equals(e.path("tipo").asString()))return true;return false;});
        post(OS+"/ordens-servico/"+ordem+"/entregar",null);await(OS+"/ordens-servico/acompanhamento/"+ordem,status("ENTREGUE"));
    }
    @E("o histórico registra o fluxo sem duplicar o pagamento") public void historico() throws Exception {
        var history=await(OS+"/ordens-servico/"+ordem+"/historico",n->n.size()>=7);var types=new ArrayList<String>();long previous=0;
        for(var event:history){types.add(event.path("tipo").asString());long sequence=event.path("sequencia").asLong();assertTrue(sequence>previous);previous=sequence;}
        assertTrue(types.containsAll(List.of("ABERTURA","DIAGNOSTICO","DECISAO","INICIO","CONCLUSAO","PAGAMENTO","ENTREGA")),types.toString());
        assertEquals(1,Collections.frequency(types,"PAGAMENTO"));
        assertEquals(403,HTTP.send(HttpRequest.newBuilder(URI.create(OS+"/ordens-servico/"+ordem+"/historico")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Quando("o cliente recusa o orçamento") public void recusar() throws Exception {post(BILLING+"/orcamentos/"+orcamento+"/recusar",null);}
    @Então("o reparo e a OS são cancelados e o estoque é restituído") public void compensar() throws Exception {
        await(EXECUTION+"/reparos/"+reparo,status("CANCELADO"));await(OS+"/ordens-servico/acompanhamento/"+ordem,status("CANCELADA"));
        await(EXECUTION+"/pecas/"+peca,n->n.path("disponivel").asInt()==5);
        assertEquals(404,request("GET",BILLING+"/cobrancas/"+orcamento,null,Map.of()).statusCode());
        assertEquals(409,request("POST",EXECUTION+"/reparos/"+reparo+"/iniciar",null,Map.of()).statusCode());
    }
}
