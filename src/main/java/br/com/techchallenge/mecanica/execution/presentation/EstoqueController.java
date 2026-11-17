package br.com.techchallenge.mecanica.execution.presentation;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import br.com.techchallenge.mecanica.execution.application.EstoqueService;
import br.com.techchallenge.mecanica.execution.domain.estoque.*;
@io.swagger.v3.oas.annotations.tags.Tag(name = "EstoqueController")
@RestController
public class EstoqueController {
    public record CriarPeca(@NotBlank @Size(max=64) String sku, @NotBlank @Size(max=200) String descricao,
            @NotNull @Positive @Digits(integer=10, fraction=2) BigDecimal preco) {}
    public record Entrada(@NotNull UUID movimentoId, @Positive int quantidade) {}
    public record Item(@NotNull UUID pecaId, @Positive int quantidade) {}
    public record Reservar(@NotEmpty List<@NotNull @Valid Item> itens) {}
    private final EstoqueService service;
    public EstoqueController(EstoqueService service) { this.service = service; }
    @io.swagger.v3.oas.annotations.Operation(summary = "Cadastrar peca")
    @PostMapping("/pecas") public ResponseEntity<Peca> criar(@Valid @RequestBody CriarPeca request) {
        var p = service.criar(request.sku(), request.descricao(), request.preco());
        return ResponseEntity.created(URI.create("/pecas/" + p.id())).body(p);
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Consultar saldo da peca")
    @GetMapping("/pecas/{id}") public Peca buscar(@PathVariable UUID id) { return service.buscar(id); }
    @io.swagger.v3.oas.annotations.Operation(summary = "Adicionar saldo ao estoque")
    @PostMapping("/pecas/{id}/entradas") public Peca entrada(@PathVariable UUID id, @Valid @RequestBody Entrada request) {
        return service.entrada(request.movimentoId(), id, request.quantidade());
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Reservar pecas para o reparo")
    @PostMapping("/reparos/{id}/reserva") public Reserva reservar(@PathVariable UUID id, @Valid @RequestBody Reservar request) {
        return service.reservar(id, request.itens().stream().map(i -> new ItemReserva(i.pecaId(), i.quantidade())).toList());
    }
    @io.swagger.v3.oas.annotations.Operation(summary = "Consultar reserva e sua compensacao")
    @GetMapping("/reparos/{id}/reserva") public Reserva buscarReserva(@PathVariable UUID id) { return service.buscarReserva(id); }
}
