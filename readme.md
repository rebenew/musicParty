🎧 MusicParty – Plataforma de Sincronización Musical en Tiempo Real

Sincroniza la música entre múltiples usuarios con WebSockets, cola compartida y control colaborativo.

 -- Descripción General -- 

MusicParty es una aplicación FullStack diseñada para crear salas de reproducción compartida donde múltiples usuarios pueden:

 * Reproducir una misma canción al mismo tiempo (sincronización real).
 * Agregar canciones a una cola compartida.
 * Seguir el estado del host (tiempo, canción actual, cola, etc.).
 * Usar una única interfaz de usuario web (frontend) para controlarlo todo.

El backend está construido con Spring Boot + WebSocket, y el frontend es una aplicación web moderna y optimizada.
 
 -- Arquitectura General --

 ┌─────────────────────┐
│      Usuario         │
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
│    Otros Usuarios    │
│   (Frontend Web)     │
└─────────────────────┘

✔ El host es la fuente de verdad.
✔ Los invitados ven la cola y la reproducción sincronizada.
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
* Los invitados agregan canciones sin necesidad de request manual

✔ Frontend (UI web)

* Interfaz de usuario unificada y optimizada para hosts e invitados.
 * Visualización mejorada y más responsiva.
 * Flujo de usuario simplificado.

🧑‍🤝‍🧑 Los invitados agregan canciones sin request manual

Sistema implementado:

🔓 Los invitados tienen permisos inmediatos para agregar canciones.
El Frontend permite agregar directamente.
El servidor actualiza la cola global.
El host recibe la actualización automáticamente.

🧭 Flujo de Sincronización
1. El host abre una sala

RoomSession creada → Broadcast inicial.

2. Los usuarios envían cambios

* Canción actual
* Tiempo
* Estado de reproducción

3. Los invitados se conectan

Reciben:

* Cola completa
* Playback actual
* Estado del host

4. Los invitados agregan una canción

Inmediatamente:

* Se agrega a la shadow playlist del servidor.
* Se transmite a todos.
* El host actualiza su cola (manual o futura automatización DOM).
