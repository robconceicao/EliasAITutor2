# Perfil autenticado na homologação

Eliminar chamadas anônimas ao perfil antigo. Usar a mesma identidade Supabase
verificada no login operacional e tabelas elias_profiles/elias_flash_offers no
core-test. RLS auth.uid=user_id no SELECT/INSERT/UPDATE; sem acesso anon.
Dados de perfil são progresso/gamificação, nunca concessão de licença comercial.
Não migrar dados legados sem dono verificado. Capturar dono antes/depois do refresh
e da resposta; oferecer falha clara preservando cópia local. Ofertas também por dono.
Validar RLS com duas identidades em transação revertida e compilar Kotlin.
Parâmetros de voz e protocolo de áudio permanecem os mesmos.

Progresso offline: cada save incrementa revisão local na mesma edição DataStore.
Revisão remota e revisão local confirmada são persistidas; pull nunca sobrescreve
revisão local não confirmada. Upload usa CAS por revisão remota e UUID durável por
dispositivo/revisão; replay de resposta perdida confirma a mesma mutação. Conflito
entre dispositivos mantém ambas as cópias e mostra aviso, sem merge destrutivo.
Inicialização usa a primeira leitura DataStore real, não UserProfile() do StateFlow.
Sincronização de oferta que falha não muda a oferta já salva/exibida.

O snapshot exato em voo é persistido antes do POST e reapresentado após resposta
perdida mesmo que existam alterações locais posteriores. Só após seu ACK a próxima
revisão recebe outra mutação. Testar serialização real de user_id.
