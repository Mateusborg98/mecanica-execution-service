package br.com.techchallenge.mecanica.execution.application;
import java.util.UUID;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.Diagnostico;
public interface PublicadorDiagnostico {
    void publicar(UUID eventId, Diagnostico diagnostico);
}
