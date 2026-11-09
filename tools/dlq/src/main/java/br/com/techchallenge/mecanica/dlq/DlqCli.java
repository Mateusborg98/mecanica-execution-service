package br.com.techchallenge.mecanica.dlq;

import com.rabbitmq.client.ConnectionFactory;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;

public final class DlqCli {
    private DlqCli() { }
    public static void main(String[] args) { System.exit(run(args)); }

    static int run(String[] args) {
        var json = new JsonMapper();
        try {
            if (args.length == 1 && args[0].equals("--help")) {
                System.out.println("""
                    java -jar mecanica-dlq.jar inspect|replay|history --local [opções]
                    --queue FILA.dlq --sha256 HASH --operator NOME --reason MOTIVO
                    --classification transient --cause-fixed [--retry-of ID] [--audit ARQUIVO]
                    Apenas o broker local 127.0.0.1:5673. Inspecione e corrija a causa antes de reenviar.
                    """);
                return 0;
            }
            if (args.length == 0 || !Set.of("inspect", "replay", "history").contains(args[0]))
                throw new IllegalArgumentException("Informe inspect, replay ou history; consulte --help.");
            var flags = Set.of("--local", "--cause-fixed");
            var values = Set.of("--queue", "--sha256", "--operator", "--reason", "--classification", "--retry-of", "--audit");
            var options = new HashMap<String, String>();
            for (int i = 1; i < args.length; i++) {
                var key = args[i];
                if (options.containsKey(key)) throw new IllegalArgumentException("Opção repetida.");
                if (flags.contains(key)) options.put(key, "true");
                else if (values.contains(key) && i + 1 < args.length && !args[i + 1].startsWith("--")) options.put(key, args[++i]);
                else throw new IllegalArgumentException("Opção inválida ou sem valor; consulte --help.");
            }
            if (!options.containsKey("--local")) throw new IllegalArgumentException("Informe --local.");
            var source = options.get("--queue");
            if (!args[0].equals("history")) DlqRecovery.destination(source);
            var base = System.getenv().getOrDefault("LOCALAPPDATA", Path.of(System.getProperty("user.home"), ".local", "share").toString());
            var path = Path.of(options.getOrDefault("--audit", Path.of(base, "MecanicaDevTools", "dlq-audit.sqlite3").toString()));
            try (var audit = new Audit(path)) {
                Object result;
                if (args[0].equals("history")) result = audit.history();
                else {
                    var factory = new ConnectionFactory();
                    factory.setHost("127.0.0.1"); factory.setPort(5673); factory.setVirtualHost("/");
                    factory.setUsername("mecanica_local"); factory.setPassword("mecanica_local_password");
                    factory.setConnectionTimeout(10_000); factory.setHandshakeTimeout(10_000);
                    factory.setRequestedHeartbeat(30); factory.setChannelRpcTimeout(15_000);
                    factory.setAutomaticRecoveryEnabled(false);
                    try (var connection = factory.newConnection("mecanica-dlq-local"); var channel = connection.createChannel()) {
                        result = args[0].equals("inspect") ? DlqRecovery.inspect(channel, source)
                            : DlqRecovery.replay(channel, audit, source, new DlqRecovery.Review(options.get("--sha256"), options.get("--operator"),
                                options.get("--reason"), options.get("--classification"), options.containsKey("--cause-fixed"), options.get("--retry-of")));
                    }
                }
                System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            }
            return 0;
        } catch (Exception ex) {
            var message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Falha operacional; confira a auditoria.";
            System.err.println(json.writeValueAsString(Map.of("error", message, "type", ex.getClass().getSimpleName())));
            return ex instanceof IllegalArgumentException ? 2 : 3;
        }
    }
}
