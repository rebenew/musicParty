package com.rebenew.musicParty.syncserver.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sistema completo de eventos de salud para salas.
 * Combina acciones y eventos en un archivo cohesivo.
 */
public class RoomHealthSystem {

    /**
     * Acciones posibles de salud para una sala
     */
    public enum Action {
        HOST_DISCONNECTED,    // Host se desconectó
        HOST_RECONNECTED,     // Host se reconectó
        ROOM_EXPIRED,         // Sala expirada por timeout
        USER_INACTIVE,        // Usuario inactivo
        HEALTH_CHECK_PASS,    // Health check exitoso
        HEALTH_CHECK_FAIL     // Health check fallido
    }

    /**
     * Evento de salud de sala
     */
    @Getter
    public static class Event extends ApplicationEvent {
        // Getters
        private final String roomId;
        private final Action action;
        private final String userId;

        public Event(Object source, String roomId, Action action) {
            this(source, roomId, action, null);
        }

        public Event(Object source, String roomId, Action action, String userId) {
            super(source);
            this.roomId = roomId;
            this.action = action;
            this.userId = userId;
        }

        @JsonProperty("timestamp")
        public long getEventTimestamp() {
            return super.getTimestamp();
        }

        //  MÉTODOS DE FABRICACIÓN COMPLETOS
        public static Event hostDisconnected(Object source, String roomId) {
            return new Event(source, roomId, Action.HOST_DISCONNECTED);
        }

        public static Event hostReconnected(Object source, String roomId) {
            return new Event(source, roomId, Action.HOST_RECONNECTED);
        }

        public static Event roomExpired(Object source, String roomId) {
            return new Event(source, roomId, Action.ROOM_EXPIRED);
        }

        public static Event healthCheckPassed(Object source, String roomId) {
            return new Event(source, roomId, Action.HEALTH_CHECK_PASS);
        }

        public static Event healthCheckFailed(Object source, String roomId) {
            return new Event(source, roomId, Action.HEALTH_CHECK_FAIL);
        }

        public static Event userInactive(Object source, String roomId, String userId) {
            return new Event(source, roomId, Action.USER_INACTIVE, userId);
        }

        @Override
        public String toString() {
            return "RoomHealthEvent{" +
                    "roomId='" + roomId + '\'' +
                    ", action=" + action +
                    (userId != null ? ", userId='" + userId + '\'' : "") +
                    ", timestamp=" + super.getTimestamp() +
                    '}';
        }
    }
}