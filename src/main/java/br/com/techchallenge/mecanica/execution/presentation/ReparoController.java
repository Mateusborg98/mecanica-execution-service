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
@io.swagger.v3.oas.annotations.tags.Tag(name = "ReparoController")
@RestController
@RequestMapping("/reparos")
public class ReparoController {
    public record CriarRequest(@NotNull UUID ordemId) {}
    public record CancelarSolicitacaoRequest(@NotNull UUID reparoId, @NotNull UUID ordemId) {}
    public record Response(UUID id, UUID ordemId, UUID orcamentoId, StatusReparo status,
            Instant criadoEm, Instant iniciadoEm, Instant concluidoEm) {
        static Response of(Reparo r) { return new Response(r.getId(), r.getOrdemId(), r.getOrcamentoId(),
                r.getStatus(), r.getCriadoEm(), r.getIniciadoEm(), r.getConcluidoEm()); }
    }
    private final ReparoService service;
    public ReparoController(ReparoService service) { this.service = service; }
    @io.swagger.v3.oas.annotations.Operation(summary = "Criar reparo (normalmente solicitado pela abertura da OS)")
    @PostMapping public ResponseEntity<Response> criar(@Valid @RequestBody CriarRequest request) {
        var r = service.criar(request.ordemId());
        return ResponseEntity.created(URI.create("/reparos/" + r.getId())).body(Response.of(r));
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Consultar fila de reparos")
    @GetMapping("/fila") public java.util.List<Response> fila(@RequestParam(defaultValue="50") int limite) {
        return service.fila(limite).stream().map(Response::of).toList();
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Cancelar solicitacao de reparo antes ou depois de sua criacao")
    @PostMapping("/cancelamentos") public Response cancelarSolicitacao(@Valid @RequestBody CancelarSolicitacaoRequest request) {
        return Response.of(service.cancelarSolicitacao(request.reparoId(), request.ordemId()));
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Consultar status, ordemId e orcamentoId do reparo")
    @GetMapping("/{id}") public Response buscar(@PathVariable UUID id) { return Response.of(service.buscar(id)); }
    @io.swagger.v3.oas.annotations.Operation(summary = "Iniciar reparo apos aprovacao do orcamento")
    @PostMapping("/{id}/iniciar") public Response iniciar(@PathVariable UUID id) { return Response.of(service.iniciar(id)); }
    @io.swagger.v3.oas.annotations.Operation(summary = "Concluir reparo e solicitar cobranca por evento")
    @PostMapping("/{id}/concluir") public Response concluir(@PathVariable UUID id) { return Response.of(service.concluir(id)); }
    @io.swagger.v3.oas.annotations.Operation(summary = "Cancelar reparo e compensar reserva antes do inicio")
    @PostMapping("/{id}/cancelar") public Response cancelar(@PathVariable UUID id) { return Response.of(service.cancelar(id)); }
}
