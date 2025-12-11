package com.rebenew.musicParty.syncserver.model;

import org.springframework.web.socket.WebSocketSession;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * RoomSession con finalización automática de track implementada de forma
 * segura.
 */
public class RoomSession {
    // IDENTIFICACIÓN
    private final String roomId;
    private final String hostUserId;
    private final Instant createdAt;
    private volatile Instant lastActivityAt;

    // CONFIG Y PERMISOS
    private volatile boolean allowGuestsControl = true;
    private volatile boolean allowGuestsEditQueue = false;

    // PLAYBACK
    private volatile Integer nowPlayingIndex = null;
    private volatile TrackEntry nowPlaying = null;
    private volatile long nowStartedAt = 0L;
    private volatile RoomState state = RoomState.CREATED;
    private volatile PlaybackState currentPlayback;

    // HEALTH & SCHEDULER
    private volatile Instant lastHostActivity;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("room-" + UUID.randomUUID().toString().substring(0, 8) + "-sched");
        return t;
    });
    private transient volatile ScheduledFuture<?> trackEndTask = null;

    private volatile WebSocketSession hostSession; // sesión host (si está conectada)
    private volatile boolean hostDisconnected = false;
    private volatile int failedHeartbeats = 0;

    // PLAYLIST
    private final List<TrackEntry> playlist = new CopyOnWriteArrayList<>();

    // CONEXIONES
    private transient final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();
    private transient final Map<String, String> sessionToSender = new ConcurrentHashMap<>();

    // CALLBACK para notificaciones al manager
    private transient RoomEventListener eventListener;

    // Added missing getters for host session and identifiers
    public WebSocketSession getHostSession() {
        return hostSession;
    }

    public String getHostUserId() {
        return hostUserId;
    }

    public String getRoomId() {
        return roomId;
    }

    public boolean isHost(String userId) {
        return hostUserId != null && hostUserId.equals(userId);
    }

    public RoomSession(String roomId, String hostUserId) {
        this.roomId = roomId;
        this.hostUserId = hostUserId;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.lastHostActivity = this.createdAt;
        this.state = RoomState.CREATED;
        this.hostDisconnected = false;
    }

    // ========== CONEXIONES ==========
    public void addClient(String userId, WebSocketSession session) {
        clients.put(userId, session);
        sessionToSender.put(session.getId(), userId);
        updateActivity();
        if (isHost(userId))
            attachHostSession(session);
    }

    public void removeClient(WebSocketSession session) {
        String userId = sessionToSender.remove(session.getId());
        if (userId != null) {
            clients.remove(userId);
            updateActivity();
            if (isHost(userId)) {
                detachHostSession();
                this.state = RoomState.HOST_DISCONNECTED;
            }
        }
    }

    public void removeClient(String userId) {
        WebSocketSession s = clients.remove(userId);
        if (s != null) {
            sessionToSender.remove(s.getId());
            updateActivity();
            if (isHost(userId)) {
                detachHostSession();
                this.state = RoomState.HOST_DISCONNECTED;
            }
        }
    }

    public String getUserIdForSession(WebSocketSession session) {
        return sessionToSender.get(session.getId());
    }

    public boolean isUserConnected(String userId) {
        return clients.containsKey(userId);
    }

    /**
     * Comprueba si el host está desconectado en base al estado real de su
     * WebSocketSession.
     * El timeout lo decide el manager (le pasará el ms).
     */
    public boolean isHostDisconnected(long hostTimeoutMs) {
        if (lastHostActivity == null)
            return true;
        long idleMs = Duration.between(lastHostActivity, Instant.now()).toMillis();
        return idleMs > hostTimeoutMs || !isUserConnected(hostUserId);
    }

    // ========== PLAYBACK ==========
    public synchronized boolean play(String userId, Integer trackIndex, Long positionMs) {
        if (!canUserControlPlayback(userId))
            return false;

        if (trackIndex != null) {
            if (!setNowPlayingIndex(trackIndex))
                return false;
        } else if (nowPlayingIndex == null && !playlist.isEmpty()) {
            if (!setNowPlayingIndex(0))
                return false;
        }

        long startTime = positionMs != null ? positionMs : 0L;
        this.nowStartedAt = System.currentTimeMillis() - startTime;
        this.state = RoomState.ACTIVE;
        updateActivity();
        if (isHost(userId))
            updateHostActivity();

        // schedule/reschedule automática
        scheduleTrackEnd();
        notifyTrackChanged();
        return true;
    }

    public synchronized boolean pause(String userId) {
        if (!canUserControlPlayback(userId) || nowPlayingIndex == null)
            return false;
        this.state = RoomState.PAUSED;
        updateActivity();
        if (isHost(userId))
            updateHostActivity();

        // cancelar finalización automática mientras esté en pausa
        cancelTrackEnd();
        return true;
    }

    public synchronized boolean nextTrack(String userId) {
        if (!canUserControlPlayback(userId) || nowPlayingIndex == null)
            return false;
        int nextIndex = nowPlayingIndex + 1;
        if (nextIndex >= playlist.size()) {
            // fin de playlist: limpiamos estado de reproducción
            nowPlayingIndex = null;
            nowPlaying = null;
            nowStartedAt = 0L;
            this.state = RoomState.CREATED;
            cancelTrackEnd();
            notifyPlaylistEnded();
            return false;
        }

        if (!setNowPlayingIndex(nextIndex))
            return false;
        this.nowStartedAt = System.currentTimeMillis();
        this.state = RoomState.ACTIVE;
        updateActivity();
        if (isHost(userId))
            updateHostActivity();

        scheduleTrackEnd();
        notifyTrackChanged();
        return true;
    }

    public synchronized boolean previousTrack(String userId) {
        if (!canUserControlPlayback(userId) || nowPlayingIndex == null)
            return false;
        int prevIndex = nowPlayingIndex - 1;
        if (prevIndex < 0)
            return false;
        if (!setNowPlayingIndex(prevIndex))
            return false;

        this.nowStartedAt = System.currentTimeMillis();
        this.state = RoomState.ACTIVE;
        updateActivity();
        if (isHost(userId))
            updateHostActivity();

        scheduleTrackEnd();
        notifyTrackChanged();
        return true;
    }

    public synchronized boolean seek(String userId, long positionMs) {
        if (!canUserControlPlayback(userId) || nowPlayingIndex == null)
            return false;
        TrackEntry t = getNowPlayingTrack();
        if (t == null)
            return false;
        if (positionMs < 0 || positionMs > t.getDurationMs())
            return false;

        this.nowStartedAt = System.currentTimeMillis() - positionMs;
        updateActivity();
        if (isHost(userId))
            updateHostActivity();

        scheduleTrackEnd();
        notifyTrackChanged();
        return true;
    }

    // ========== FINALIZACIÓN AUTOMÁTICA ==========
    /**
     * Programa la tarea que se disparará cuando el track actual termine.
     * Si ya existe una tarea, la cancela primero. Es seguro frente a race
     * conditions (synchronized).
     */
    private synchronized void scheduleTrackEnd() {
        // cancelar tarea anterior si existe
        cancelTrackEnd();

        TrackEntry track = getNowPlayingTrack();
        if (track == null)
            return;

        long elapsed = getCurrentPlaybackPosition();
        long remaining = track.getDurationMs() - elapsed;
        if (remaining <= 0) {
            // si ya debería haber terminado, ejecutar handler en hilo del scheduler
            // inmediatamente
            trackEndTask = scheduler.schedule(this::handleTrackEndSafely, 0, TimeUnit.MILLISECONDS);
            return;
        }

        trackEndTask = scheduler.schedule(this::handleTrackEndSafely, remaining, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancela la tarea programada de final de track (si existe).
     */
    private synchronized void cancelTrackEnd() {
        if (trackEndTask != null && !trackEndTask.isDone()) {
            trackEndTask.cancel(false);
        }
        trackEndTask = null;
    }

    // Reemplaza toda la playlist (Sincronización Host -> Server)
    public synchronized boolean replacePlaylist(List<TrackEntry> newTracks, String sourceUserId) {
        if (!isHost(sourceUserId))
            return false;

        playlist.clear();
        playlist.addAll(newTracks);

        // Ajustar índice de reproducción si es necesario
        if (nowPlayingIndex != null) {
            if (nowPlayingIndex >= playlist.size()) {
                nowPlayingIndex = null;
                nowPlaying = null;
            } else {
                nowPlaying = playlist.get(nowPlayingIndex);
            }
        }

        updateActivity();
        updateHostActivity();
        notifyPlaylistChanged();
        return true;
    }

    /**
     * Metodo seguro que ejecuta la lógica al terminar el track.
     * Se asegura de capturar excepciones y de invocar la transición de track de
     * forma sincronizada.
     */
    private void handleTrackEndSafely() {
        try {
            // Intentamos avanzar al siguiente track usando el host como actor (coherente
            // con tu lógica).
            boolean advanced;
            synchronized (this) {
                // si nadie está reproduciendo o ya no hay track, evitamos cambiar nada
                if (nowPlayingIndex == null) {
                    advanced = false;
                } else {
                    // Intentar avanzar al siguiente track; nextTrack() ya reprograrma si avanza
                    advanced = nextTrack(hostUserId);
                }
            }

            if (!advanced) {
                // fin de playlist -> notificar al manager
                notifyPlaylistEnded();
            }
            // si advanced == true, nextTrack() ya llamó notifyTrackChanged()
        } catch (Throwable t) {
            // Loguear si necesitas (no tenemos logger en esta clase), pero no lanzar
            t.printStackTrace();
        }
    }

    private void notifyTrackChanged() {
        RoomEventListener l = this.eventListener;
        if (l != null) {
            try {
                l.onTrackChanged(this);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyPlaylistEnded() {
        RoomEventListener l = this.eventListener;
        if (l != null) {
            try {
                l.onPlaylistEnded(this);
            } catch (Throwable ignored) {
            }
        }
    }

    // ========== PLAYLIST ==========
    public boolean addTrack(TrackEntry track, String userId) {
        if (!canUserModifyPlaylist(userId))
            return false;
        playlist.add(track);
        updateActivity();
        return true;
    }

    public boolean removeTrack(int trackIndex, String userId) {
        if (!canUserModifyPlaylist(userId))
            return false;
        if (trackIndex < 0 || trackIndex >= playlist.size())
            return false;
        playlist.remove(trackIndex);
        adjustNowPlayingIndexAfterRemove(trackIndex);
        updateActivity();
        return true;
    }

    public boolean moveTrack(int fromIndex, int toIndex, String userId) {
        if (!canUserModifyPlaylist(userId))
            return false;
        if (fromIndex < 0 || fromIndex >= playlist.size() || toIndex < 0 || toIndex >= playlist.size())
            return false;
        TrackEntry moved = playlist.remove(fromIndex);
        playlist.add(toIndex, moved);
        adjustNowPlayingIndexAfterMove(fromIndex, toIndex);
        updateActivity();
        return true;
    }

    public boolean clearPlaylist(String userId) {
        if (!isHost(userId))
            return false;
        playlist.clear();
        nowPlayingIndex = null;
        nowPlaying = null;
        nowStartedAt = 0L;
        cancelTrackEnd();
        updateActivity();
        return true;
    }

    // ========== PERMISOS ==========
    public boolean canUserControlPlayback(String userId) {
        return isHost(userId) || allowGuestsControl;
    }

    public boolean canUserModifyPlaylist(String userId) {
        return isHost(userId) || allowGuestsEditQueue;
    }

    // ========== UTILIDADES ==========
    private boolean setNowPlayingIndex(int index) {
        if (index < 0 || index >= playlist.size())
            return false;
        this.nowPlayingIndex = index;
        this.nowPlaying = playlist.get(index);
        return true;
    }

    private void adjustNowPlayingIndexAfterRemove(int removedIndex) {
        if (nowPlayingIndex == null)
            return;
        if (nowPlayingIndex == removedIndex) {
            nowPlayingIndex = null;
            nowPlaying = null;
            nowStartedAt = 0L;
            cancelTrackEnd();
        } else if (removedIndex < nowPlayingIndex) {
            nowPlayingIndex--;
        }
    }

    private void adjustNowPlayingIndexAfterMove(int fromIndex, int toIndex) {
        if (nowPlayingIndex == null)
            return;
        if (nowPlayingIndex == fromIndex) {
            nowPlayingIndex = toIndex;
        } else if (fromIndex < nowPlayingIndex && toIndex >= nowPlayingIndex) {
            nowPlayingIndex--;
        } else if (fromIndex > nowPlayingIndex && toIndex <= nowPlayingIndex) {
            nowPlayingIndex++;
        }
    }

    public long getCurrentPlaybackPosition() {
        if (nowStartedAt <= 0)
            return 0L;
        return System.currentTimeMillis() - nowStartedAt;
    }

    public TrackEntry getNowPlayingTrack() {
        return nowPlayingIndex != null && nowPlayingIndex < playlist.size() ? playlist.get(nowPlayingIndex) : null;
    }

    // ========== ACTIVITY / HOST ACTIVITY ==========
    public void updateActivity() {
        this.lastActivityAt = Instant.now();
    }

    public void updateHostActivity() {
        this.lastHostActivity = Instant.now();
        this.hostDisconnected = false;
    }

    public void attachHostSession(WebSocketSession session) {
        this.hostSession = session;
        updateHostActivity();
        this.hostDisconnected = false;
        this.state = RoomState.ACTIVE;
    }

    public void detachHostSession() {
        this.hostSession = null;
        this.hostDisconnected = true;
        this.lastHostActivity = Instant.now();
    }

    public void incrementFailedHeartbeats() {
        this.failedHeartbeats++;
    }

    // ========== CALLBACK ==========
    public void setEventListener(RoomEventListener listener) {
        this.eventListener = listener;
    }

    public interface RoomEventListener {
        void onTrackChanged(RoomSession session);

        void onPlaylistEnded(RoomSession session);

        void onPlaylistChanged(RoomSession session); // Nuevo evento
    }

    private void notifyPlaylistChanged() {
        if (eventListener != null) {
            try {
                eventListener.onPlaylistChanged(this);
            } catch (Exception e) {
                // log error
            }
        }
    }

    // ========== UTIL / DTO / CIERRE ==========
    public RoomResponse toRoomResponse() {
        return new RoomResponse(roomId, hostUserId, allowGuestsEditQueue, new HashSet<>(clients.keySet()),
                playlist.size());
    }

    public PlaybackState toPlaybackState(String senderId) {
        return PlaybackState.fromRoomSession(this);
    }

    public boolean isActive() {
        return state == RoomState.ACTIVE || state == RoomState.PAUSED;
    }

    /**
     * Debes invocar este metodo desde RoomSessionManager al eliminar/limpiar la
     * sala
     * para evitar fugas de hilos.
     */
    public synchronized void shutdownScheduler() {
        cancelTrackEnd();
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    // ========== GETTERS / SETTERS ==========

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Instant lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public boolean isHostDisconnected() {
        return hostDisconnected;
    }

    public void setHostDisconnected(boolean hostDisconnected) {
        this.hostDisconnected = hostDisconnected;
    }

    public boolean isAllowGuestsControl() {
        return allowGuestsControl;
    }

    public void setAllowGuestsControl(boolean allowGuestsControl) {
        this.allowGuestsControl = allowGuestsControl;
    }

    public boolean isAllowGuestsEditQueue() {
        return allowGuestsEditQueue;
    }

    public void setAllowGuestsEditQueue(boolean allowGuestsEditQueue) {
        this.allowGuestsEditQueue = allowGuestsEditQueue;
    }

    public List<TrackEntry> getPlaylist() {
        return playlist;
    }

    public Integer getNowPlayingIndex() {
        return nowPlayingIndex;
    }

    public TrackEntry getNowPlaying() {
        return nowPlaying;
    }

    public long getNowStartedAt() {
        return nowStartedAt;
    }

    public RoomState getState() {
        return state;
    }

    public void setState(RoomState state) {
        this.state = state;
    }

    public Instant getLastHostActivity() {
        return lastHostActivity;
    }

    public void setLastHostActivity(Instant lastHostActivity) {
        this.lastHostActivity = lastHostActivity;
    }

    public int getFailedHeartbeats() {
        return failedHeartbeats;
    }

    public void setFailedHeartbeats(int failedHeartbeats) {
        this.failedHeartbeats = failedHeartbeats;
    }

    public Map<String, WebSocketSession> getClients() {
        return clients;
    }

    public Map<String, String> getSessionToSender() {
        return sessionToSender;
    }

    public int getClientCount() {
        return clients.size();
    }

    public int getPlaylistSize() {
        return playlist.size();
    }

    @Override
    public String toString() {
        return "RoomSession{" +
                "roomId='" + roomId + '\'' +
                ", hostUserId='" + hostUserId + '\'' +
                ", state=" + state +
                ", allowGuestsControl=" + allowGuestsControl +
                ", allowGuestsEditQueue=" + allowGuestsEditQueue +
                ", playlistSize=" + playlist.size() +
                ", nowPlayingIndex=" + nowPlayingIndex +
                ", clientsCount=" + clients.size() +
                ", lastHostActivity=" + lastHostActivity +
                ", lastActivityAt=" + lastActivityAt +
                '}';
    }
}
