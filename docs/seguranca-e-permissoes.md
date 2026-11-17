# Autenticação e permissões — Fase 4

Os três serviços validam JWT RS256 usando `JWT_PUBLIC_KEY` (PEM X.509) e `JWT_ISSUER` (padrão `mecanica-auth`). O perfil vem da claim assinada `role`; não é aceito via parâmetro ou header separado. Chave pública ausente/inválida impede a inicialização: não há perfil que desligue a segurança.

## Matriz de acesso

| Serviço/operação | Acesso |
|---|---|
| OS: acompanhar `/ordens-servico/acompanhamento/{id}` | Público |
| OS: abrir `POST /ordens-servico` | CLIENTE, OPERADOR ou ADMINISTRADOR |
| OS: listar ordens, consultar histórico/solicitação e entregar | OPERADOR ou ADMINISTRADOR |
| OS: cadastrar, consultar e alterar clientes, veículos e operadores | OPERADOR ou ADMINISTRADOR |
| Billing: consultar orçamento/cobrança pelo identificador | Público |
| Billing: aprovar, recusar e gerar checkout | Público, conforme decisão do projeto |
| Billing: criar orçamento pela API e demais rotas administrativas | OPERADOR ou ADMINISTRADOR |
| Execution: catálogo, estoque, diagnóstico, reserva e operações/consultas de reparos | OPERADOR ou ADMINISTRADOR |
| Health e documentação da API | Público |
| Billing: `POST /webhooks/mercado-pago` | Sem JWT; assinatura HMAC validada pelo controller |

As exceções públicas do Billing são restritas ao método HTTP e caminho declarados. A criação de orçamento não foi tornada pública. Quem possui o link de orçamento continua podendo executar as operações públicas previstas; não há verificação de propriedade por JWT nessas rotas, por decisão explícita do responsável pelo projeto.

Na abertura da OS, `sub` continua representando o cliente do atendimento e o veículo precisa pertencer a ele. Não foi acrescentado um campo para escolher outro cliente. Um token de funcionário cujo `sub` não seja um cliente cadastrado não pode ser usado como atalho para abrir uma OS em nome de terceiros.

## Configuração local

`scripts/iniciar-integracao-os.ps1`, no Execution, fornece a mesma chave pública e emissor aos três serviços. A chave privada de desenvolvimento permanece protegida por DPAPI. Os roteiros `validar-*.ps1` foram adaptados para enviar JWT de operador nas chamadas administrativas do Execution.

O módulo BDD gera uma chave temporária própria, injeta somente a chave pública nos contêineres e abre a OS com JWT de cliente e utiliza JWT de operador nas operações administrativas de OS/Execution. As operações públicas do Billing permanecem sem Bearer nesse cenário. Não há acesso aos segredos reais do Mercado Pago.

Para iniciar aplicações separadamente, configure `JWT_PUBLIC_KEY` e `JWT_ISSUER`. O launcher `iniciar-billing-checkout.ps1` pode carregar a chave pública local já criada pelo roteiro integrado; não acessa a chave privada. A versão standalone do Compose Execution recebe essas variáveis do ambiente.

No PowerShell, para uma chamada administrativa local, é possível carregar o helper do Execution:

```powershell
. ./scripts/autenticacao-local.ps1
$headers = Get-LocalOperatorHeaders
Invoke-RestMethod -Uri 'http://localhost:18083/reparos/ID-DO-REPARO' -Headers $headers
```

O helper destina-se exclusivamente ao desenvolvimento e não imprime o token. Em produção, o emissor confiável deve autenticar o operador e emitir sua role; os serviços somente validam o JWT. A integração do emissor e a configuração das chaves no ambiente final ainda precisam ser verificadas no deploy.

## Respostas e testes

Billing/Execution retornam 401 para ausência ou falha de autenticação e 403 para perfil sem permissão. A OS preserva seu contrato anterior de 403 também para ausência de JWT. Isso não altera a validação de regras de negócio, como o HTTP 422 para tentar entregar sem pagamento ou abrir OS com veículo de outro cliente.

Os testes verificam cliente/operador/administrador, token expirado, emissor incorreto, outra chave, ausência de autenticação, rotas públicas e ausência de identidade residual entre requisições. Os testes de controller/webhook e os cenários BDD complementam a matriz de autorização.

[Evidência de testes e cobertura](validacao-permissoes-2026-11-05.md).
