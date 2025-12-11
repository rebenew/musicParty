package com.rebenew.musicParty.syncserver.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mensaje WebSocket unificado con diseño consistente.
 * Usa un solo campo 'data' para todos los payloads.
 */

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncMsg {
    // Metadatos básicos
    private String type; // "system", "playback", "playlist", etc.
    private String subType; // subtipo específico
    private String roomId;
    private String senderId;
    private String correlationId;
    private Long timestamp;

    // Compatibilidad con clientes antiguos que envían "action"
    public void setAction(String action) {
        this.subType = action;
    }

    // ✅ UNIFICADO: Solo un campo para datos
    private Object data;

    // ==================== CONSTRUCTORES ESTÁTICOS MEJORADOS ====================

    public static SyncMsg system(String subType, String roomId, Object data) {
        return new SyncMsg("system", subType, roomId, null, data);
    }

    public static SyncMsg playback(String action, String roomId, String senderId, Object playbackData) {
        SyncMsg msg = new SyncMsg("playback", action, roomId, senderId, playbackData);
        return msg;
    }

    public static SyncMsg playlist(String action, String roomId, String senderId, Object playlistData) {
        return new SyncMsg("playlist", action, roomId, senderId, playlistData);
    }

    public static SyncMsg ack(boolean success, String reason, String correlationId) {
        Map<String, Object> ackData = Map.of("success", success, "reason", reason);
        SyncMsg msg = new SyncMsg("ack", null, null, null, ackData);
        msg.setCorrelationId(correlationId);
        return msg;
    }

    public static SyncMsg error(String errorCode, String message, String correlationId) {
        Map<String, Object> errorData = Map.of("code", errorCode, "message", message);
        SyncMsg msg = new SyncMsg("error", null, null, null, errorData);
        msg.setCorrelationId(correlationId);
        return msg;
    }

    public static SyncMsg heartbeat(String roomId, String senderId) {
        return new SyncMsg("heartbeat", null, roomId, senderId, null);
    }

    // Constructor principal privado
    private SyncMsg(String type, String subType, String roomId, String senderId, Object data) {
        this.type = type;
        this.subType = subType;
        this.roomId = roomId;
        this.senderId = senderId;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // Constructor público vacío para Jackson
    public SyncMsg() {
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== MÉTODOS DE CONVENIENCIA MEJORADOS ====================

    /**
     * Métodos de verificación de tipo (más legibles)
     */
    public boolean isSystem() {
        return "system".equals(type);
    }

    public boolean isPlayback() {
        return "playback".equals(type);
    }

    public boolean isPlaylist() {
        return "playlist".equals(type);
    }

    public boolean isAck() {
        return "ack".equals(type);
    }

    public boolean isError() {
        return "error".equals(type);
    }

    public boolean isHeartbeat() {
        return "heartbeat".equals(type);
    }

    /**
     * Verifica si es un ACK exitoso
     */
    public boolean isSuccess() {
        if (!isAck())
            return false;
        Map<String, Object> ackData = getDataAsMap();
        return ackData != null && Boolean.TRUE.equals(ackData.get("success"));
    }

    /**
     * Extracción segura de datos
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        return data instanceof Map ? (Map<String, Object>) data : null;
    }

    public String getStringData(String key) {
        Map<String, Object> dataMap = getDataAsMap();
        Object value = dataMap != null ? dataMap.get(key) : null;
        return value != null ? value.toString() : null;
    }

    public Integer getIntData(String key) {
        Map<String, Object> dataMap = getDataAsMap();
        Object value = dataMap != null ? dataMap.get(key) : null;
        if (value instanceof Number)
            return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public Long getLongData(String key) {
        Map<String, Object> dataMap = getDataAsMap();
        Object value = dataMap != null ? dataMap.get(key) : null;
        if (value instanceof Number)
            return ((Number) value).longValue();
        return null;
    }

    public Boolean getBoolData(String key) {
        Map<String, Object> dataMap = getDataAsMap();
        Object value = dataMap != null ? dataMap.get(key) : null;
        if (value instanceof Boolean)
            return (Boolean) value;
        if (value instanceof String)
            return Boolean.parseBoolean((String) value);
        return null;
    }

    public Boolean getBoolData(String key, boolean defaultValue) {
        Boolean value = getBoolData(key);
        return value != null ? value : defaultValue;
    }

    @Override
    public String toString() {
        return String.format("SyncMsg{type='%s', subType='%s', roomId='%s', senderId='%s', timestamp=%d}",
                type, subType, roomId, senderId, timestamp);
    }

    public Float getFloatData(String key) {
        Map<String, Object> dataMap = getDataAsMap();
        if (dataMap == null)
            return null;

        Object value = dataMap.get(key);
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