package com.rebenew.musicParty.syncserver.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rebenew.musicParty.syncserver.model.*;
import com.rebenew.musicParty.syncserver.service.RoomHealthSystem;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

// Gestiona todas las operaciones de salas usando RoomSession como fuente única de verdad.

@Service
public class RoomSessionManager implements RoomSession.RoomEventListener {
    private static final Logger logger = LoggerFactory.getLogger(RoomSessionManager.class);

    // ============================
    // ESTADO PRINCIPAL
    // ============================
    private final ConcurrentHashMap<String, RoomSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastActivityMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> healthStateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledHealthCheck> scheduledHealthChecks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService healthScheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    // ============================
    // CONFIG
    // ============================
    public static final long HOST_TIMEOUT_MS = 600_000L; // 10 min sin actividad del host
    private static final long RECONNECTION_WINDOW_MS = 300_000L; // 5 min para reconectarse
    private static final long HEALTH_CHECK_INTERVAL_MS = 10_000L; // 10 segundos
    private static final long CLEANUP_INTERVAL_MS = 30_000L; // 30 segundos

    public RoomSessionManager(ApplicationEventPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        logger.info("RoomSessionManager inicializado");
    }

    // ====================
    // CREACIÓN DE SALAS
    // ====================
    public RoomSession createRoom(String roomId, String hostUserId) {
        validateRoomId(roomId);
        validateUserId(hostUserId);

        if (sessions.containsKey(roomId)) {
            logger.warn("Intento de crear sala existente: {}", roomId);
            throw new IllegalStateException("La sala ya existe: " + roomId);
        }

        RoomSession session = new RoomSession(roomId, hostUserId);
        session.setEventListener((RoomSession.RoomEventListener) this); // conecta callbacks desde RoomSession
        sessions.put(roomId, session);
        lastActivityMap.put(roomId, session.getLastActivityAt());
        healthStateMap.put(roomId, true);

        logger.info("🎵 Sala creada: {} por host: {}", roomId, hostUserId);
        return session;
    }
    // ====================
    // GESTIÓN BÁSICA
    // ====================

    // Obtiene una sesión de sala
    public RoomSession getSession(String roomId) {
        return sessions.get(roomId);
    }

    // Verifica si una sala existe
    public boolean roomExists(String roomId) {
        return sessions.containsKey(roomId);
    }

    // Obtiene copia defensiva de todas las sesiones
    public Map<String, RoomSession> getAllSessions() {
        return new HashMap<>(sessions);
    }

    // Elimina una sala (solo permitido al host)
    public boolean deleteRoom(String roomId, String callerUserId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de eliminar sala inexistente: {}", roomId);
            return false;
        }
        if (!session.isHost(callerUserId) && !"health_system".equals(callerUserId)) {
            logger.warn("Intento no autorizado de eliminar sala {} por: {}", roomId, callerUserId);
            return false;
        }

        closeAllConnections(session);
        sessions.remove(roomId);
        lastActivityMap.remove(roomId);
        cancelHealthCheck(roomId);
        healthStateMap.remove(roomId);

        logger.info("🗑️ Sala eliminada: {} por {}", roomId, callerUserId);
        return true;
    }

    // ====================
    // USUARIOS / CONEXIONES
    // ====================

    // Añade un usuario a una sala vía WebSocket
    public boolean addUserToRoom(String roomId, WebSocketSession wsSession, String userId, boolean isHost) {
        RoomSession roomSession = sessions.get(roomId);
        if (roomSession == null) {
            logger.warn("Intento de unirse a sala inexistente: {}", roomId);
            return false;
        }

        if (isHost) {
            boolean wasDisconnected = roomSession.isHostDisconnected(HOST_TIMEOUT_MS);

            roomSession.addClient(userId, wsSession);
            roomSession.updateHostActivity();
            lastActivityMap.put(roomId, roomSession.getLastActivityAt());

            roomSession.setHostDisconnected(false);
            roomSession.setState(RoomState.ACTIVE);

            if (wasDisconnected) {
                logger.info("👑 Host {} reconectado a sala: {}", userId, roomId);
                broadcastSystemMessage(roomSession, "host_reconnected", Map.of("hostId", userId), userId);
            } else {
                logger.info("👑 Host {} (re)conectado a sala: {}", userId, roomId);
                broadcastSystemMessage(roomSession, "host_connected", Map.of("hostId", userId), userId);
            }
        } else {
            if (!canGuestJoinRoom(roomId)) {
                logger.warn("Intento de unirse a sala {} no disponible para guest {}", roomId, userId);
                return false;
            }
            roomSession.addClient(userId, wsSession);
            lastActivityMap.put(roomId, roomSession.getLastActivityAt());
            logger.info("👤 Invitado {} unido a sala: {}", userId, roomId);
            broadcastSystemMessage(roomSession, "user_joined", Map.of("userId", userId), userId);
        }

        scheduleHealthCheck(roomId, isHost);
        return true;
    }

    // Remueve una sesión de todas las salas (por desconexión) - CORREGIDO
    public void removeUserFromAllRooms(WebSocketSession session) {
        boolean removed = false;
        for (RoomSession roomSession : sessions.values()) {
            String userId = roomSession.getUserIdForSession(session);
            if (userId != null) {
                roomSession.removeClient(session);
                removed = true;
                lastActivityMap.put(roomSession.getRoomId(), roomSession.getLastActivityAt());

                if (roomSession.isHost(userId)) {
                    logger.warn("👑 Host {} desconectado de sala {}", userId, roomSession.getRoomId());
                    roomSession.setHostDisconnected(true);
                    roomSession.setState(RoomState.HOST_DISCONNECTED);
                    lastActivityMap.put(roomSession.getRoomId(), roomSession.getLastActivityAt());

                    broadcastSystemMessage(roomSession, "host_disconnected", Map.of("hostId", userId), null);
                    scheduleHostExpiration(roomSession.getRoomId());
                    markHealthFail(roomSession.getRoomId());
                } else {
                    logger.info("👤 Usuario {} desconectado de sala {}", userId, roomSession.getRoomId());
                    broadcastSystemMessage(roomSession, "user_left", Map.of("userId", userId), null);
                }
            }
        }
        if (removed)
            logger.debug("Usuario removido de salas");
    }

    // Decide si un guest puede unirse a la sala.
    public boolean canGuestJoinRoom(String roomId) {
        RoomSession session = sessions.get(roomId);
        if (session == null)
            return false;

        // Si el host está conectado activamente, permitir.
        String hostId = session.getHostUserId();
        if (session.isUserConnected(hostId))
            return true;

        // Si el host no está conectado, permitir si aún no excedió el HOST_TIMEOUT_MS
        Instant lastHost = session.getLastHostActivity();
        if (lastHost == null)
            return false;

        long hostIdleMs = Duration.between(lastHost, Instant.now()).toMillis();
        return hostIdleMs <= HOST_TIMEOUT_MS;
    }

    // ==================== GESTIÓN DE REPRODUCCIÓN ====================

    // Inicia o reanuda la reproducción en una sala
    public boolean play(String roomId, String userId, Integer trackIndex, Long positionMs) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de reproducir en sala inexistente: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("Usuario {} sin permisos para reproducir en sala: {}", userId, roomId);
            return false;
        }

        boolean success = session.play(userId, trackIndex, positionMs);
        if (success) {
            long actualPosition = positionMs != null ? positionMs : session.getCurrentPlaybackPosition();

            broadcastPlaybackState(session, "play", actualPosition);
            logger.info("▶️ Reproducción iniciada en sala {} por {} (track: {}, position: {}ms)",
                    roomId, userId, session.getNowPlayingIndex(), actualPosition);
        }
        return success;
    }

    // Pausa la reproducción en una sala
    public boolean pause(String roomId, String userId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de pausar en sala inexistente: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("Usuario {} sin permisos para pausar en sala: {}", userId, roomId);
            return false;
        }

        boolean success = session.pause(userId);
        if (success) {
            long currentPosition = session.getCurrentPlaybackPosition();
            broadcastPlaybackState(session, "pause", currentPosition);
            logger.info("⏸️ Reproducción pausada en sala {} por {} (position: {}ms)",
                    roomId, userId, currentPosition);
        }
        return success;
    }

    // Salta al siguiente track en la playlist
    public boolean nextTrack(String roomId, String userId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de siguiente track en sala inexistente: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("Usuario {} sin permisos para siguiente track en sala: {}", userId, roomId);
            return false;
        }

        boolean success = session.nextTrack(userId);
        if (success) {
            broadcastPlaybackState(session, "play", 0L);
            logger.info("⏭️ Siguiente track en sala {} por {} (nuevo track: {})",
                    roomId, userId, session.getNowPlayingIndex());
        }
        return success;
    }

    // Regresa al track anterior en la playlist
    public boolean previousTrack(String roomId, String userId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de track anterior en sala inexistente: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("Usuario {} sin permisos para track anterior en sala: {}", userId, roomId);
            return false;
        }

        boolean success = session.previousTrack(userId);
        if (success) {
            broadcastPlaybackState(session, "play", 0L);
            logger.info("⏮️ Track anterior en sala {} por {} (nuevo track: {})",
                    roomId, userId, session.getNowPlayingIndex());
        }
        return success;
    }

    // Busca una posición específica en el track actual
    public boolean seek(String roomId, String userId, long positionMs) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de seek en sala inexistente: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("Usuario {} sin permisos para seek en sala: {}", userId, roomId);
            return false;
        }

        boolean success = session.seek(userId, positionMs);
        if (success) {
            broadcastPlaybackState(session, "seek", positionMs);
            logger.info("🔍 Seek en sala {} por {} a {}ms", roomId, userId, positionMs);
        }
        return success;
    }

    // ==================== GESTIÓN DE PLAYLIST ====================

    // Añade un track a la playlist de una sala
    public boolean addTrack(String roomId, String trackId, String title, String addedBy) {
        return addTrack(roomId, trackId, title, addedBy, 0L);
    }

    public boolean addTrack(String roomId, String trackId, String title, String addedBy, long durationMs) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de añadir track a sala inexistente: {}", roomId);
            return false;
        }
        if (!session.canUserModifyPlaylist(addedBy)) {
            logger.warn("Usuario {} sin permisos para añadir tracks en sala: {}", addedBy, roomId);
            return false;
        }

        TrackEntry track = new TrackEntry(trackId, title != null ? title : "Unknown Track", addedBy,
                System.currentTimeMillis(), durationMs);
        boolean success = session.addTrack(track, addedBy);
        if (success) {
            broadcastPlaylistUpdate(session, "add", track, null, null);
            logger.debug("🎵 Track añadido a sala {}: '{}' por {}", roomId, title, addedBy);
            lastActivityMap.put(roomId, session.getLastActivityAt());
        }
        return success;
    }

    public boolean replacePlaylist(String roomId, List<TrackEntry> newTracks, String sourceUserId) {
        RoomSession session = sessions.get(roomId);
        if (session == null)
            return false;

        boolean success = session.replacePlaylist(newTracks, sourceUserId);
        if (success) {
            // Enviamos un evento 'full_sync' o 'replace' de playlist
            // Reutilizamos broadcastPlaylistUpdate o creamos uno nuevo específico
            // Para simplicidad, enviamos un mensaje 'full_state' o 'playlist_replaced'
            // Pero broadcastPlaylistUpdate espera "add/remove/move"
            // Mejor enviamos 'full_state' parcial o un nuevo subtipo 'sync_queue'

            Map<String, Object> data = new HashMap<>();
            data.put("tracks", newTracks);
            broadcastSystemMessage(session, "playlist_sync", data, sourceUserId);

            logger.debug("🔄 Playlist sincronizada en sala {} ({} tracks) por {}", roomId, newTracks.size(),
                    sourceUserId);
            lastActivityMap.put(roomId, session.getLastActivityAt());
        }
        return success;
    }

    // Reenvía la petición de agregar track al host para que lo gestione nativamente
   // 1. Método corregido usando la nueva sintaxis de SyncMsg
    public void forwardAddRequestToHost(String roomId, String trackId, String title, String requestedBy) {
        RoomSession session = sessions.get(roomId);
        if (session == null)
            return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("trackId", trackId);
        payload.put("title", title);
        payload.put("requestedBy", requestedBy);

        // CORRECCIÓN 1: Usamos SyncMsg.system(...) en lugar de createSystemMessage
        // La firma es: system(subType, roomId, data)
        SyncMsg msg = SyncMsg.system("add_track_request", roomId, payload);
        msg.setSenderId("server"); // Opcional: marcamos que lo envía el servidor

        WebSocketSession hostSession = session.getHostSession();
        
        // Verificamos que hostSession no sea null y esté abierto
        if (hostSession != null && hostSession.isOpen()) {
            sendMessage(hostSession, msg); // Ahora llamamos al helper creado abajo
        } else {
            // Opcional: Log si no hay host disponible
            logger.warn("No se pudo reenviar track request: Host no disponible en sala " + roomId);
        }
    }

    // 2. Método Helper necesario (Agrégalo al final de tu clase RoomSessionManager)
    private void sendMessage(WebSocketSession session, SyncMsg msg) {
        try {
            if (session.isOpen()) {
                // Necesitas Jackson ObjectMapper. 
                // Si ya tienes un campo 'objectMapper' en la clase, úsalo. Si no, instancia uno aquí:
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                
                String json = mapper.writeValueAsString(msg);
                session.sendMessage(new org.springframework.web.socket.TextMessage(json));
            }
        } catch (Exception e) {
            logger.error("Error enviando mensaje directo a sesión " + session.getId(), e);
        }
    }

    // Obtiene una copia segura de la playlist
    public List<TrackEntry> getPlaylistCopy(String roomId) {
        RoomSession session = sessions.get(roomId);
        if (session == null)
            return Collections.emptyList();
        return new ArrayList<>(session.getPlaylist());
    }

    // Remueve un track de la playlist
    public boolean removeTrack(String roomId, int trackIndex, String removerUserId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de remover track de sala inexistente: {}", roomId);
            return false;
        }
        if (!session.canUserModifyPlaylist(removerUserId)) {
            logger.warn("Usuario {} sin permisos para remover tracks en sala: {}", removerUserId, roomId);
            return false;
        }
        if (trackIndex < 0 || trackIndex >= session.getPlaylistSize()) {
            logger.warn("Índice inválido al remover track: {} en sala {}", trackIndex, roomId);
            return false;
        }
        TrackEntry removedTrack = session.getPlaylist().get(trackIndex);
        boolean success = session.removeTrack(trackIndex, removerUserId);
        if (success) {
            broadcastPlaylistUpdate(session, "remove", removedTrack, trackIndex, null);
            logger.debug("🗑️ Track removido de sala {}: índice {} por {}", roomId, trackIndex, removerUserId);
            lastActivityMap.put(roomId, session.getLastActivityAt());
        }
        return success;
    }

    // Mueve un track de una posición a otra en la playlist
    public boolean moveTrack(String roomId, int fromIndex, int toIndex, String moverUserId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de mover track en sala inexistente: {}", roomId);
            return false;
        }
        if (!session.canUserModifyPlaylist(moverUserId)) {
            logger.warn("Usuario {} sin permisos para mover tracks en sala: {}", moverUserId, roomId);
            return false;
        }
        if (fromIndex < 0 || fromIndex >= session.getPlaylistSize() ||
                toIndex < 0 || toIndex >= session.getPlaylistSize()) {
            logger.warn("Índices inválidos para mover track: {} -> {} en sala {}", fromIndex, toIndex, roomId);
            return false;
        }
        TrackEntry movedTrack = session.getPlaylist().get(fromIndex);
        boolean success = session.moveTrack(fromIndex, toIndex, moverUserId);
        if (success) {
            broadcastPlaylistUpdate(session, "move", movedTrack, fromIndex, toIndex);
            logger.debug("🔀 Track movido en sala {}: de {} a {} por {}", roomId, fromIndex, toIndex, moverUserId);
            lastActivityMap.put(roomId, session.getLastActivityAt());
        }
        return success;
    }

    // Limpia toda la playlist (solo host)
    public boolean clearPlaylist(String roomId, String clearerUserId) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("Intento de limpiar playlist de sala inexistente: {}", roomId);
            return false;
        }
        if (!session.isHost(clearerUserId)) {
            logger.warn("Usuario {} sin permisos para limpiar playlist en sala: {}", clearerUserId, roomId);
            return false;
        }
        boolean success = session.clearPlaylist(clearerUserId);
        if (success) {
            broadcastSystemMessage(session, "playlist_cleared", Map.of("clearedBy", clearerUserId), null);
            logger.info("🧹 Playlist limpiada en sala {} por {}", roomId, clearerUserId);
            lastActivityMap.put(roomId, session.getLastActivityAt());
        }
        return success;
    }

    // ==================== CONFIGURACIÓN Y PERMISOS ====================

    // Actualiza la configuración de la sala
    public boolean updateRoomSettings(String roomId, String userId, Boolean allowGuestsAddTracks,
            Boolean allowGuestsControl) {
        RoomSession session = sessions.get(roomId);
        if (session == null)
            return false;
        if (!session.isHost(userId)) {
            logger.warn("Intento no autorizado de cambiar config en sala {} por: {}", roomId, userId);
            return false;
        }
        session.updateActivity();
        session.updateHostActivity();
        lastActivityMap.put(roomId, session.getLastActivityAt());

        if (allowGuestsAddTracks != null)
            session.setAllowGuestsEditQueue(allowGuestsAddTracks);
        if (allowGuestsControl != null)
            session.setAllowGuestsControl(allowGuestsControl);

        logger.info("⚙️ Configuración actualizada en sala {}: allowGuestsEditQueue={}, allowGuestsControl={}",
                roomId, session.isAllowGuestsEditQueue(), session.isAllowGuestsControl());
        broadcastRoomSettingsUpdate(session);
        return true;
    }

    public boolean updateTrackDuration(String roomId, int trackIndex, long durationMs) {
        RoomSession session = sessions.get(roomId);
        if (session == null)
            return false;
        if (trackIndex < 0 || trackIndex >= session.getPlaylistSize())
            return false;

        TrackEntry old = session.getPlaylist().get(trackIndex);
        TrackEntry updated = new TrackEntry(old.getTrackId(), old.getTitle(), old.getAddedBy(), old.getAddedAt(),
                Math.max(0L, durationMs));

        // reemplazamos elemento (CopyOnWriteArrayList -> set allowed)
        try {
            session.getPlaylist().set(trackIndex, updated);
        } catch (UnsupportedOperationException | ClassCastException ex) {
            // en caso de listas inmutables, reconstruir lista (defensivo)
            List<TrackEntry> newList = new ArrayList<>(session.getPlaylist());
            newList.set(trackIndex, updated);
            session.getPlaylist().clear();
            session.getPlaylist().addAll(newList);
        }

        logger.info("🛠️ Duración actualizada para track {} en room {}: {}ms", trackIndex, roomId, durationMs);

        // si es el now playing, re-programamos finalización automática notificando al
        // RoomSession
        if (session.getNowPlayingIndex() != null && session.getNowPlayingIndex() == trackIndex) {
            // re-emitimos estado para que clients sincronicen y RoomSession re-schedule si
            // implementado
            broadcastPlaybackState(session, "duration_updated", session.getCurrentPlaybackPosition());
        }
        return true;
    }

    // ==================== HEALTH CHECKS Y MANTENIMIENTO ====================

    // Inicializa el sistema de health checks
    @PostConstruct
    public void initializeHealthSystem() {
        healthScheduler.scheduleAtFixedRate(this::performHealthChecks,
                HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        healthScheduler.scheduleAtFixedRate(this::cleanupInactiveRooms,
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("🩺 Sistema de Health Checks inicializado");
    }

    // Registra actividad para health checks
    public void recordActivity(String roomId, String userId, boolean isHost) {
        if (roomId == null)
            return;
        RoomSession session = sessions.get(roomId);
        if (session != null) {
            session.updateActivity();
            if (isHost)
                session.updateHostActivity();
            lastActivityMap.put(roomId, session.getLastActivityAt());
        } else {
            // si no existe la sala, ignoramos
            logger.debug("recordActivity para sala inexistente: {}", roomId);
        }
        if (isHost)
            scheduleHealthCheck(roomId, true);
    }

    // Programa un health check para una sala
    public void scheduleHealthCheck(String roomId, boolean isHost) {
        cancelHealthCheck(roomId);
        ScheduledFuture<?> future = healthScheduler.schedule(() -> {
            // usa el estado actual para evaluar
            performHealthCheck(roomId, isHost);
        }, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduledHealthChecks.put(roomId, new ScheduledHealthCheck(roomId, isHost, future));
        logger.debug("Health check programado para sala {} (isHost={})", roomId, isHost);
    }

    // Programa expiración de sala por inactividad del host
    private void scheduleHostExpiration(String roomId) {
        healthScheduler.schedule(() -> {
            RoomSession session = sessions.get(roomId);
            if (session != null && session.isHostDisconnected(HOST_TIMEOUT_MS)) {
                logger.warn("💀 Sala expirada por host inactivo: {}", roomId);
                publisher.publishEvent(RoomHealthSystem.Event.roomExpired(this, roomId));
                deleteRoom(roomId, "health_system");
            }
        }, RECONNECTION_WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    // Ejecuta health checks para todas las salas
    private void performHealthChecks() {
        try {
            Instant now = Instant.now();
            for (Map.Entry<String, RoomSession> e : sessions.entrySet()) {
                String roomId = e.getKey();
                RoomSession session = e.getValue();

                Instant lastActivity = lastActivityMap.getOrDefault(roomId, session.getLastActivityAt());
                Instant lastHost = session.getLastHostActivity();

                long inactivityMs = Duration.between(lastActivity, now).toMillis();
                long hostIdleMs = Duration.between(lastHost, now).toMillis();

                boolean hostTimedOut = hostIdleMs > HOST_TIMEOUT_MS;

                if (hostTimedOut) {
                    if (markHealthFail(roomId)) {
                        logger.warn("👑 Timeout del host detectado para sala: {} ({}ms)", roomId, hostIdleMs);
                        publisher.publishEvent(RoomHealthSystem.Event.hostDisconnected(this, roomId));
                        scheduleHostExpiration(roomId);
                    }
                    continue;
                }

                if (session.isHostDisconnected(HOST_TIMEOUT_MS)) {
                    boolean roomExpired = inactivityMs > RECONNECTION_WINDOW_MS;
                    if (roomExpired) {
                        if (markHealthFail(roomId)) {
                            logger.warn("💀 Sala expirada por inactividad: {} ({}ms)", roomId, inactivityMs);
                            publisher.publishEvent(RoomHealthSystem.Event.roomExpired(this, roomId));
                        }
                        deleteRoom(roomId, "health_system");
                        continue;
                    }
                }

                if (markHealthPass(roomId)) {
                    publisher.publishEvent(RoomHealthSystem.Event.healthCheckPassed(this, roomId));
                }
            }
        } catch (Exception ex) {
            logger.error("💥 Error durante health checks: {}", ex.getMessage(), ex);
        }
    }

    // Ejecuta un health check para una sala específica
    public void performHealthCheck(String roomId, boolean isHost) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("❌ Sala no encontrada en health check puntual: {}", roomId);
            publisher.publishEvent(RoomHealthSystem.Event.healthCheckFailed(this, roomId));
            return;
        }
        Instant lastActivity = lastActivityMap.getOrDefault(roomId, session.getLastActivityAt());
        Instant now = Instant.now();
        long inactivityMs = Duration.between(lastActivity, now).toMillis();
        long hostIdleMs = Duration.between(session.getLastHostActivity(), now).toMillis();

        if (isHost && hostIdleMs > HOST_TIMEOUT_MS) {
            if (markHealthFail(roomId)) {
                logger.warn("👑 Host timeout en healthCheck puntual para sala {} ({}ms)", roomId, hostIdleMs);
                publisher.publishEvent(RoomHealthSystem.Event.hostDisconnected(this, roomId));
                scheduleHostExpiration(roomId);
            }
        } else if (inactivityMs > RECONNECTION_WINDOW_MS) {
            if (markHealthFail(roomId)) {
                logger.warn("💀 Sala expirada en healthCheck puntual: {} ({}ms)", roomId, inactivityMs);
                publisher.publishEvent(RoomHealthSystem.Event.roomExpired(this, roomId));
            }
        } else {
            if (markHealthPass(roomId)) {
                publisher.publishEvent(RoomHealthSystem.Event.healthCheckPassed(this, roomId));
            }
        }
    }

    // Cancela un health check programado
    private void cancelHealthCheck(String roomId) {
        ScheduledHealthCheck shc = scheduledHealthChecks.remove(roomId);
        if (shc != null) {
            ScheduledFuture<?> f = shc.getFuture();
            if (f != null && !f.isDone())
                f.cancel(true);
            logger.debug("🛑 Health check cancelado para sala: {}", roomId);
        }
    }

    // Limpieza periódica de salas inactivas
    public void cleanupInactiveRooms() {
        logger.debug("🧹 Ejecutando limpieza de salas inactivas");
        Instant now = Instant.now();
        List<String> toRemove = new ArrayList<>();

        for (RoomSession s : sessions.values()) {
            if (s.getLastHostActivity().isBefore(now.minusMillis(RECONNECTION_WINDOW_MS))) {
                toRemove.add(s.getRoomId());
            }
        }

        lastActivityMap.entrySet().removeIf(entry -> {
            long inactivityMs = Duration.between(entry.getValue(), now).toMillis();
            boolean shouldRemove = inactivityMs > RECONNECTION_WINDOW_MS * 2;
            if (shouldRemove) {
                logger.info("🗑️ Eliminando sala inactiva del health monitoring: {}", entry.getKey());
                cancelHealthCheck(entry.getKey());
            }
            return shouldRemove;
        });

        for (String roomId : toRemove) {
            logger.warn("🕒 Eliminando sala inactiva: {}", roomId);
            RoomSession session = sessions.get(roomId);
            if (session != null) {
                broadcastSystemMessage(session, "room_expired", Map.of("reason", "Host inactive"), null);
                closeAllConnections(session);
            }
            sessions.remove(roomId);
            lastActivityMap.remove(roomId);
            cancelHealthCheck(roomId);
            healthStateMap.remove(roomId);
        }

        if (!toRemove.isEmpty()) {
            logger.info("🧹 Limpieza completada: {} salas eliminadas", toRemove.size());
        }
    }

    private boolean markHealthFail(String roomId) {
        Boolean prev = healthStateMap.put(roomId, false);
        return prev == null || prev;
    }

    private boolean markHealthPass(String roomId) {
        Boolean prev = healthStateMap.put(roomId, true);
        return prev == null || !prev;
    }

    // ==================== HANDLERS DE EVENTOS DE SALUD ====================
    // Maneja eventos de salud de salas
    @EventListener
    public void handleRoomHealthEvent(RoomHealthSystem.Event event) {
        logger.info("🎯 RoomSessionManager procesando health event: {} for room: {}",
                event.getAction(), event.getRoomId());

        RoomSession session = getSession(event.getRoomId());
        if (session == null) {
            logger.warn("Room no encontrado para health event: {}", event.getRoomId());
            return;
        }

        switch (event.getAction()) {
            case HOST_DISCONNECTED:
                handleHostDisconnected(event, session);
                break;
            case ROOM_EXPIRED:
                handleRoomExpired(event, session);
                break;
            case HEALTH_CHECK_FAIL:
                handleHealthCheckFail(event, session);
                break;
            case HOST_RECONNECTED:
                handleHostReconnected(event, session);
                break;
            case HEALTH_CHECK_PASS:
                handleHealthCheckPass(event, session);
                break;
            default:
                logger.warn("❓ RoomHealthAction desconocido: {}", event.getAction());
        }
    }

    // Maneja desconexión de Host
    private void handleHostDisconnected(RoomHealthSystem.Event event, RoomSession session) {
        session.setState(RoomState.HOST_DISCONNECTED);
        session.setHostDisconnected(true);
        broadcastSystemMessage(session, "host_disconnected",
                Map.of("hostId", session.getHostUserId(), "reason", "health_check"), null);
        logger.warn("👑 Host desconectado via health check: {}", session.getRoomId());
    }

    // Maneja expiración de Room
    private void handleRoomExpired(RoomHealthSystem.Event event, RoomSession session) {
        broadcastSystemMessage(session, "room_expired", Map.of("reason", "Health check timeout"), null);
        deleteRoom(event.getRoomId(), "health_system");
        logger.warn("💀 Room expirado via health check: {}", session.getRoomId());
    }

    // Maneja reconexión de Host
    private void handleHostReconnected(RoomHealthSystem.Event event, RoomSession session) {
        session.setState(RoomState.ACTIVE);
        session.updateHostActivity();
        session.setHostDisconnected(false);
        broadcastSystemMessage(session, "host_reconnected", Map.of("hostId", session.getHostUserId()), null);
        logger.info("✅ Host reconectado via health check: {}", session.getRoomId());
    }

    // Maneja fallo de HealthCheck
    private void handleHealthCheckFail(RoomHealthSystem.Event event, RoomSession session) {
        logger.warn("❌ Health check falló para room: {}", session.getRoomId());
        broadcastSystemMessage(session, "health_warning", Map.of("message", "Connection issues detected"), null);
    }

    // Maneja aprovación de HealthCheck
    private void handleHealthCheckPass(RoomHealthSystem.Event event, RoomSession session) {
        logger.debug("✅ Health check aprovado para room: {}", session.getRoomId());
        session.setFailedHeartbeats(0);
    }

    // ==================== IMPLEMENTACIÓN ROOM EVENT LISTENER ====================

    // ==================== UTILIDADES DE BROADCAST ====================

    public boolean isHost(String roomId, String userId) {
        RoomSession session = sessions.get(roomId);
        return session != null && session.isHost(userId);
    }

    public boolean canControlPlayback(String roomId, String userId) {
        RoomSession session = sessions.get(roomId);
        return session != null && session.canUserControlPlayback(userId);
    }

    public boolean canModifyPlaylist(String roomId, String userId) {
        RoomSession session = sessions.get(roomId);
        return session != null && session.canUserModifyPlaylist(userId);
    }

    public long getCurrentPlaybackPosition(String roomId) {
        RoomSession session = sessions.get(roomId);
        return session != null ? session.getCurrentPlaybackPosition() : 0L;
    }

    public PlaybackState getCurrentPlaybackState(String roomId, String senderId) {
        RoomSession session = sessions.get(roomId);
        return session != null ? session.toPlaybackState(senderId) : null;
    }

    // Broadcast actualización de playlist
    public void broadcastPlaylistUpdate(RoomSession room, String action, TrackEntry track, Integer fromIndex,
            Integer toIndex) {
        if (room == null)
            return;
        try {
            Map<String, Object> playlistData = new HashMap<>();
            playlistData.put("action", action);
            playlistData.put("track", track);
            playlistData.put("playlistSize", room.getPlaylistSize());
            playlistData.put("nowPlayingIndex", room.getNowPlayingIndex());
            if (fromIndex != null)
                playlistData.put("fromIndex", fromIndex);
            if (toIndex != null)
                playlistData.put("toIndex", toIndex);

            Map<String, Object> payload = Map.of(
                    "type", "playlist_update",
                    "data", playlistData);
            broadcastToRoom(room, payload, null);
            logger.debug("📝 Playlist broadcast: {} in room: {}", action, room.getRoomId());
        } catch (Exception e) {
            logger.error("❌ Error en broadcastPlaylistUpdate: {}", e.getMessage(), e);
        }
    }

    // Broadcast un mensaje del sistema a todos los usuarios de una sala
    public void broadcastSystemMessage(RoomSession room, String eventType, Map<String, Object> data,
            String excludeSenderId) {
        if (room == null)
            return;
        try {
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("event", eventType);
            messageData.put("roomId", room.getRoomId());
            messageData.put("timestamp", System.currentTimeMillis());
            if (data != null)
                messageData.putAll(data);

            Map<String, Object> payload = Map.of(
                    "type", "system",
                    "data", messageData);

            broadcastToRoom(room, payload, excludeSenderId);
            logger.debug("📢 System broadcast: {} to room: {} (excluded: {})",
                    eventType, room.getRoomId(), excludeSenderId);
        } catch (Exception e) {
            logger.error("❌ Error en broadcastSystemMessage: {}", e.getMessage(), e);
        }
    }

    // Broadcast el estado de reproducción actual
    public void broadcastPlaybackState(RoomSession room, String action, long positionMs) {
        if (room == null)
            return;
        try {
            Map<String, Object> playbackData = new HashMap<>();
            playbackData.put("action", action);
            playbackData.put("currentTrack", room.getNowPlaying());
            playbackData.put("currentTrackIndex", room.getNowPlayingIndex());
            playbackData.put("positionMs", positionMs);
            playbackData.put("timestamp", System.currentTimeMillis());
            playbackData.put("roomId", room.getRoomId());

            Map<String, Object> payload = Map.of(
                    "type", "playback",
                    "data", playbackData);

            broadcastToRoom(room, payload, null);
            logger.debug("🎵 Playback broadcast: {} at {}ms in room: {}", action, positionMs, room.getRoomId());
        } catch (Exception e) {
            logger.error("❌ Error en broadcastPlaybackState: {}", e.getMessage(), e);
        }
    }

    // Broadcast actualización de configuración de sala
    public void broadcastRoomSettingsUpdate(RoomSession room) {
        if (room == null)
            return;
        try {
            Map<String, Object> settingsData = new HashMap<>();
            settingsData.put("allowGuestsEditQueue", room.isAllowGuestsEditQueue());
            settingsData.put("allowGuestsControl", room.isAllowGuestsControl());
            settingsData.put("roomId", room.getRoomId());

            Map<String, Object> payload = Map.of(
                    "type", "room_settings_updated",
                    "data", settingsData);

            broadcastToRoom(room, payload, null);
            logger.debug("⚙️ Settings broadcast in room: {}", room.getRoomId());
        } catch (Exception e) {
            logger.error("❌ Error en broadcastRoomSettingsUpdate: {}", e.getMessage(), e);
        }
    }

    // Broadcast un objeto SyncMsg a una sala
    public void broadcastToRoom(String roomId, SyncMsg message) {
        RoomSession session = sessions.get(roomId);
        if (session == null) {
            logger.warn("⚠️ No se pudo hacer broadcast - sala no encontrada: {}", roomId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            broadcastToRoom(session, json, null);
        } catch (Exception e) {
            logger.error("❌ Error en broadcastToRoom: {}", e.getMessage(), e);
        }
    }

    // Broadcast un mensaje personalizado a todos los usuarios de una sala
    public void broadcastToRoom(RoomSession room, Object payload, String excludeSenderId) {
        if (room == null)
            return;

        // actualizar actividad de la sala
        room.updateActivity();

        String json;
        try {
            if (payload instanceof String)
                json = (String) payload;
            else
                json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.error("❌ Error serializando mensaje para sala {}: {}", room.getRoomId(), e.getMessage(), e);
            return;
        }

        room.getClients().forEach((targetSenderId, session) -> {
            if (excludeSenderId != null && excludeSenderId.equals(targetSenderId))
                return;
            safeSend(session, json);
        });
    }

    // Envío seguro de mensajes WebSocket
    public void safeSend(WebSocketSession session, String json) {
        if (session == null)
            return;
        try {
            if (!session.isOpen())
                return;
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            logger.warn("⚠️ Error enviando mensaje WebSocket a sesión {}: {}", session.getId(), e.getMessage());
        }
    }

    // Enviar mensaje ACK de confirmación
    public void sendAck(WebSocketSession session, boolean success, String reason, String correlationId) {
        try {
            Map<String, Object> ackData = new HashMap<>();
            ackData.put("success", success);
            ackData.put("reason", reason);
            ackData.put("correlationId", correlationId);
            ackData.put("timestamp", System.currentTimeMillis());

            Map<String, Object> payload = Map.of(
                    "type", "ack",
                    "data", ackData);

            safeSend(session, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.error("❌ Error enviando ACK: {}", e.getMessage(), e);
        }
    }

    // Enviar estado completo de la sala a un usuario específico
    public void sendFullRoomState(WebSocketSession session, RoomSession room) {
        if (session == null || room == null)
            return;
        try {
            Map<String, Object> stateData = new HashMap<>();
            stateData.put("room", room.toRoomResponse());
            stateData.put("playlist", room.getPlaylist());
            stateData.put("nowPlayingIndex", room.getNowPlayingIndex());
            stateData.put("nowPlaying", room.getNowPlayingTrack());
            stateData.put("settings", Map.of(
                    "allowGuestsEditQueue", room.isAllowGuestsEditQueue(),
                    "allowGuestsControl", room.isAllowGuestsControl()));
            stateData.put("timestamp", System.currentTimeMillis());

            Map<String, Object> payload = Map.of(
                    "type", "full_state",
                    "data", stateData);

            safeSend(session, objectMapper.writeValueAsString(payload));
            logger.debug("📦 Sent full state to user in room: {}", room.getRoomId());
        } catch (Exception e) {
            logger.error("❌ Error enviando estado completo a sesión {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    // Notificar error a un usuario específico
    public void sendError(WebSocketSession session, String errorCode, String message, String correlationId) {
        try {
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("errorCode", errorCode);
            errorData.put("message", message);
            errorData.put("correlationId", correlationId);
            errorData.put("timestamp", System.currentTimeMillis());

            Map<String, Object> payload = Map.of(
                    "type", "error",
                    "data", errorData);

            safeSend(session, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.error("❌ Error enviando error: {}", e.getMessage(), e);
        }
    }

    // Cierra todas las conexiones WebSocket de una sala
    private void closeAllConnections(RoomSession session) {
        session.getClients().values().forEach(webSocketSession -> {
            try {
                if (webSocketSession != null && webSocketSession.isOpen()) {
                    safeSend(webSocketSession,
                            "{\"type\":\"system\",\"event\":\"room_closed\",\"reason\":\"cleanup\"}");
                }
            } catch (Exception e) {
                logger.debug("Error enviando mensaje de cierre: {}", e.getMessage());
            }
            try {
                if (webSocketSession != null && webSocketSession.isOpen()) {
                    webSocketSession.close();
                }
            } catch (Exception e) {
                logger.debug("Error cerrando sesión: {}", e.getMessage());
            }
        });

        session.getClients().clear();
        session.getSessionToSender().clear();
    }

    // ==================== SCHEDULING & HELPERS ====================

    // Clase interna para manejar health checks programados

    @Getter
    private static class ScheduledHealthCheck {
        private final String roomId;
        private final boolean isHost;
        private final ScheduledFuture<?> future;

        public ScheduledHealthCheck(String roomId, boolean isHost, ScheduledFuture<?> future) {
            this.roomId = roomId;
            this.isHost = isHost;
            this.future = future;
        }
    }

    // Obtiene estadisticas del servicio
    public Map<String, Object> getServiceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRooms", sessions.size());
        stats.put("totalConnections", sessions.values().stream().mapToInt(RoomSession::getClientCount).sum());
        stats.put("totalTracks", sessions.values().stream().mapToInt(RoomSession::getPlaylistSize).sum());
        stats.put("activePlayingRooms", sessions.values().stream().filter(s -> s.getNowPlayingIndex() != null).count());
        stats.put("timestamp", System.currentTimeMillis());
        return stats;
    }

    // Obtiene estadisticas de salud del servicio
    public Map<String, Object> getHealthStats() {
        Map<String, Object> stats = getServiceStats();
        stats.put("monitoredRooms", lastActivityMap.size());
        stats.put("scheduledHealthChecks", scheduledHealthChecks.size());
        stats.put("healthSystemStatus", shuttingDown.get() ? "SHUTTING_DOWN" : "ACTIVE");
        stats.put("hostTimeoutMs", HOST_TIMEOUT_MS);
        stats.put("reconnectionWindowMs", RECONNECTION_WINDOW_MS);
        stats.put("healthCheckIntervalMs", HEALTH_CHECK_INTERVAL_MS);
        return stats;
    }

    // ==================== VALIDACIONES ====================

    private void validateRoomId(String roomId) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("roomId no puede ser nulo o vacío");
        }
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId no puede ser nulo o vacío");
        }
    }

    // ==================== MÉTODOS DE MONITOREO ====================

    // Log del estado actual de todas las salas (debug)
    public void debugRooms() {
        logger.info("=== DEBUG ROOMS ===");
        sessions.forEach((roomId, session) -> logger.info(
                "Room: {} | Host: {} | State: {} | Clients: {} | Playlist: {} | NowPlaying: {}/{}",
                roomId,
                session.getHostUserId(),
                session.getState(),
                session.getClients().keySet(),
                session.getPlaylistSize(),
                session.getNowPlayingIndex(),
                session.getNowPlayingTrack() != null ? session.getNowPlayingTrack().title() : "none"));
        logger.info("===================");
    }

    // ==================== CONCURRENCIES Y LIMPIEZA ====================
    @Override
    public void onTrackChanged(RoomSession session) {
        broadcastPlaybackState(session, "play", 0);
    }

    @Override
    public void onPlaylistEnded(RoomSession session) {
        broadcastSystemMessage(session, "playlist_ended", null, null);
    }

    @Override
    public void onPlaylistChanged(RoomSession session) {
        Map<String, Object> data = new HashMap<>();
        data.put("tracks", session.getPlaylist());
        broadcastSystemMessage(session, "playlist_sync", data, null);
    }

    public void shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            logger.info("Shutting down RoomSessionManager...");
            healthScheduler.shutdownNow();
        }
    }
}