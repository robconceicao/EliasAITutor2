# Métricas do método SDD

Preencher ao fim de cada ciclo — dois minutos. O que importa é a **tendência**, não o número solto.

| Ciclo | Data | M1 % lacuna de spec | M2 retrabalho | M3 min até desvio | M4 bloqueantes vazados | M5 ciclos/semana | M6 min de revisão |
|---|---|---|---|---|---|---|---|
| 1 | 2026-08-26 | **100%** (4/4) | 0% | sem desvio | 0 | 1 | não cronometrado |
| 2 | | | | | | | |
| 3 | | | | | | | |
| 4 | | | | | | | |
| 5 | | | | | | | |

Leitura combinada:

- **M1 caindo + M3 subindo** → a spec está aprendendo. É o objetivo.
- **M1 caindo + M4 subindo** → suas specs estão ficando permissivas, não mais claras.
- **M6 abaixo de 10 min** → você parou de ler o diff. É o começo do fim do método.

Portão para a Fase 6 (sessões longas): **M4 = 0 em três ciclos seguidos.**

## Ciclo 1 — leitura

**M1 = 100%.** Quatro achados, os quatro `LACUNA_DE_SPEC`: superfície de exports não declarada (F1,
do verificador), detecção de chave duplicada (G1), `/health/tts` ambíguo entre *pronto* e *sem chave*
(G2), `cooldownMs <= 0` não documentado (G3). Nenhum bug de código. Isso é o esperado no primeiro
ciclo e é bom sinal: a spec estava incompleta, não o código errado. O que importa é M1 **cair** nos
próximos ciclos — se continuar em 100%, é porque a realimentação não está acontecendo de verdade.

**M3 sem desvio.** O escritor não tocou nenhum arquivo fora dos três autorizados. Fatia pequena e
não-escopo nomeando arquivos concretos funcionaram.

**M4 = 0, com ressalva.** Nada escapou para `main` — mas A4, A5 e A8 dependem de device e de código
que ainda não existe. O portão da Fase 6 (M4 = 0 por três ciclos) só começa a contar quando houver
teste em device de verdade.

**Ressalvas de honestidade deste ciclo:** M6 não foi cronometrado (o ciclo rodou por agente, não em
sessão sua), e o verificador não era de outra família de modelo — nenhum CLI desse tipo estava
disponível. Ambas as coisas inflam a confiança nos números acima.
