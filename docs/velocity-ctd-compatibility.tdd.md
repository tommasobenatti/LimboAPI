# Verifica della compatibilità Velocity-CTD

Intervento locale del 24 ottobre 2026 sul commit LimboAPI
`bfef5799f21d73a80155035c3430f6654aa6b9f8`, per Velocity-CTD
`4.3.1-SNAPSHOT` build 410 e Java 25. Il confronto GitHub tra `build-410` e
HEAD `cf46d4000d252124c280f6361166df57f3d0f7f3` restituisce **zero file
diversi**: i commit intermedi introducono e poi annullano lo snapshot 26.4.

Questi jar sono compilati per CTD. La riflessione nel ponte di login gestisce
alcune differenze di firma, ma non rende identiche le ABI di tutti i fork:
in particolare i costruttori e i campi delle tablist di CTD sono diversi
da quelli di Velocity upstream. Non installare questo artefatto su un altro
proxy senza ricompilazione e verifica.

## Comportamento corretto

- Nessun collegamento diretto al metodo rimosso `canRegisterConnection` o
  al vecchio tipo del forwarding. La registrazione asincrona viene attesa
  sul ciclo eventi del giocatore senza bloccarlo.
- Il profilo definitivo di nLogin viene applicato prima della registrazione.
  Il replay conserva il `LoginInboundConnection` originale. Gli eventi
  `LoginEvent` e `PostLoginEvent` rispettano il ciclo di vita CTD, inclusi
  `markLoginEventFired` e `PlayerRegistry.finalizeLogin`, necessario a
  rilasciare il lock di identità e il relativo timeout di 30 secondi.
- Gli errori dei task, dei permessi e dei future terminano la connessione
  e liberano i riferimenti. La coda può finalizzare il login una sola volta.
  Le disconnessioni precedenti a `LoginEvent` puliscono esplicitamente
  le mappe, perché CTD non emette ancora `DisconnectEvent` in quel punto.
- Gli handler pre-login rimangono corretti dopo il reload. Le strutture
  condivise tra cicli eventi sono concorrenti; il bypass è legato al singolo
  evento e viene rimosso anche alla cancellazione.
- Le risposte ai resource pack raggiungono il gestore nativo Velocity in
  CONFIG e PLAY. I risultati di kick cambiati da altri plugin vengono
  rispettati; i kick gestiti da LimboReconnect non lasciano future sospesi.
- Le sessioni rilasciano contatori e task una volta sola. I keepalive PLAY
  attendono l'ack della transizione. La coda di conferma ha limiti per numero
  e byte e libera i pacchetti trattenuti anche su errore o disconnessione.
  Il budget dei pacchetti generici è per intervallo, senza accumulo infinito.
- L'aggiornamento di `config.yml`, comprese le sezioni messaggi incorporate,
  conserva commenti, traduzioni, chiavi aggiuntive e collezioni. Validazione
  rigorosa, backup privati e sostituzione atomica precedono la pubblicazione
  delle impostazioni. YAML invalido o cambiato durante il lavoro viene
  rifiutato. Non esiste un `messages.yml` separato da aggiungere.

## Test e prove RED/GREEN

| Test | Garanzia | Test superati |
| --- | --- | ---: |
| `LoginTasksQueueTest` | Registrazione asincrona, rifiuti, errori, chiusura tardiva, finalizzazione unica, permessi, lifecycle e lock CTD | 9 |
| `EventManagerHookTest` | Future terminali, replay concorrente, ordine pre-login, cache/reload, kick e precedenza degli altri plugin | 12 |
| `LoginConfirmHandlerTest` | Limiti, refcount, cancellazione e pulizia anticipata | 7 |
| `LimboSessionHandlerImplTest` | Resource pack CONFIG/PLAY, contatori, task e transizioni | 7 |
| `SafeConfigUpdaterTest` | Disco reale, idempotenza, personalizzazioni, backup, race, UTF-8 e mappe inline | 11 |
| `SettingsTest` | Schema reale, nuovi default, nLogin, chiavi aggiuntive e tipi invalidi | 5 |

Totale: **51 test**, nessun fallimento o test saltato. Il companion
LimboReconnect comprende altri **34 test**. La percentuale di copertura
dell'intero progetto non è stata misurata; non si tratta di una verifica
esaustiva di ogni versione del protocollo o di un profiler di memoria.

Prima delle correzioni sono state riprodotte regressioni reali: 6/7 test
eventi falliti, 7 regressioni negli handler, doppia finalizzazione del login,
future dei permessi ignorato e lifecycle CTD incompleto. Evidenze in
`../output/limbo-ctd-compat-20261024/`: `event-hook-red.xml`,
`event-hook-overrides-red.xml`, `login-tests-red-next.log` e
`login-identity-red.log`. Le prove aggiuntive del companion sono descritte
nella sua documentazione.

## Riproduzione della build

Scaricare il fatjar ufficiale CTD 410 e il dev-build LimboAPI del commit base,
verificando SHA-256 prima di usarli:

| Artefatto | SHA-256 |
| --- | --- |
| `velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar` | `7ec86cab7c4187e84ff0312ef2459bd70f96c5484552142b79b82d7c9d25a05d` |
| LimboAPI originale `bfef579`, per le sole risorse mapping | `5d541984b90ca7a88eb49ca5f8cc3a803f5ecdfa6d7ed30b605b72ff94156e40` |

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew build \
  -PvelocityCtdJar=/absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar \
  -PmappingsJar=/absolute/path/to/limboapi-upstream-bfef579.jar
```

L'opzione mapping riutilizza soltanto i JSON originali non modificati; le
classi vengono compilate dai sorgenti locali. È facoltativa: senza di essa
Gradle genera le risorse Minecraft. Le generazioni non partono più durante
la semplice configurazione Gradle. I jar di CTD e i test non vengono inclusi
nel plugin; SnakeYAML 2.5 viene incluso in uno spazio nomi privato.

Build completa, compilazione, Checkstyle, SpotBugs e licenze verificati.
Le release GitHub identificano il commit pubblicato e gli artefatti tramite tag e SHA-256.
Nessun aggiornamento del server di produzione.

## Integrazione locale

Il rapporto conclusivo e le ricevute del proxy di prova sono in
`../output/limbo-ctd-compat-20261024/`. Il test usa il vero CTD 410, i due
plugin modificati, ForcePack 1.3.75-SNAPSHOT e nLogin 2.0.18, con client
sintetico Minecraft 1.20.4/protocollo 765, pack e backend su loopback.
Comprende gli handler profilo reali di nLogin, il pack in CONFIG, accessi
ripetuti e due reload effettivi di ciascun plugin con configurazioni identiche.
La ricevuta conclusiva è `smoke/runtime-20261024T130926-nlogin/report.json`:
stessa connessione tenuta in CONFIG 36,00048 secondi prima di completare il
pack, poi altri due login riusciti dopo i reload; zero ERROR e nessun errore
binario. Solo il read-timeout del sandbox è 75 secondi per compensare il
backend sintetico senza heartbeat: il lock CTD di 30 secondi è invariato.

Non vengono utilizzati credenziali, licenze o database di produzione. Il
backend sintetico non fornisce un mondo giocabile: rendering del pack,
autenticazione premium/password e riavvio completo di un backend con
riconnessione dal PLAY restano prove da effettuare in staging con un client.
