package com.rebenew.musicParty.syncserver.controller;

import com.rebenew.musicParty.syncserver.core.RoomSessionManager;
import com.rebenew.musicParty.syncserver.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controlador para gestión de salas de sincronización musical.
 * Maneja la creación, eliminación y configuración de salas para sesiones en
 * grupo.
 *
 * Flujo principal:
 * 1. Host crea sala → 2. Comparte roomId → 3. Usuarios se unen vía WebSocket
 */
@RestController
@RequestMapping("/rooms")
public class RoomController {
    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);

    // ✅ ACTUALIZADO: Usar RoomSessionManager en lugar de RoomService
    private final RoomSessionManager sessionManager;

    public RoomController(RoomSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Crea una nueva sala de sincronización musical
     * 
     * @param request {"senderId": "host1"} - Identificador único del usuario host
     * @return {"roomId": "uuid"} - ID único de la sala para compartir
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> create(@RequestBody CreateRoomRequest request) {
        logger.info("📝 Solicitud de creación de sala para senderId: {}", request.getSenderId());

        if (request.getSenderId() == null || request.getSenderId().trim().isEmpty()) {
            logger.warn("❌ Intento de crear sala sin senderId");
            return ResponseEntity.badRequest().body(Map.of("error", "missing_senderId"));
        }

        try {
            String roomId = UUID.randomUUID().toString().substring(0, 8); // ID más corto
            sessionManager.createRoom(roomId, request.getSenderId());
            logger.info("✅ Sala creada exitosamente: {} para host: {}", roomId, request.getSenderId());
            return ResponseEntity.ok(Map.of("roomId", roomId));
        } catch (IllegalArgumentException e) {
            logger.warn("❌ Error de validación al crear sala: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("⚠️ Conflicto al crear sala: {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("🚨 Error inesperado al crear sala: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "internal_server_error"));
        }
    }

    /**
     * Elimina una sala existente (solo accesible para el host)
     * 
     * @param roomId  ID de la sala a eliminar
     * @param request {"senderId": "host1"} - Para verificar permisos
     * @return 200 OK o error
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable String roomId,
            @RequestBody(required = false) CreateRoomRequest request) {

        logger.info("🗑️ Solicitud de eliminación de sala: {}", roomId);

        if (request == null || request.getSenderId() == null) {
            logger.warn("❌ Intento de eliminar sala {} sin senderId", roomId);
            return ResponseEntity.badRequest().body(Map.of("error", "missing_senderId"));
        }

        if (roomId == null || roomId.trim().isEmpty()) {
            logger.warn("❌ Intento de eliminar sala con ID inválido");
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_room_id"));
        }

        boolean deleted = sessionManager.deleteRoom(roomId, request.getSenderId());
        if (deleted) {
            logger.info("✅ Sala {} eliminada por: {}", roomId, request.getSenderId());
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } else {
            logger.warn("⚠️ No se pudo eliminar sala {} por: {}", roomId, request.getSenderId());
            return ResponseEntity.ok(Map.of("status", "completed"));
        }
    }

    /**
     * Obtiene metadata de una sala específica
     * 
     * @param roomId ID de la sala
     * @return Información de la sala: host, configuración, clientes conectados,
     *         tamaño de playlist
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable String roomId) {
        logger.debug("🔍 Consultando información de sala: {}", roomId);

        if (roomId == null || roomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        RoomSession session = sessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("❌ Intento de acceder a sala inexistente: {}", roomId);
            return ResponseEntity.notFound().build();
        }

        RoomResponse response = session.toRoomResponse();

        logger.debug("✅ Información de sala {} recuperada - Host: {}, Clientes: {}",
                roomId, session.getHostUserId(), session.getClientCount());
        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene el estado actual de reproducción
     */
    @GetMapping("/{roomId}/playback")
    public ResponseEntity<PlaybackState> getPlaybackState(
            @PathVariable String roomId,
            @RequestParam String senderId) {

        logger.debug("▶️ Consultando estado de reproducción de sala: {}", roomId);

        if (roomId == null || roomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        PlaybackState state = sessionManager.getCurrentPlaybackState(roomId, senderId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(state);
    }

    /**
     * Obtiene la playlist completa de una sala
     * 
     * @param roomId ID de la sala
     * @return Lista de tracks con información básica (id, título, quien lo añadió,
     *         timestamp)
     */
    @GetMapping("/{roomId}/playlist")
    public ResponseEntity<List<Map<String, Object>>> getPlaylist(@PathVariable String roomId) {
        logger.debug("🎵 Consultando playlist de sala: {}", roomId);

        if (roomId == null || roomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        RoomSession session = sessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("❌ Intento de acceder a playlist de sala inexistente: {}", roomId);
            return ResponseEntity.notFound().build();
        }

        List<TrackEntry> playlist = sessionManager.getPlaylistCopy(roomId);

        Map<String, Object> response = new HashMap<>();
        response.put("roomId", roomId);
        response.put("playlist", playlist.stream()
                .map(track -> {
                    Map<String, Object> trackMap = new HashMap<>();
                    trackMap.put("trackId", track.trackId());
                    trackMap.put("title", track.title());
                    trackMap.put("addedBy", track.addedBy());
                    trackMap.put("addedAt", track.addedAt());
                    return trackMap;
                })
                .collect(Collectors.toList()));
        response.put("totalTracks", playlist.size());
        response.put("nowPlayingIndex", session.getNowPlayingIndex());
        response.put("nowPlaying", session.getNowPlayingTrack());

        logger.debug("✅ Playlist de sala {} recuperada - {} tracks", roomId, playlist.size());
        return ResponseEntity.ok(Collections.singletonList(response));
    }

    /**
     * Actualiza configuración de la sala (solo host)
     * 
     * @param roomId  ID de la sala
     * @param request { "senderId": "host1", "allowGuestsAddTracks": true,
     *                "allowGuestsControl": false }
     * @return 200 OK o error con descripción
     */

    @PostMapping("/{roomId}/settings")
    public ResponseEntity<Map<String, String>> updateSettings(
            @PathVariable String roomId,
            @RequestBody RoomSettingRequest request) {

        logger.info("⚙️ Actualizando configuración de sala: {} por: {}", roomId, request.getSenderId());

        if (roomId == null || roomId.trim().isEmpty()) {
            logger.warn("❌ Intento de actualizar configuración con roomId inválido");
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_room_id"));
        }

        if (request.getSenderId() == null) {
            logger.warn("❌ Intento de actualizar configuración sin senderId");
            return ResponseEntity.badRequest().body(Map.of("error", "missing_senderId"));
        }

        if (!sessionManager.roomExists(roomId)) {
            logger.warn("❌ Intento de actualizar configuración de sala inexistente: {}", roomId);
            return ResponseEntity.status(404).body(Map.of("error", "room_not_found"));
        }

        if (!sessionManager.isHost(roomId, request.getSenderId())) {
            logger.warn("🚫 Intento no autorizado de actualizar configuración de sala {} por: {}",
                    roomId, request.getSenderId());
            return ResponseEntity.status(403).body(Map.of("error", "not_authorized"));
        }

        // ✅ ACTUALIZADO: Usar RoomSessionManager
        boolean success = sessionManager.updateRoomSettings(
                roomId,
                request.getSenderId(),
                request.getAllowGuestsAddTracks(),
                request.getAllowGuestsControl());

        if (success) {
            logger.info("✅ Configuración actualizada en sala: {} - addTracks: {}, control: {}",
                    roomId, request.getAllowGuestsAddTracks(), request.getAllowGuestsControl());
            return ResponseEntity.ok(Map.of("status", "updated"));
        } else {
            logger.warn("❌ Error al actualizar configuración en sala: {}", roomId);
            return ResponseEntity.status(400).body(Map.of("error", "update_failed"));
        }
    }

    /**
     * Endpoint para obtener estadísticas del servicio (debug/admin)
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getServiceStats() {
        logger.debug("📊 Solicitando estadísticas del servicio");
        Map<String, Object> stats = sessionManager.getServiceStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Endpoint para debug de salas (solo en desarrollo)
     */
    @GetMapping("/debug/rooms")
    public ResponseEntity<String> debugRooms() {
        logger.info("🐛 Ejecutando debug de salas");
        sessionManager.debugRooms();
        return ResponseEntity.ok("Check logs for room debug information");
    }

    /**
     * Health check del servicio
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        logger.debug("❤️ Health check solicitado");

        Map<String, Object> healthInfo = new HashMap<>();
        healthInfo.put("status", "healthy");
        healthInfo.put("timestamp", System.currentTimeMillis());
        healthInfo.put("service", "music-party-sync");
        healthInfo.put("activeRooms", sessionManager.getAllSessions().size());
        healthInfo.put("version", "1.0.0");

        return ResponseEntity.ok(healthInfo);
    }

    /**
     * Listar todas las salas (solo para debug/administración)
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllRooms() {
        logger.debug("📋 Listando todas las salas activas");

        Map<String, RoomSession> sessions = sessionManager.getAllSessions();
        List<Map<String, Object>> rooms = sessions.values().stream()
                .map(session -> {
                    Map<String, Object> roomInfo = new HashMap<>();
                    roomInfo.put("roomId", session.getRoomId());
                    roomInfo.put("hostUserId", session.getHostUserId());
                    roomInfo.put("state", session.getState().toString());
                    roomInfo.put("clientCount", session.getClientCount());
                    roomInfo.put("playlistSize", session.getPlaylistSize());
                    roomInfo.put("lastActivity", session.getLastActivityAt());
                    roomInfo.put("allowGuestsControl", session.isAllowGuestsControl());
                    roomInfo.put("allowGuestsEditQueue", session.isAllowGuestsEditQueue());
                    return roomInfo;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("totalRooms", rooms.size());
        response.put("rooms", rooms);
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }
}