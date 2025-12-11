package com.rebenew.musicParty.syncserver.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rebenew.musicParty.syncserver.model.*;
import com.rebenew.musicParty.syncserver.core.RoomSessionManager;
import com.rebenew.musicParty.syncserver.service.PlaybackService;
import com.rebenew.musicParty.syncserver.service.PlaylistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

@Component
public class SyncWebSocketHandler extends TextWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(SyncWebSocketHandler.class);

    private final RoomSessionManager sessionManager;
    private final PlaybackService playbackService;
    private final PlaylistService playlistService;
    private final ObjectMapper objectMapper;

    // Configuración
    private static final long CLIENT_TIMEOUT_MS = 600_000L;
    private static final long SWEEP_INTERVAL_MS = 30_000L;

    // Sesiones de usuario
    private final ConcurrentMap<String, UserSession> userSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();

    // ✅ CONSTRUCTOR SIMPLIFICADO
    public SyncWebSocketHandler(RoomSessionManager sessionManager, PlaybackService playbackService, PlaylistService playlistService, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.playbackService = playbackService;
        this.playlistService = playlistService;
        this.objectMapper = objectMapper;
        startSweeper();
        logger.info("✅ SyncWebSocketHandler inicializado con servicios consolidados");
    }
    // ==================== CICLO DE VIDA WEBSOCKET ====================

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        logger.info("🔄 Nueva conexión WebSocket: {}", session.getId());
        userSessions.put(session.getId(), new UserSession(session));
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        UserSession userSession = userSessions.get(session.getId());
        if (userSession != null) {
            userSession.updateActivity();
        }

        try {
            SyncMsg syncMsg = objectMapper.readValue(message.getPayload(), SyncMsg.class);
            processMessage(session, syncMsg);
        } catch (Exception e) {
            logger.error("❌ Error parseando mensaje: {}", e.getMessage());
            sessionManager.sendAck(session, false, "invalid_message", null);
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        UserSession userSession = userSessions.remove(session.getId());
        if (userSession != null && userSession.roomId != null) {
            sessionManager.removeUserFromAllRooms(session);
            logger.info("🔌 Conexión cerrada: {} - Sala: {}", session.getId(), userSession.roomId);
        } else {
            logger.info("🔌 Conexión cerrada: {}", session.getId());
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        logger.error("🚨 Error de transporte WebSocket: {} - {}", session.getId(), exception.getMessage());
    }

    // ==================== PROCESAMIENTO PRINCIPAL ====================

    private void processMessage(WebSocketSession session, SyncMsg msg) {
        String type = msg.getType();
        String roomId = msg.getRoomId();
        String senderId = msg.getSenderId();
        String correlationId = msg.getCorrelationId();

        if (type == null || roomId == null || senderId == null) {
            sessionManager.sendAck(session, false, "missing_required_fields", correlationId);
            return;
        }

        UserSession userSession = getUserSession(session);
        if (userSession != null && !validateSession(userSession, msg)) {
            sessionManager.sendAck(session, false, "invalid_session", correlationId);
            return;
        }

        try {
            switch (type) {
                case "auth":
                    handleAuthentication(session, msg);
                    break;
                case "playback":
                    handlePlaybackControl(session, msg);
                    break;
                case "playlist":
                    handlePlaylistManagement(session, msg);
                    break;
                case "settings":
                    handleRoomSettings(session, msg);
                    break;
                case "sync":
                    handleSyncRequest(session, msg);
                    break;
                case "heartbeat":
                    handleHeartbeat(session, msg);
                    break;
                case "system":
                    handleSystemEvent(session, msg);
                    break;
                default:
                    sessionManager.sendAck(session, false, "unknown_message_type", correlationId);
            }
        } catch (Exception e) {
            logger.error("❌ Error processing message {}: {}", type, e.getMessage());
            sessionManager.sendAck(session, false, "processing_error", correlationId);
        }

        if (userSession != null) {
            sessionManager.recordActivity(userSession.roomId, userSession.senderId, userSession.isHost);
        }
    }

    // ==================== MANEJO DE AUTENTICACIÓN ====================
    private void handleAuthentication(WebSocketSession session, SyncMsg msg) {
        String roomId = msg.getRoomId();
        String senderId = msg.getSenderId();
        boolean isHost = msg.getBoolData("isHost", false);
        String correlationId = msg.getCorrelationId();

        if (!sessionManager.roomExists(roomId)) {
            sessionManager.sendAck(session, false, "room_not_found", correlationId);
            return;
        }

        RoomSession roomSession = sessionManager.getSession(roomId);
        if (roomSession == null) {
            sessionManager.sendAck(session, false, "room_not_found", correlationId);
            return;
        }

        // Recuperación de identidad de Host: Si el senderId coincide con el host de la
        // sala, forzamos isHost=true
        if (roomSession.isHost(senderId)) {
            logger.info("👑 Identificado Host por senderId {} (Client reportó: {})", senderId, isHost);
            isHost = true;
        }

        if (!isHost) {
            // Permitir unirse si está activa O si el host está desconectado pero la sala
            // sigue válida (Grace period)
            boolean isActive = roomSession.isActive();
            boolean isCreated = roomSession.getState() == RoomState.CREATED;
            boolean isHostDisconnected = roomSession.getState() == RoomState.HOST_DISCONNECTED;

            if (!isActive && !isCreated && !isHostDisconnected) {
                // Solo rechazar si está realmente inactiva (ej. expirada o en estado inválido)
                // y no es el host desconectado
                sessionManager.sendAck(session, false, "room_not_active", correlationId);
                return;
            }
        }

        boolean joined = sessionManager.addUserToRoom(roomId, session, senderId, isHost);
        if (!joined) {
            sessionManager.sendAck(session, false, "join_failed", correlationId);
            return;
        }

        UserSession userSession = userSessions.get(session.getId());
        if (userSession == null) {
            logger.error("❌ CRÍTICO: UserSession no encontrada en handleAuthentication para session {}",
                    session.getId());
            // Intentar recuperar/crear sesión si falta (defensive)
            userSession = new UserSession(session);
            userSessions.put(session.getId(), userSession);
        }

        userSession.roomId = roomId;
        userSession.senderId = senderId;
        userSession.isHost = isHost;

        logger.info("🔐 Sesión autenticada y vinculada: [SessionID: {}] -> [RoomID: {}]", session.getId(), roomId);

        sessionManager.sendAck(session, true, "authenticated", correlationId);
        sessionManager.sendFullRoomState(session, roomSession);
    }

    // ==================== MANEJO DE EVENTOS ESPECÍFICOS ====================

    private void handleHeartbeat(WebSocketSession session, SyncMsg msg) {
        String correlationId = msg.getCorrelationId();
        sessionManager.sendAck(session, true, "heartbeat_received", correlationId);
    }

    private void handlePlaybackControl(WebSocketSession session, SyncMsg msg) {
        String roomId = msg.getRoomId();
        String senderId = msg.getSenderId();
        String subType = msg.getSubType();
        Map<String, Object> data = msg.getDataAsMap();
        String correlationId = msg.getCorrelationId();

        if (roomId == null || senderId == null) {
            sessionManager.sendAck(session, false, "missing_params", correlationId);
            return;
        }

        boolean success = false;
        if ("play".equals(subType)) {
            Integer trackIndex = data != null ? (Integer) data.get("trackIndex") : null;
            Long positionMs = data != null ? ((Number) data.getOrDefault("positionMs", 0L)).longValue() : 0L;
            success = playbackService.play(roomId, senderId, trackIndex, positionMs);
        } else if ("pause".equals(subType)) {
            success = playbackService.pause(roomId, senderId);
        } else if ("next".equals(subType)) {
            success = playbackService.nextTrack(roomId, senderId);
        } else if ("previous".equals(subType)) {
            success = playbackService.previousTrack(roomId, senderId);
        } else if ("seek".equals(subType)) {
            Long positionMs = data != null ? ((Number) data.getOrDefault("positionMs", 0L)).longValue() : 0L;
            success = playbackService.seek(roomId, senderId, positionMs);
        } else if ("syncState".equals(subType)) {
            // Sincronización completa de estado (para hosts que reconectan o inicializan)
            Integer trackIndex = data != null ? (Integer) data.get("trackIndex") : null;
            Long positionMs = data != null ? ((Number) data.getOrDefault("positionMs", 0L)).longValue() : 0L;
            boolean isPlaying = data != null && Boolean.TRUE.equals(data.get("isPlaying"));

            if (isPlaying) {
                success = playbackService.play(roomId, senderId, trackIndex, positionMs);
            } else {
                success = playbackService.pause(roomId, senderId);
                if (positionMs > 0)
                    playbackService.seek(roomId, senderId, positionMs);
            }
        } else {
            sessionManager.sendAck(session, false, "unknown_subtype", correlationId);
            return;
        }

        if (success) {
            sessionManager.sendAck(session, true, "success", correlationId);
        } else {
            sessionManager.sendAck(session, false, "action_failed", correlationId);
        }
    }

    private void handlePlaylistManagement(WebSocketSession session, SyncMsg msg) {
        String roomId = msg.getRoomId();
        String senderId = msg.getSenderId();
        String subType = msg.getSubType();
        Map<String, Object> data = msg.getDataAsMap();
        String correlationId = msg.getCorrelationId();

        if (roomId == null || senderId == null) {
            sessionManager.sendAck(session, false, "missing_params", correlationId);
            return;
        }

        boolean success = false;
        if ("sync_queue".equals(subType)) {
            if (data != null && data.containsKey("tracks")) {
                List<Map<String, Object>> tracksData = (List<Map<String, Object>>) data.get("tracks");
                List<com.rebenew.musicParty.syncserver.model.TrackEntry> newTracks = new ArrayList<>();

                if (tracksData != null) {
                    for (Map<String, Object> trackMap : tracksData) {
                        String trackId = (String) trackMap.get("trackId");
                        String title = (String) trackMap.get("title");
                        // String addedBy = (String) trackMap.get("addedBy"); // Usamos el del mapa o
                        // senderId
                        Long durationMs = trackMap.get("durationMs") != null
                                ? ((Number) trackMap.get("durationMs")).longValue()
                                : 0L;

                        if (trackId != null) {
                            newTracks.add(new com.rebenew.musicParty.syncserver.model.TrackEntry(
                                    trackId,
                                    title != null ? title : "Unknown",
                                    "Host", // Force Host as source for native sync
                                    System.currentTimeMillis(),
                                    durationMs));
                        }
                    }
                }
                success = playlistService.replacePlaylist(roomId, newTracks, senderId);
            }
        } else if ("add".equals(subType)) {
            if (data != null) {
                // Convertir mapa a TrackEntry (simplificado)
                String trackId = (String) data.get("trackId");
                String title = (String) data.get("title");
                String thumbnailUrl = (String) data.get("thumbnailUrl");
                Long durationMs = data.get("durationMs") != null ? ((Number) data.get("durationMs")).longValue() : 0L;

                if (trackId != null) {
                    success = playlistService.addTrack(roomId, trackId, title, senderId, durationMs);
                }
            }
        } else if ("remove".equals(subType)) {
            Integer index = data != null ? (Integer) data.get("trackIndex") : null;
            if (index != null) {
                success = playlistService.removeTrack(roomId, index, senderId);
            }
        } else if ("move".equals(subType)) {
            Integer from = data != null ? (Integer) data.get("fromIndex") : null;
            Integer to = data != null ? (Integer) data.get("toIndex") : null;
            if (from != null && to != null) {
                success = playlistService.moveTrack(roomId, from, to, senderId);
            }
        }

        if (success) {
            sessionManager.sendAck(session, true, "success", correlationId);
        } else {
            sessionManager.sendAck(session, false, "action_failed", correlationId);
        }
    }

    private void handleRoomSettings(WebSocketSession session, SyncMsg msg) {
        String roomId = msg.getRoomId();
        String senderId = msg.getSenderId();
        Map<String, Object> data = msg.getDataAsMap();
        String correlationId = msg.getCorrelationId();

        if (roomId == null || senderId == null || data == null) {
            sessionManager.sendAck(session, false, "missing_params", correlationId);
            return;
        }

        Boolean allowGuestsAddTracks = (Boolean) data.get("allowGuestsAddTracks");
        Boolean allowGuestsControl = (Boolean) data.get("allowGuestsControl");

        boolean success = sessionManager.updateRoomSettings(roomId, senderId, allowGuestsAddTracks, allowGuestsControl);

        if (success) {
            sessionManager.sendAck(session, true, "success", correlationId);
        } else {
            sessionManager.sendAck(session, false, "action_failed", correlationId);
        }
    }

    private void handleSyncRequest(WebSocketSession session, SyncMsg msg) {
        // TODO: Implementar solicitud de sincronización
        logger.warn("⚠️ handleSyncRequest no implementado. Mensaje ignorado.");
        sessionManager.sendAck(session, false, "not_implemented", msg.getCorrelationId());
    }

    private static class UserSession {
        final WebSocketSession session;
        volatile String roomId;
        volatile String senderId;
        volatile boolean isHost;
        volatile long lastActivity;

        UserSession(WebSocketSession session) {
            this.session = session;
            this.lastActivity = System.currentTimeMillis();
        }

        void updateActivity() {
            this.lastActivity = System.currentTimeMillis();
        }
    }

    // ==================== MÉTODOS DE VALIDACIÓN ====================

    private UserSession getUserSession(WebSocketSession session) {
        return userSessions.get(session.getId());
    }

    /**
     * Metodo de validación de sesión
     */
    private boolean validateSession(UserSession userSession, SyncMsg msg) {
        if (userSession == null) {
            logger.warn("❌ Invalid session: UserSession is null for session {}", msg.getSenderId());
            return false;
        }
        if (userSession.roomId == null) {
            logger.warn("❌ Invalid session: roomId is null in UserSession");
            return false;
        }
        if (!userSession.roomId.equals(msg.getRoomId())) {
            logger.warn("❌ Invalid session: Mismatch roomId. Session: {} vs Msg: {}", userSession.roomId,
                    msg.getRoomId());
            return false;
        }
        if (userSession.senderId == null) {
            logger.warn("❌ Invalid session: senderId is null in UserSession");
            return false;
        }
        if (!userSession.senderId.equals(msg.getSenderId())) {
            logger.warn("❌ Invalid session: Mismatch senderId. Session: {} vs Msg: {}", userSession.senderId,
                    msg.getSenderId());
            return false;
        }
        return true;
    }

    // ==================== LIMPIEZA DE SESIONES INACTIVAS ====================

    private void startSweeper() {
        sweeper.scheduleAtFixedRate(this::cleanupInactiveSessions,
                SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("🧹 Sweeper iniciado");
    }

    private void cleanupInactiveSessions() {
        long now = System.currentTimeMillis();
        userSessions.entrySet().removeIf(entry -> {
            UserSession userSession = entry.getValue();
            boolean isInactive = (now - userSession.lastActivity) > CLIENT_TIMEOUT_MS;
            if (isInactive) {
                try {
                    if (userSession.roomId != null) {
                        sessionManager.removeUserFromAllRooms(userSession.session);
                    }
                    userSession.session.close();
                } catch (IOException ignored) {
                }
                return true;
            }
            return false;
        });
    }

    // ==================== MÉTODOS DE UTILIDAD ====================

    private void handleSystemEvent(WebSocketSession session, SyncMsg msg) {
        String correlationId = msg.getCorrelationId();
        String roomId = msg.getRoomId();

        // Solo reconoce el health_check como válido
        Map<String, Object> data = msg.getDataAsMap();
        if (data != null && "health_check".equals(data.get("event"))) {
            logger.info("🟢 Health check recibido para sala {}", roomId);
            sessionManager.sendAck(session, true, "health_check_received", correlationId);
            return;
        }

        sessionManager.sendAck(session, false, "unknown_system_event", correlationId);
    }

    /**
     * Extrae un valor float del data de SyncMsg (alternativa si no existe
     * getFloatData)
     */
    private Float extractFloatFromData(SyncMsg msg, String key) {
        Map<String, Object> data = msg.getDataAsMap();
        if (data == null)
            return null;

        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        } else if (value instanceof String) {
            try {
                return Float.parseFloat((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}