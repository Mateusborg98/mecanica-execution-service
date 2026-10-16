package br.com.techchallenge.mecanica.execution.presentation;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import br.com.techchallenge.mecanica.execution.application.ReparoService;
import br.com.techchallenge.mecanica.execution.domain.reparo.*;
@RestController
@RequestMapping("/reparos")
public class ReparoController {
    public record CriarRequest(@NotNull UUID ordemId) {}
    public record Response(UUID id, UUID ordemId, UUID orcamentoId, StatusReparo status,
            Instant criadoEm, Instant iniciadoEm, Instant concluidoEm) {
        static Response of(Reparo r) { return new Response(r.getId(), r.getOrdemId(), r.getOrcamentoId(),
                r.getStatus(), r.getCriadoEm(), r.getIniciadoEm(), r.getConcluidoEm()); }
    }
    private final ReparoService service;
    public ReparoController(ReparoService service) { this.service = service; }
    @PostMapping public ResponseEntity<Response> criar(@Valid @RequestBody CriarRequest request) {
        var r = service.criar(request.ordemId());
        return ResponseEntity.created(URI.create("/reparos/" + r.getId())).body(Response.of(r));
    }
    @GetMapping("/{id}") public Response buscar(@PathVariable UUID id) { return Response.of(service.buscar(id)); }
    @PostMapping("/{id}/iniciar") public Response iniciar(@PathVariable UUID id) { return Response.of(service.iniciar(id)); }
    @PostMapping("/{id}/concluir") public Response concluir(@PathVariable UUID id) { return Response.of(service.concluir(id)); }
    @PostMapping("/{id}/cancelar") public Response cancelar(@PathVariable UUID id) { return Response.of(service.cancelar(id)); }
}
