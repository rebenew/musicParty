🎵 Music Sync Server — README (resumen del estado actual)
Descripción

Pequeña plataforma de sincronización musical en tiempo real (estilo Spotify Jam) orientada a una extensión Chrome que controla YouTube Music como reproductor. Hay dos roles principales:

Host: crea la sala, controla (por defecto) la reproducción, puede permitir o no a invitados editar la cola.

Guests: se unen por roomId provisto por el host; pueden controlar reproducción y/o editar la playlist si el host lo permite.

El backend es una API REST + WebSocket que centraliza la «verdad» en un objeto RoomSession por sala; la lógica de control, broadcast y health checks está consolidada en RoomSessionManager.
Contenido

Estado actual (alto nivel)

Código reestructurado para consolidar servicios en un RoomSessionManager central (gestión de salas, playback, broadcast, health checks).

SyncWebSocketHandler maneja conexiones WS y enruta mensajes al manager.

RoomController expone endpoints REST para operaciones administrativas (crear/eliminar sala, estado, playlist, settings).

Implementadas:

creación/ eliminación de salas

unirse a sala (host/guest) vía WebSocket (auth)

control de reproducción: play, pause, next, previous, seek

operaciones en playlist: add, remove, move, clear

health checks periódicos + reconexión del host (reconnection window)

broadcast de events (playback, playlist, system)

envío de full room state a quien se autentique correctamente

Tests manuales con Postman (mensajes WS) ya ejecutados y corridos. Ejemplos funcionaron y devolvieron ack / full_state / errores útiles.

Arquitectura (resumen)
Frontend (Chrome Ext) <--> SyncWebSocketHandler <--> RoomSessionManager <--> RoomSession (por sala)
\
 -> Health checks / scheduler
-> Broadcasting (safeSend)

Principales clases / componentes

RoomSession — estado de sala (playlist, nowPlaying, clients map, host session, scheduling para fin de track, listener de eventos).

RoomSessionManager — fuente única de verdad; APIs públicas para play/pause/seek/addTrack/removeTrack/...; health checks, broadcasts, administración de sesiones WS.

SyncWebSocketHandler — adapta mensajes SyncMsg desde WS y llama a RoomSessionManager; mantiene sessions locales por socket.

SyncMsg — DTO unificado para WS: { type, subType, roomId, senderId, correlationId, data }.

TrackEntry — record para cada pista (actualmente: trackId, title, addedBy, addedAt) — nota importante abajo.

RoomController — REST endpoints para administración y debugging.

API REST (rutas de ejemplo)

Asumiendo server:8080 (confirma el puerto en application.properties)

POST /rooms/create — crear sala (body { "senderId": "host1" }) → { "roomId": "xxx" }

DELETE /rooms/{roomId} — eliminar sala (body { "senderId": "host1" })

GET /rooms/{roomId} — obtener metadata de la sala

GET /rooms/{roomId}/playback?senderId=host1 — estado de reproducción actual

GET /rooms/{roomId}/playlist — playlist (lista de tracks)

POST /rooms/{roomId}/settings — actualizar settings (host-only)

GET /rooms/stats — stats del servicio

GET /rooms/debug/rooms — debug (logs)

Mensajes WebSocket (SyncMsg) — ejemplos para Postman o cliente WS

1. Autenticación (host)

{
"type": "auth",
"roomId": "abc12345",
"senderId": "host1",
"correlationId": "auth-host",
"data": { "isHost": true }
}

2. Autenticación (guest)
   {
   "type": "auth",
   "roomId": "abc12345",
   "senderId": "guest1",
   "correlationId": "auth-guest",
   "data": { "isHost": false }
   }

Respuestas esperadas:

ack success + full_state (cuando join ok)

ack failure con reason (ej. room_not_active, room_not_found, join_failed)

3. Playback — play (host)

{
"type": "playback",
"subType": "play",
"roomId": "abc12345",
"senderId": "host1",
"correlationId": "play1",
"data": { "trackIndex": 0, "positionMs": 0 }
}

4. Playback — pause

{
"type": "playback",
"subType": "pause",
"roomId": "abc12345",
"senderId": "host1",
"correlationId": "pause1"
}

5. Playlist — add

{
"type": "playlist",
"subType": "add",
"roomId": "abc12345",
"senderId": "guest1",
"correlationId": "add1",
"data": { "trackId": "song001", "title": "Song 1" }
}

Cada mensaje WS produce un ack con success: true/false y un reason. Además hay broadcasts para cambios (playback, playlist_update, system, full_state, trackChanged/trackChanged etc).

Health & reconexión

Health checks periódicos (HEALTH_CHECK_INTERVAL_MS, HOST_TIMEOUT_MS, RECONNECTION_WINDOW_MS) detectan host timeouts y eventualmente eliminan la sala si no hay reconexión.

Al desconectar el host se notifica a guests (host_disconnected) y se lanza temporizador de expiración (ventana de reconexión).

RoomSessionManager publica eventos (RoomHealthSystem.Event) y reacciona (hostDisconnected, roomExpired, hostReconnected, healthCheckPass).

Problemas detectados / decisiones abiertas (IMPORTANTE)

He reunido los principales puntos que requieren atención / ya detectados:

<!-- TrackEntry durationMs: IMPLEMENTADO -->

RoomSessionManager no se registra como bean

Error que viste: Parameter 0 ... SyncWebSocketHandler required a bean of type 'RoomSessionManager' that could not be found.

Causa frecuente:

la clase está abstract, o no está anotada con @Service/@Component, o la clase está en un paquete que Spring no escanea.

Solución:

Asegúrate que exista una implementación concreta y anotada, p.ej. @Service public class RoomSessionManagerImpl extends RoomSessionManager { ... } o convertir RoomSessionManager a no abstract y anotarla con @Service.

O define un @Bean en @Configuration que retorne la instancia.

Uso de setters inexistentes

Llamadas como roomSession.setHostDisconnected(...) requieren setter en RoomSession. Asegúrate de exponer setters (o usar métodos explícitos markHostDisconnected()).

Mejor práctica: encapsular estado con métodos semánticos (markHostDisconnected(), attachHostSession, detachHostSession) en lugar de setters directos.

Concurrent scheduling & life-cycle

RoomSession usa scheduler = Executors.newSingleThreadScheduledExecutor() por sala — crea un thread por sala si muchas salas llegan a existir. Considera utilizar un scheduler compartido (inyectable) para reducir hilos.

Asegúrate de cancelar trackEndTask y shutdown del scheduler al eliminar la sala (evitar leaks).

Serialización de SyncMsg y TrackEntry

Al enviar objetos como payload en broadcast, preferible serializar DTOs simples (Map/POJO) para controlar fields y evitar problemas con record o transient fields.

Mensajes de error y razones

Mantén una lista centralizada de reason strings (constantes) para consistencia (ej. ROOM_NOT_FOUND, JOIN_FAILED, PLAY_FAILED).

Recomendaciones / TODOs (priorizadas)

Agregar durationMs a TrackEntry y adaptar llamadas que crean TrackEntry (host debe enviar duración si la tiene).

Asegurar bean de RoomSessionManager:

convertir a implementación concreta o crear RoomSessionManagerImpl y anotar @Service.

Proveer setters/semánticos en RoomSession:

markHostDisconnected(), markHostReconnected() o getters/setters necesarios.

Mover scheduler por sala a un executor compartido (inyectar ScheduledExecutorService) para evitar muchos hilos.

Agregar shutdown/cancel en eliminación de sala:

cancelar trackEndTask, liberar scheduler si es por sala, o limpiar referencias.

Agregar tests unitarios para:

reproducción/skip/seek logic en RoomSession

health check flows (host disconnect → expiration)

add/remove/move track

Documentar protocolos WS (versión del mensaje, tipos válidos, subType list) en README y/o en un OpenAPI/JSON Schema para SyncMsg.

Buffer/latency handling: considerar envío de ping/pong y timestamps para compesación de latencia, y/o notificación de buffer si cliente detecta underflow.

Ejemplos de Postman / pruebas rápidas (WS)

Autenticar host → recibir ack + full_state

Autenticar guest (host conectado) → recibir ack + full_state

play host con trackIndex y positionMs → comprobar broadcast playback

add track por guest (si permitido) → comprobar playlist_update

Simular desconexión host (cerrar WS) → comprobar host_disconnected y que RoomSessionManager programe expiración

Simular finalización automática de track (en tests, usa durationMs corto) → comprobar trackChanged broadcast y nowPlayingIndex incrementado

Configuración & arranque (sugerido)

Variables / properties:

server.port=8080

app.host.timeout.ms=600000

app.reconnection.window.ms=300000

Comandos:

mvn clean package

mvn spring-boot:run (o ejecutar jar empaquetado)

Nota: confirmar @ComponentScan / packages para que Spring descubra SyncWebSocketHandler, RoomSessionManager y beans.

Roadmap & próximos pasos

Mejorar pruebas automáticas (JUnit + Mockito + WebSocket test harness).

Implementar persistencia opcional (guardar playlists o sesiones en Redis para escalabilidad).

Políticas de sharding y usar scheduler centralizado para track-end.

Implementar métricas (Prometheus) y tracing (OpenTelemetry) para latencias de sincronía.

UI/UX: extensión Chrome que envíe durationMs y confirme buffer/latency.

## Guía de Integración Frontend (Chrome Extension / Web Client)

### 1. Conexión y Autenticación

- **Endpoint:** `ws://localhost:8080/ws/music-sync`
- **Flujo:**
  1.  Conectar WebSocket.
  2.  Enviar mensaje `auth` inmediatamente.
  3.  Esperar `ack` con `success: true`.
  4.  Si es exitoso, recibirás un `full_state` con el estado actual de la sala.

### 2. Heartbeats (Vital)

El servidor desconecta clientes inactivos tras 10 minutos (`CLIENT_TIMEOUT_MS`).

- **Regla:** Enviar un mensaje `heartbeat` cada 30-60 segundos.
- **Payload:** `{"type": "heartbeat", "roomId": "...", "senderId": "..."}`

### 3. Sincronización de Reproducción

El servidor envía `positionMs` (tiempo transcurrido del track).

- **Cálculo:** `Tiempo Actual Reproductor = positionMs + (Tiempo Actual Local - timestamp del mensaje)`
- **Latencia:** Considera el RTT (Round Trip Time) si necesitas precisión milimétrica, pero para música, el ajuste básico suele bastar.

### 4. Manejo de Errores

Todos los comandos responden con un mensaje `ack`.

- Si `success: false`, revisa el campo `reason`.
- **Razones comunes:**
  - `room_not_found`: La sala expiró o no existe.
  - `host_disconnected`: El host se cayó (pausar reproducción o mostrar aviso).

### 5. Reconexión

- **Si el socket se cierra:** Reintentar conexión con _exponential backoff_.
- **Si recibes `host_disconnected`:**
  - **Guests:** Mostrar "Esperando al host...". No cerrar la sala localmente inmediatamente (hay una ventana de reconexión de 5 min).
  - **Host:** Al reconectar, enviar `auth` con `isHost: true` para recuperar el control.

Contribuciones

Mantener estilo de commits claro (feat/fix/refactor).

Abrir PRs pequeños por funcionalidad (playback, health, playlist).

Añadir tests unitarios para cada PR.
