package br.com.techchallenge.mecanica.execution.presentation;
import java.net.URI;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import br.com.techchallenge.mecanica.execution.application.DiagnosticoService;
import br.com.techchallenge.mecanica.execution.domain.diagnostico.*;
@io.swagger.v3.oas.annotations.tags.Tag(name = "DiagnosticoController")
@RestController
public class DiagnosticoController {
    public record CriarServico(@NotBlank @Size(max=64) String codigo, @NotBlank @Size(max=200) String descricao,
            @NotNull @Positive @Digits(integer=10, fraction=2) BigDecimal preco) {}
    public record ItemRequest(@NotNull Diagnostico.Tipo tipo, @NotNull UUID referenciaId, @Positive int quantidade) {}
    public record Request(@NotBlank @Size(max=2000) String observacoes, @NotEmpty List<@NotNull @Valid ItemRequest> itens) {}
    public record Response(UUID reparoId, UUID ordemId, String observacoes, Instant criadoEm, List<Diagnostico.Item> itens, BigDecimal valorTotal, @io.swagger.v3.oas.annotations.media.Schema(description = "ID do orcamento solicitado. A criacao no Billing e assincrona; a consulta pode retornar 404 ate o processamento do evento.") UUID orcamentoId) {
        static Response of(Diagnostico d) { return new Response(d.reparoId(), d.ordemId(), d.observacoes(), d.criadoEm(), d.itens(), d.total(), UUID.nameUUIDFromBytes(("mecanica:diagnostico:v1:" + d.reparoId()).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    }
    private final DiagnosticoService service;
    public DiagnosticoController(DiagnosticoService service) { this.service = service; }
    @io.swagger.v3.oas.annotations.Operation(summary = "Cadastrar servico do catalogo")
    @PostMapping("/servicos") public ResponseEntity<Servico> criar(@Valid @RequestBody CriarServico request) {
        var s = service.criarServico(request.codigo(), request.descricao(), request.preco());
        return ResponseEntity.created(URI.create("/servicos/" + s.id())).body(s);
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Consultar servico")
    @GetMapping("/servicos/{id}") public Servico buscarServico(@PathVariable UUID id) { return service.buscarServico(id); }
    @io.swagger.v3.oas.annotations.Operation(summary = "Registrar diagnostico e solicitar orcamento ao Billing")
    @PostMapping("/reparos/{id}/diagnostico") public Response registrar(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return Response.of(service.registrar(id, request.observacoes(), request.itens().stream()
                .map(i -> new Diagnostico.Selecao(i.tipo(), i.referenciaId(), i.quantidade())).toList()));
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Consultar diagnostico e valor total")
    @GetMapping("/reparos/{id}/diagnostico") public Response buscar(@PathVariable UUID id) { return Response.of(service.buscar(id)); }
}
