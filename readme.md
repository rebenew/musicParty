🎧 MusicParty – Plataforma de Sincronización Musical en Tiempo Real

Sincroniza YouTube Music entre múltiples usuarios con WebSockets, cola compartida y control colaborativo.

 -- Descripción General -- 

MusicParty es una aplicación FullStack diseñada para crear salas de reproducción compartida donde múltiples usuarios pueden:

 * Reproducir una misma canción al mismo tiempo (sincronización real).

 * Agregar canciones a una cola compartida.

 * Seguir el estado del host (tiempo, canción actual, cola, etc.).

 * Usar una extensión de navegador o una UI web (frontend) para controlarlo.

El backend está construido con Spring Boot + WebSocket, y el frontend consiste en:

 * Una extensión de navegador que detecta cambios en YouTube Music.

 * Una UI web para invitados/host (en proceso de optimización).
 
 -- Arquitectura General --

 ┌─────────────────────┐
│   Usuario Invitado   │
│   (Frontend Web)     │
└──────────┬───────────┘
           │ WebSocket
           ▼
┌─────────────────────┐
│    Spring Boot WS   │
│  SyncWebSocketHandler│
│  RoomSessionManager  │
└──────────┬───────────┘
           │ Broadcast
           ▼
┌─────────────────────┐
│       Host          │
│ Extensión + YTMusic │
└─────────────────────┘

✔ El host es la fuente de verdad.
✔ Los guests ven la cola y reproducción sincronizada.
✔ El servidor actúa como coordinador que refleja el estado real.

-- Estado actual del proyecto (2025)--

✔ Backend funcional con:

* WebSocket estable (/ws/music-sync)

* Manejo de salas dinámicas (RoomSession)

* RoomSessionManager con:

 * timers

 * sincronización de playback

 * broadcast general a todos los clients

* Shadow playlist del host en el servidor

* Guests agregan canciones sin necesidad de request manual

✔ Frontend (UI web)

* Existe y funciona, pero se está optimizando:

 * Mejor visualización

 * Mejor flujo para invitados

 * Más responsivo

 ✔ Extensión Chrome / Firefox

 Detecta automáticamente:

 * Canción actual

 * Porcentaje / tiempo de reproducción

 * Cambios en la cola

 * Siguiente canción

 * Pausas / skips

Y envía la información al Backend vía WS.

Mirror Mode (En Desarrollo)

El objetivo final:

- El host se vuelve la fuente absoluta de verdad.

La cola real de YouTube Music del host es:

✔ Leída
✔ Sincronizada
✔ Convertida en una shadow playlist
✔ Enviada como broadcast a todos los invitados

🔄 Cuando el host cambie su cola:

* El backend recibirá un sync_queue

* Actualizará la shadow interna

* Enviará un broadcast completo con el nuevo estado

Este modo permitirá sincronización EXACTA con YT Music.

🧑‍🤝‍🧑 Guests agregan canciones sin request manual

Nuevo sistema implementado:

🔓 Guests tienen permisos inmediatos para agregar canciones:

Ya NO se requiere enviar add_track_request al host.

La extensión/Frontend permite agregar directo.

El servidor actualiza la cola global.

El host recibe el update automáticamente.

Este sistema se integrará totalmente en el Mirror Mode final.

🧭 Flujo de Sincronización
1. Host abre sala

RoomSession creada → Broadcast inicial.

2. Extensión envía cambios

* Canción actual

* Tiempo

* Cola real (mirror mode)

* Estado de reproducción

3. Guests se conectan

Reciben:

* Cola completa

* Playback actual

* Estado del host

4. Guests agregan canción

Inmediatamente:

* Se agrega a la shadow playlist del server

* Broadcast a todos

* Host actualiza su cola (manual o futura automatización DOM)