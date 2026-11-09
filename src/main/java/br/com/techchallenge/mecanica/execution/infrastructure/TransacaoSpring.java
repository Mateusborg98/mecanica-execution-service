package br.com.techchallenge.mecanica.execution.infrastructure;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import br.com.techchallenge.mecanica.execution.application.Transacao;
public class TransacaoSpring implements Transacao {
    private final TransactionTemplate template;
    public TransacaoSpring(PlatformTransactionManager manager) { template = new TransactionTemplate(manager); }
    public <T> T executar(Supplier<T> operacao) { return template.execute(status -> operacao.get()); }
}
