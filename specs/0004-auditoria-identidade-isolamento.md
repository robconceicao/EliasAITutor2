# ELI-01/02/03 — identidade e isolamento
Somente código/testes de homologação; sem deploy, novo APK ou billing real.
- HTTP /program, /sessions, /progress e Socket.IO exigem token validado pelo Supabase Auth configurado para as contas Tadeu. Identidade vem exclusivamente da resposta do Auth. Payload userId é ignorado.
- A sessão operacional continua obrigatória quando APP_ENV=homologation e TEST_LICENSE_BYPASS=true. Cliente preserva login e renovação; somente consulta comercial é dispensada.
- Contexto AsyncLocalStorage por requisição/evento transporta identidade até todas as funções do programa (incluindo feedback assíncrono). Sem contexto, acesso a estado pessoal falha.
- Estado tem key=userId; sessões têm user_id. Consultas, updates e cache são filtrados pelo dono. Currículo e banco de perguntas continuam compartilhados.
- Snapshot em arquivo é separado por hash do userId. O arquivo e documentos antigos key=default/sem dono ficam intactos e sem acesso automático. Migração somente com comprovação do dono.
- Socket valida sessão em cada evento, sem aceitar trocar de autor; erros emitidos como erro_backend. Eventos de áudio e formato permanecem.
- Voz passa por checkTadeuQuota antes da operação; minutos são medidos pelos frames Opus emitidos pelo servidor (20ms), nunca por durationMs do cliente. Consumo usa chave por sessão/minuto e fila serial, sem arredondar frações ou repetir um minuto confirmado. Falha interrompe a conexão. Em homologação explícita helpers não acessam billing.
- Cliente HTTP e handshake enviam token atualizado. As credenciais não entram em logs.
Aceite: chamadas anônimas/forjadas rejeitadas; A/B concorrentes não compartilham estado/sessões/feedback; reboot preserva arquivos separados; testes do caminho Mongo e licenciamento continuam passando. Sem teste real de rede, usar verificadores e medição injetados.
Limites: não migrar histórico legado automaticamente; não declarar homologação em aparelho; liberar backend e cliente juntos somente após nova autorização de build.

## Cache Android (correção adicional da revisão independente)
Programa e perfil/gamificação usam DataStore separado por hash SHA-256 da identidade autenticada. ViewModels são criados por chave dessa identidade após o gate de sessão. Arquivos legados globais ficam intactos, sem importação automática. O cache local não é restaurado automaticamente em um programa remoto vazio. Interceptor do programa verifica a conta capturada antes e depois de renovar o token; tarefa antiga não usa sessão da conta seguinte. Registro de DataStores é sincronizado para evitar duas instâncias do mesmo arquivo.
Validação local: seis testes de identidade/isolamento do backend e os onze scripts unitários existentes passaram; compileHomologationKotlin passou. Sem instrumentação em aparelho ou integração com contas Supabase reais; o cliente PostgREST de perfil legado exige validação de RLS/Auth antes de dados reais.

Revisão final: ViewModelStore vinculado ao ciclo da conta autenticada; troca/saída encerra coletores antigos. Socket descarta histórico/restauração da conta anterior, remove listeners anteriores e rejeita conclusão de refresh de conexão superada. Reconexão da mesma conta preserva a sessão enquanto não houver disconnect explícito.
