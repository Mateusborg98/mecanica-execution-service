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
@RestController
public class EstoqueController {
    public record CriarPeca(@NotBlank @Size(max=64) String sku, @NotBlank @Size(max=200) String descricao,
            @NotNull @Positive @Digits(integer=10, fraction=2) BigDecimal preco) {}
    public record Entrada(@NotNull UUID movimentoId, @Positive int quantidade) {}
    public record Item(@NotNull UUID pecaId, @Positive int quantidade) {}
    public record Reservar(@NotEmpty List<@NotNull @Valid Item> itens) {}
    private final EstoqueService service;
    public EstoqueController(EstoqueService service) { this.service = service; }
    @PostMapping("/pecas") public ResponseEntity<Peca> criar(@Valid @RequestBody CriarPeca request) {
        var p = service.criar(request.sku(), request.descricao(), request.preco());
        return ResponseEntity.created(URI.create("/pecas/" + p.id())).body(p);
    }
    @GetMapping("/pecas/{id}") public Peca buscar(@PathVariable UUID id) { return service.buscar(id); }
    @PostMapping("/pecas/{id}/entradas") public Peca entrada(@PathVariable UUID id, @Valid @RequestBody Entrada request) {
        return service.entrada(request.movimentoId(), id, request.quantidade());
    }
    @PostMapping("/reparos/{id}/reserva") public Reserva reservar(@PathVariable UUID id, @Valid @RequestBody Reservar request) {
        return service.reservar(id, request.itens().stream().map(i -> new ItemReserva(i.pecaId(), i.quantidade())).toList());
    }
    @GetMapping("/reparos/{id}/reserva") public Reserva buscarReserva(@PathVariable UUID id) { return service.buscarReserva(id); }
}
