package br.com.techchallenge.mecanica.bdd;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/** External provider double for the isolated BDD network. Never used by the real payment environment. */
public final class PaymentSimulator {
    private static final JsonMapper JSON=new JsonMapper();
    private static final Map<String,Map<String,Object>> ORDERS=new ConcurrentHashMap<>();
    private static final Map<String,String> KEYS=new ConcurrentHashMap<>();
    private PaymentSimulator() { }
    public static void main(String[] args) throws Exception {
        var server=HttpServer.create(new InetSocketAddress(8090),64);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/",PaymentSimulator::handle);server.start();
    }
    private static void handle(HttpExchange exchange) throws java.io.IOException {
        try(exchange) {
            var path=exchange.getRequestURI().getPath();
            if(path.equals("/health")){reply(exchange,200,Map.of("status","UP"));return;}
            if(!"Bearer bdd-token-not-real".equals(exchange.getRequestHeaders().getFirst("Authorization"))){reply(exchange,401,Map.of());return;}
            if(path.equals("/v1/orders") && exchange.getRequestMethod().equals("POST")) {
                var request=JSON.readTree(exchange.getRequestBody().readNBytes(65536));
                var reference=request.path("external_reference").asString();var total=request.path("total_amount").asString();
                if(!reference.equals(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"))){reply(exchange,400,Map.of());return;}
                var id=KEYS.computeIfAbsent(reference,key->"BDD"+UUID.randomUUID().toString().replace("-",""));
                ORDERS.computeIfAbsent(id,key->{var order=new ConcurrentHashMap<String,Object>();
                    order.put("id",key);order.put("type","online");order.put("external_reference",reference);order.put("total_amount",total);
                    order.put("currency","BRL");order.put("total_paid_amount","0.00");order.put("status","created");order.put("status_detail","pending");
                    order.put("last_updated_date",Instant.now().toString());order.put("checkout_url","https://www.mercadopago.com.br/checkout/v1/redirect?bdd="+key);return order;});
                reply(exchange,201,ORDERS.get(id));return;
            }
            if(path.startsWith("/simulacao/") && exchange.getRequestMethod().equals("POST")) {
                var parts=path.split("/");var order=parts.length==4?ORDERS.get(parts[2]):null;
                if(order==null){reply(exchange,404,Map.of());return;}
                switch(parts[3]) {
                    case "aprovar" -> {order.put("status","processed");order.put("status_detail","accredited");order.put("total_paid_amount",order.get("total_amount"));}
                    case "recusar" -> {order.put("status","failed");order.put("status_detail","rejected");}
                    case "divergir" -> {order.put("status","processed");order.put("status_detail","accredited");order.put("total_paid_amount","0.01");}
                    default -> {reply(exchange,400,Map.of());return;}
                }
                order.put("last_updated_date",Instant.now().toString());reply(exchange,200,Map.of());return;
            }
            if(path.startsWith("/v1/orders/") && exchange.getRequestMethod().equals("GET")) {
                var order=ORDERS.get(path.substring("/v1/orders/".length()));reply(exchange,order==null?404:200,order==null?Map.of():order);return;
            }
            reply(exchange,404,Map.of());
        } catch(RuntimeException ex) { System.err.println("Invalid simulator request: "+ex.getClass().getSimpleName()); }
    }
    private static void reply(HttpExchange exchange,int status,Object value) throws java.io.IOException {
        var bytes=JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}
